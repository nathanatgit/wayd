// SPDX-License-Identifier: GPL-3.0-or-later
//! Per-flow relays between the userspace stack and real sockets.
//!
//! Idle handling is per *flow*, not per direction: a long download with no upload (or a server-push UDP
//! stream) must not be torn down just because one direction is quiet.

use std::net::SocketAddr;
use std::pin::Pin;
use std::task::{Context, Poll};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use ipstack::{IpStackTcpStream, IpStackUdpStream};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};
use tokio::net::{TcpSocket, UdpSocket};
use tokio::time::timeout;

use crate::events::EventSink;
use crate::shared::Shared;

/// Max payload prefix reported per direction for TCP flows (enough for TLS ClientHello / HTTP head).
const TCP_CAPTURE: usize = 16 * 1024;
/// Short on purpose: the client already saw the handshake complete, so a slow failure stalls the app.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(6);
/// Apps keep pooled / push connections idle for a long time (the userspace stack allows 2 h).
pub const TCP_IDLE: Duration = Duration::from_secs(3600);
/// QUIC and other UDP flows must keep their source port across quiet periods or the server loses them.
pub const UDP_IDLE: Duration = Duration::from_secs(300);
const DNS_IDLE: Duration = Duration::from_secs(15);
const DNS_PORT: u16 = 53;
const PROGRESS_EVERY: Duration = Duration::from_secs(2);
/// How long a flow waits for Kotlin's intercept decision before falling back to passthrough.
const DECISION_WAIT: Duration = Duration::from_millis(600);

/// Shared "last byte moved" clock for one flow.
struct Activity {
    start: Instant,
    last_ms: AtomicU64,
}

impl Activity {
    fn new() -> Arc<Self> {
        Arc::new(Activity { start: Instant::now(), last_ms: AtomicU64::new(0) })
    }

    fn touch(&self) {
        self.last_ms.store(self.start.elapsed().as_millis() as u64, Ordering::Relaxed);
    }

    fn idle(&self) -> Duration {
        let now = self.start.elapsed().as_millis() as u64;
        Duration::from_millis(now.saturating_sub(self.last_ms.load(Ordering::Relaxed)))
    }
}

/// Reads from a stream; returns 0 on EOF, error, or when the whole flow has been idle for `limit`.
async fn read_idle<R: AsyncRead + Unpin>(from: &mut R, buf: &mut [u8], act: &Activity, limit: Duration) -> usize {
    loop {
        match timeout(limit, from.read(buf)).await {
            Ok(Ok(n)) => {
                if n > 0 {
                    act.touch();
                }
                return n;
            }
            Ok(Err(_)) => return 0,
            Err(_) => {
                if act.idle() >= limit {
                    return 0;
                }
            }
        }
    }
}

pub async fn tcp(id: u64, client: IpStackTcpStream, sink: EventSink, shared: Arc<Shared>) {
    let src = client.local_addr();
    let dst = client.peer_addr();
    tcp_flow(id, client, src, dst, sink, shared).await;
}

pub(crate) async fn tcp_flow<S: AsyncRead + AsyncWrite + Unpin + Send>(id: u64, client: S, src: SocketAddr, dst: SocketAddr, sink: EventSink, shared: Arc<Shared>) {
    let control = shared.blocking.open(id, dst.ip());
    tokio::select! {
        biased;
        d = control.cancelled() => {
            sink.policy(id, d);
            sink.route(id, 3);
            sink.close(id, control.up.load(Ordering::Relaxed), control.down.load(Ordering::Relaxed));
        }
        _ = tcp_inner(id, client, src, dst, sink.clone(), shared.clone(), control.clone()) => {}
    }
    shared.blocking.close(id);
    shared.forget(id);
}

async fn tcp_inner<S: AsyncRead + AsyncWrite + Unpin + Send>(id: u64, mut client: S, src: SocketAddr, dst: SocketAddr, sink: EventSink, shared: Arc<Shared>, control: Arc<crate::blocking::FlowControl>) {
    // Register the decision slot BEFORE announcing the flow so Kotlin's answer cannot arrive first.
    let decision = if shared.mitm_active() { Some(shared.register(id)) } else { None };
    sink.open(id, 6, src, dst);

    let up = control.up.clone();
    let down = control.down.clone();

    if (shared.blocking.active() || shared.routing_active()) && timeout(Duration::from_secs(1), control.owner_ready()).await.is_err() {
        sink.policy(id, shared.blocking.outcome(id, true));
        sink.route(id, 4);
        sink.close(id, 0, 0);
        return;
    }
    let mut pre = Vec::new();
    // A global block list makes every client-speaks-first TCP connection worth the bounded hostname wait; protocols where the
    // server speaks first never show a hostname, so waiting would only delay them.
    let list_gate = shared.blocking.list_active() && !matches!(dst.port(), 21 | 22 | 25 | 110 | 143 | 587 | 3306 | 5432);
    if shared.blocking.needs_host(id) || list_gate || shared.routing_needs_host(id) {
        // Reuse Kotlin's bounded, stream-aware SNI/HTTP parser. DATA arrives before any upstream dial.
        let _ = timeout(Duration::from_millis(600), async {
            let mut buf = [0u8; 4096];
            while pre.len() < TCP_CAPTURE {
                tokio::select! {
                    biased;
                    _ = control.host_ready() => break,
                    n = client.read(&mut buf[..4096.min(TCP_CAPTURE - pre.len())]) => {
                        let n = match n { Ok(n) if n > 0 => n, _ => break };
                        sink.data(id, false, &buf[..n]);
                        pre.extend_from_slice(&buf[..n]);
                    }
                }
            }
            if pre.len() == TCP_CAPTURE { let _ = timeout(Duration::from_millis(100), control.host_ready()).await; }
        }).await;
    }
    let outcome = shared.blocking.outcome(id, false);
    sink.policy(id, outcome);
    if outcome.kind == 1 {
        sink.route(id, 3);
        sink.close(id, 0, 0);
        return;
    }

    // Read the client's first bytes while the outbound connect is in flight, so the TLS SNI / HTTP Host
    // of connections that fail or stall is still recorded. `read` is cancel-safe, so if the connect wins
    // the race nothing is lost.
    let pre_captured = pre.len();
    let mut first = vec![0u8; TCP_CAPTURE];
    first[..pre.len()].copy_from_slice(&pre);
    let mut early: Option<usize> = if pre.is_empty() { None } else { Some(pre.len()) };
    // Preserve this flow's selected metadata across research TLS fallback.
    // Late host attribution must not turn a proxy failure into a Direct retry.
    let route_metadata = shared.blocking.metadata(id);
    let connect_fut = connect(dst, &shared, id, &sink, &route_metadata);
    tokio::pin!(connect_fut);
    let remote = loop {
        tokio::select! {
            r = &mut connect_fut => break r,
            res = client.read(&mut first), if early.is_none() => match res {
                Ok(n) if n > 0 => early = Some(n),
                _ => break None, // client closed before sending anything
            },
        }
    };

    let mut captured0 = 0usize;
    if let Some(n) = early {
        let take = n.min(TCP_CAPTURE);
        if pre_captured == 0 { sink.data(id, false, &first[..take]); }
        captured0 = take;
    }

    #[cfg(feature = "mitm")]
    let mut remote = remote;
    #[cfg(not(feature = "mitm"))]
    let _ = &decision;
    #[cfg(feature = "mitm")]
    if let (Some(m), Some(rx), Some(n)) = (shared.mitm(), decision, early) {
        if first[..n].len() > 2 && first[0] == 0x16 && first[1] == 0x03 {
            let allow = matches!(timeout(DECISION_WAIT, rx).await, Ok(Ok(true)));
            shared.forget(id);
            if allow {
                if let Some(r) = remote.take() {
                    let mut rec = crate::mitm::tls::Recording::new(client, first[..n].to_vec());
                    match m.intercept_counted(&mut rec, r, id, &sink, Some(&control)).await {
                        crate::mitm::tls::Outcome::Done(u, d) => {
                            sink.close(id, u, d);
                        }
                        crate::mitm::tls::Outcome::ClientRejected => {
                            sink.close(id, 0, 0);
                        }
                        crate::mitm::tls::Outcome::Fallback(replay) => {
                            // Nothing was sent to the app: continue as a plain relay, replaying what we consumed.
                            if let Some(mut r2) = connect(dst, &shared, id, &sink, &route_metadata).await {
                                if r2.write_all(&replay).await.is_ok() {
                                    up.fetch_add(replay.len() as u64, Ordering::Relaxed);
                                    let act = Activity::new();
                                    act.touch();
                                    let (cr, cw) = tokio::io::split(rec);
                                    let (rr, rw) = tokio::io::split(r2);
                                    let _ = tokio::join!(
                                        pump(id, false, cr, rw, up.clone(), sink.clone(), TCP_CAPTURE, act.clone()),
                                        pump(id, true, rr, cw, down.clone(), sink.clone(), 0, act.clone()),
                                    );
                                }
                            }
                            sink.close(id, up.load(Ordering::Relaxed), down.load(Ordering::Relaxed));
                        }
                    }
                    return;
                }
            }
        }
    }
    #[cfg(feature = "mitm")]
    let remote = remote;

    if let Some(mut remote) = remote {
        let mut ok = true;
        if let Some(n) = early {
            ok = remote.write_all(&first[..n]).await.is_ok();
            up.fetch_add(n as u64, Ordering::Relaxed);
        }
        if ok {
            let act = Activity::new();
            act.touch();
            let (cr, cw) = tokio::io::split(client);
            let (rr, rw) = tokio::io::split(remote);
            let _ = tokio::join!(
                pump(id, false, cr, rw, up.clone(), sink.clone(), captured0, act.clone()),
                pump(id, true, rr, cw, down.clone(), sink.clone(), 0, act.clone()),
            );
        }
    }
    sink.close(id, up.load(Ordering::Relaxed), down.load(Ordering::Relaxed));
}

trait Transport: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Transport for T {}

struct OutboundStream {
    io: Box<dyn Transport>,
    #[cfg(unix)]
    _lease: Option<crate::routing::Lease>,
}
impl AsyncRead for OutboundStream {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, b: &mut ReadBuf<'_>) -> Poll<std::io::Result<()>> { Pin::new(&mut *self.io).poll_read(cx, b) }
}
impl AsyncWrite for OutboundStream {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, b: &[u8]) -> Poll<std::io::Result<usize>> { Pin::new(&mut *self.io).poll_write(cx, b) }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> { Pin::new(&mut *self.io).poll_flush(cx) }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> { Pin::new(&mut *self.io).poll_shutdown(cx) }
}

async fn connect(dst: SocketAddr, shared: &Shared, id: u64, sink: &EventSink, metadata: &(String, String)) -> Option<OutboundStream> {
    #[cfg(unix)]
    if let Some(route) = &shared.routing {
        let (owner, host) = metadata.clone();
        if route.selected(&owner) {
            return match route.dial(dst, owner, host, false).await {
                Ok((fd, lease, kind)) => match crate::routing::stream(fd) {
                    Ok(io) => { sink.route(id, kind); Some(OutboundStream { io: Box::new(io), _lease: Some(lease) }) },
                    Err(_) => { sink.route(id, 4); None },
                },
                Err(kind) => { sink.route(id, kind); None },
            };
        }
    }
    let sock = match if dst.is_ipv4() { TcpSocket::new_v4() } else { TcpSocket::new_v6() } {
        Ok(socket) => socket,
        Err(_) => { sink.route(id, 4); return None; },
    };
    let _ = sock.set_nodelay(true);
    match timeout(CONNECT_TIMEOUT, sock.connect(dst)).await {
        Ok(Ok(io)) => { sink.route(id, 1); Some(OutboundStream { io: Box::new(io), #[cfg(unix)] _lease: None }) },
        _ => { sink.route(id, 4); None },
    }
}

/// Copies `from` to `to`, counting bytes and reporting the first `TCP_CAPTURE` bytes.
/// When this direction ends it half-closes `to`; the other direction keeps running until it ends too.
#[allow(clippy::too_many_arguments)]
async fn pump<R, W>(
    id: u64,
    down: bool,
    mut from: R,
    mut to: W,
    counter: Arc<AtomicU64>,
    sink: EventSink,
    mut captured: usize,
    act: Arc<Activity>,
) where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let mut buf = vec![0u8; 32 * 1024];
    let mut last_report = Instant::now();
    loop {
        let n = read_idle(&mut from, &mut buf, &act, TCP_IDLE).await;
        if n == 0 {
            break;
        }
        if captured < TCP_CAPTURE {
            let take = n.min(TCP_CAPTURE - captured);
            sink.data(id, down, &buf[..take]);
            captured += take;
        }
        if to.write_all(&buf[..n]).await.is_err() {
            break;
        }
        act.touch();
        let total = counter.fetch_add(n as u64, Ordering::Relaxed) + n as u64;
        if last_report.elapsed() >= PROGRESS_EVERY {
            sink.progress(id, down, total);
            last_report = Instant::now();
        }
    }
    let _ = to.shutdown().await;
}

pub async fn udp(id: u64, client: IpStackUdpStream, sink: EventSink, shared: Arc<Shared>) {
    let src = client.local_addr();
    let dst = client.peer_addr();
    let control = shared.blocking.open(id, client.peer_addr().ip());
    tokio::select! {
        biased;
        d = control.cancelled() => {
            sink.policy(id, d);
            sink.route(id, 3);
            sink.close(id, control.up.load(Ordering::Relaxed), control.down.load(Ordering::Relaxed));
        }
        _ = udp_inner(id, client, src, dst, sink.clone(), shared.clone(), control.clone()) => {}
    }
    shared.blocking.close(id);
    shared.forget(id);
}

async fn udp_inner<S: AsyncRead + AsyncWrite + Unpin>(id: u64, client: S, src: SocketAddr, dst: SocketAddr, sink: EventSink, shared: Arc<Shared>, control: Arc<crate::blocking::FlowControl>) {
    // QUIC (UDP 443) of an intercepted app is dropped so the app falls back to TCP+TLS, which can be intercepted.
    let decision = if shared.mitm_active() && dst.port() == 443 { Some(shared.register(id)) } else { None };
    sink.open(id, 17, src, dst);
    let error = (shared.blocking.active() || shared.routing_active()) && timeout(Duration::from_secs(1), control.owner_ready()).await.is_err();
    let outcome = shared.blocking.outcome(id, error);
    sink.policy(id, outcome);
    if outcome.kind == 1 || error { sink.route(id, if error { 4 } else { 3 }); sink.close(id, 0, 0); return; }
    if let Some(rx) = decision {
        let drop_it = matches!(timeout(DECISION_WAIT, rx).await, Ok(Ok(true)));
        shared.forget(id);
        if drop_it {
            sink.route(id, 3);
            sink.close(id, 0, 0);
            return;
        }
    }

    let up = control.up.clone();
    let down = control.down.clone();
    let is_dns = dst.port() == DNS_PORT;
    let limit = if is_dns { DNS_IDLE } else { UDP_IDLE };

    if let Some(sock) = connect_udp(dst, &shared, id, &sink).await {
            let sock = Arc::new(sock);
            let act = Activity::new();
            act.touch();
            let (mut cr, mut cw) = tokio::io::split(client);

            let up_task = {
                let (sock, up, sink, act) = (sock.clone(), up.clone(), sink.clone(), act.clone());
                async move {
                    let mut buf = vec![0u8; 65535];
                    let mut first = true;
                    loop {
                        let n = read_idle(&mut cr, &mut buf, &act, limit).await;
                        if n == 0 {
                            break;
                        }
                        if is_dns || first {
                            sink.data(id, false, &buf[..n.min(1500)]);
                            first = false;
                        }
                        if sock.send(&buf[..n]).await.is_err() {
                            break;
                        }
                        up.fetch_add(n as u64, Ordering::Relaxed);
                    }
                }
            };
            let down_task = {
                let (sock, down, sink, act) = (sock.clone(), down.clone(), sink.clone(), act.clone());
                async move {
                    let mut buf = vec![0u8; 65535];
                    let mut first = true;
                    loop {
                        let n = match timeout(limit, sock.recv(&mut buf)).await {
                            Ok(Ok(n)) => n,
                            Ok(Err(_)) => break,
                            Err(_) => {
                                if act.idle() >= limit {
                                    break;
                                }
                                continue;
                            }
                        };
                        act.touch();
                        if is_dns || first {
                            sink.data(id, true, &buf[..n.min(1500)]);
                            first = false;
                        }
                        if cw.write_all(&buf[..n]).await.is_err() {
                            break;
                        }
                        down.fetch_add(n as u64, Ordering::Relaxed);
                    }
                }
            };
            // The flow ends when either side errors or the *whole flow* has been idle for `limit`.
            tokio::select! { _ = up_task => {}, _ = down_task => {} }
    }
    sink.close(id, up.load(Ordering::Relaxed), down.load(Ordering::Relaxed));
}

enum Datagram {
    Direct(UdpSocket),
    #[cfg(unix)]
    Routed { socket: tokio::net::UnixDatagram, _lease: crate::routing::Lease },
}
impl Datagram {
    async fn send(&self, b: &[u8]) -> std::io::Result<usize> {
        match self { Self::Direct(s) => s.send(b).await, #[cfg(unix)] Self::Routed { socket, .. } => socket.send(b).await }
    }
    async fn recv(&self, b: &mut [u8]) -> std::io::Result<usize> {
        match self { Self::Direct(s) => s.recv(b).await, #[cfg(unix)] Self::Routed { socket, .. } => socket.recv(b).await }
    }
}
async fn connect_udp(dst: SocketAddr, shared: &Shared, id: u64, sink: &EventSink) -> Option<Datagram> {
    #[cfg(unix)]
    if let Some(route) = &shared.routing {
        let (owner, host) = shared.blocking.metadata(id);
        if route.selected(&owner) {
            return match route.dial(dst, owner, host, true).await {
                Ok((fd, lease, kind)) => match crate::routing::datagram(fd) {
                    Ok(socket) => { sink.route(id, kind); Some(Datagram::Routed { socket, _lease: lease }) },
                    Err(_) => { sink.route(id, 4); None },
                },
                Err(kind) => { sink.route(id, kind); None },
            };
        }
    }
    let bind = if dst.is_ipv4() { "0.0.0.0:0" } else { "[::]:0" };
    if let Ok(socket) = UdpSocket::bind(bind).await {
        if socket.connect(dst).await.is_ok() { sink.route(id, 1); return Some(Datagram::Direct(socket)); }
    }
    sink.route(id, 4); None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(target_os = "android")]
    #[tokio::test]
    async fn android_fallback_keeps_the_original_route_when_host_arrives_late() {
        let proxy = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let destination = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let profile = format!("proxies:\n - {{name: test, type: http, server: 127.0.0.1, port: {}}}\nrules: ['DOMAIN,late.test,DIRECT', 'MATCH,test']", proxy.local_addr().unwrap().port());
        let route = crate::routing::Routing::new(profile.as_bytes(), "0:com.example.probe").unwrap();
        let shared = Shared::with_routing(Some(route));
        shared.blocking.open(1, destination.local_addr().unwrap().ip());
        shared.blocking.info(1, "0:com.example.probe", false);
        let original = shared.blocking.metadata(1);
        let (sink, events) = EventSink::channel();
        let server = tokio::spawn(async move {
            for _ in 0..2 {
                let (mut c, _) = timeout(Duration::from_secs(2), proxy.accept()).await.unwrap().unwrap();
                let mut head = Vec::new(); let mut b = [0; 1];
                while !head.ends_with(b"\r\n\r\n") { c.read_exact(&mut b).await.unwrap(); head.push(b[0]); assert!(head.len() < 2048); }
                c.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await.unwrap();
            }
        });
        drop(connect(destination.local_addr().unwrap(), &shared, 1, &sink, &original).await.unwrap());
        shared.blocking.info(1, "late.test", true);
        drop(connect(destination.local_addr().unwrap(), &shared, 1, &sink, &original).await.unwrap());
        server.await.unwrap();
        assert!(timeout(Duration::from_millis(100), destination.accept()).await.is_err());
        assert_eq!(events.try_iter().filter(|e| e[4] == 8 && e[13] == 2).count(), 2);
    }

    #[cfg(target_os = "android")]
    #[tokio::test]
    async fn android_local_block_precedes_mihomo_and_proxy_error_never_dials_direct() {
        for blocked in [true, false] {
            let proxy = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let destination = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let profile = format!("proxies:\n - {{name: test, type: http, server: 127.0.0.1, port: {}}}\nrules: ['MATCH,test']", proxy.local_addr().unwrap().port());
            let route = crate::routing::Routing::new(profile.as_bytes(), "0:com.example.probe").unwrap();
            let shared = Shared::with_routing(Some(route));
            assert!(shared.blocking.install(if blocked { "WAYD1\t1\n1\t0:com.example.probe\tAPP\t*" } else { "WAYD1\t1" }));
            let (mut app, client) = tokio::io::duplex(1024);
            let (sink, events) = EventSink::channel();
            let local = shared.clone(); let dst = destination.local_addr().unwrap();
            let task = tokio::spawn(async move { tcp_flow(1, client, "127.0.0.1:20000".parse().unwrap(), dst, sink, local).await });
            let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
            loop {
                if events.try_iter().any(|e| e[4] == crate::events::OPEN) { break; }
                assert!(tokio::time::Instant::now() < deadline); tokio::time::sleep(Duration::from_millis(1)).await;
            }
            shared.blocking.info(1, "0:com.example.probe", false);
            app.write_all(b"not direct").await.unwrap();
            if blocked {
                assert!(timeout(Duration::from_millis(200), proxy.accept()).await.is_err());
            } else {
                let (mut server, _) = timeout(Duration::from_secs(2), proxy.accept()).await.unwrap().unwrap();
                let mut b = [0; 1024]; let n = server.read(&mut b).await.unwrap();
                assert!(std::str::from_utf8(&b[..n]).unwrap().starts_with("CONNECT "));
                server.write_all(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n").await.unwrap();
            }
            timeout(Duration::from_secs(2), task).await.unwrap().unwrap();
            assert!(timeout(Duration::from_millis(200), destination.accept()).await.is_err());
            let all: Vec<_> = events.try_iter().collect();
            assert!(all.iter().any(|e| e[4] == 8 && e[13] == if blocked { 3 } else { 4 }));
        }
    }

    #[tokio::test]
    async fn installing_a_rule_closes_an_existing_flow_and_preserves_counts() {
        let server = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let shared = Shared::new();
        assert!(shared.blocking.install("WAYD1\t1"));
        let (mut app, client) = tokio::io::duplex(1024);
        let (sink, events) = EventSink::channel();
        let task = tokio::spawn(tcp_flow(1, client, "127.0.0.1:40000".parse().unwrap(), server.local_addr().unwrap(), sink, shared.clone()));
        let (mut remote, _) = timeout(Duration::from_secs(1), server.accept()).await.unwrap().unwrap();
        shared.blocking.info(1, "0:org.example.app", false);
        app.write_all(b"hello").await.unwrap();
        let mut payload = [0u8; 5]; remote.read_exact(&mut payload).await.unwrap();
        assert!(shared.blocking.install("WAYD1\t2\n42\t0:org.example.app\tAPP\t*"));
        timeout(Duration::from_secs(1), task).await.unwrap().unwrap();
        assert_eq!(app.read(&mut payload).await.unwrap(), 0);
        assert_eq!(remote.read(&mut payload).await.unwrap(), 0);
        let all: Vec<_> = events.try_iter().collect();
        let closed = all.iter().find(|e| e[4] == crate::events::CLOSE).unwrap();
        assert_eq!(u64::from_be_bytes(closed[13..21].try_into().unwrap()), 5);
        assert!(all.iter().any(|e| e[4] == 7 && e[13] == 1));
    }

    #[tokio::test]
    async fn udp_app_and_ip_blocks_send_no_datagrams() {
        for (kind, target) in [("APP", "*"), ("IP", "127.0.0.1")] {
            let server = UdpSocket::bind("127.0.0.1:0").await.unwrap();
            let shared = Shared::new();
            assert!(shared.blocking.install(&format!("WAYD1\t1\n1\t0:org.example.app\t{kind}\t{target}")));
            let c = shared.blocking.open(1, server.local_addr().unwrap().ip());
            shared.blocking.info(1, "0:org.example.app", false);
            let (mut app, client) = tokio::io::duplex(1024);
            app.write_all(b"must not leave").await.unwrap();
            let (sink, events) = EventSink::channel();
            timeout(Duration::from_secs(1), udp_inner(1, client, "127.0.0.1:40000".parse().unwrap(), server.local_addr().unwrap(), sink, shared, c)).await.unwrap();
            assert!(timeout(Duration::from_millis(30), server.recv(&mut [0u8; 64])).await.is_err());
            assert_eq!(events.try_iter().find(|e| e[4] == 7).unwrap()[13], 1);
        }
    }

    #[tokio::test]
    async fn app_ip_and_host_blocks_never_dial_the_local_server() {
        for (kind, target) in [("APP", "*"), ("IP", "127.0.0.1"), ("HOST", "blocked.example")] {
            let server = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let shared = Shared::new();
            assert!(shared.blocking.install(&format!("WAYD1\t1\n1\t0:org.example.app\t{kind}\t{target}")));
            let c = shared.blocking.open(1, server.local_addr().unwrap().ip());
            shared.blocking.info(1, "0:org.example.app", false);
            shared.blocking.info(1, "blocked.example", true);
            let (_app, client) = tokio::io::duplex(1024);
            let (sink, events) = EventSink::channel();
            timeout(Duration::from_secs(1), tcp_inner(1, client, "127.0.0.1:40000".parse().unwrap(), server.local_addr().unwrap(), sink, shared, c)).await.unwrap();
            assert!(timeout(Duration::from_millis(30), server.accept()).await.is_err());
            let policy = events.try_iter().find(|e| e[4] == 7).unwrap();
            assert_eq!(policy[13], 1);
        }
    }

    #[tokio::test]
    async fn fragmented_host_prefix_waits_for_parser_before_dial() {
        let server = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let shared = Shared::new();
        assert!(shared.blocking.install("WAYD1\t1\n1\t0:org.example.app\tHOST\tblocked.example"));
        let c = shared.blocking.open(1, server.local_addr().unwrap().ip());
        shared.blocking.info(1, "0:org.example.app", false);
        let (mut app, client) = tokio::io::duplex(1024);
        let (sink, events) = EventSink::channel();
        let task = tokio::spawn(tcp_inner(1, client, "127.0.0.1:40000".parse().unwrap(), server.local_addr().unwrap(), sink, shared.clone(), c));
        app.write_all(b"GET / HTTP/1.1\r\nHo").await.unwrap();
        assert!(timeout(Duration::from_millis(30), server.accept()).await.is_err());
        app.write_all(b"st: blocked.example\r\n\r\n").await.unwrap();
        let mut prefix = Vec::new();
        timeout(Duration::from_secs(1), async {
            loop {
                for ev in events.try_iter() { if ev[4] == 2 { prefix.extend_from_slice(&ev[16..]); } }
                if prefix.ends_with(b"\r\n\r\n") { break; }
                tokio::time::sleep(Duration::from_millis(2)).await;
            }
        }).await.unwrap();
        shared.blocking.info(1, "blocked.example", true);
        timeout(Duration::from_secs(1), task).await.unwrap().unwrap();
        assert!(timeout(Duration::from_millis(30), server.accept()).await.is_err());
    }

    #[tokio::test]
    async fn allowed_flow_keeps_all_bytes_and_missing_names_are_visible() {
        let server = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let shared = Shared::new();
        assert!(shared.blocking.install("WAYD1\t1\n1\t0:org.example.other\tAPP\t*"));
        let c = shared.blocking.open(1, server.local_addr().unwrap().ip());
        shared.blocking.info(1, "0:org.example.app", false);
        let (mut app, client) = tokio::io::duplex(4096);
        let (sink, _events) = EventSink::channel();
        let task = tokio::spawn(tcp_inner(1, client, "127.0.0.1:40000".parse().unwrap(), server.local_addr().unwrap(), sink, shared, c));
        let payload = b"GET / HTTP/1.1\r\nHost: allowed.example\r\n\r\n";
        app.write_all(payload).await.unwrap(); app.shutdown().await.unwrap();
        let (mut remote, _) = timeout(Duration::from_secs(1), server.accept()).await.unwrap().unwrap();
        let mut received = Vec::new(); remote.read_to_end(&mut received).await.unwrap();
        assert_eq!(received, payload);
        remote.shutdown().await.unwrap(); drop(remote);
        timeout(Duration::from_secs(1), task).await.unwrap().unwrap();
    }

    #[tokio::test]
    async fn tcp_prefix_spans_reads_is_bounded_and_does_not_truncate_forwarding() {
        let payload = vec![42u8; TCP_CAPTURE + 4096];
        let (mut input, reader) = tokio::io::duplex(payload.len());
        let (writer, mut output) = tokio::io::duplex(payload.len());
        let (sink, events) = EventSink::channel();
        let count = Arc::new(AtomicU64::new(0));
        input.write_all(&payload).await.unwrap();
        input.shutdown().await.unwrap();
        // An early read already reported the first 100 bytes; the pump must continue that prefix.
        sink.data(1, false, &payload[..100]);
        let mut reader = reader;
        let mut early = [0u8; 100];
        reader.read_exact(&mut early).await.unwrap();
        pump(1, false, reader, writer, count.clone(), sink, 100, Activity::new()).await;
        let mut forwarded = Vec::new();
        output.read_to_end(&mut forwarded).await.unwrap();
        assert_eq!(forwarded, payload[100..]);
        assert_eq!(count.load(Ordering::Relaxed), (payload.len() - 100) as u64);
        let captured: Vec<u8> = events.try_iter()
            .filter(|event| event[4] == crate::events::DATA)
            .flat_map(|event| event[16..].to_vec())
            .collect();
        assert_eq!(captured, payload[..TCP_CAPTURE]);
    }
}
