// SPDX-License-Identifier: GPL-3.0-or-later
//! Per-flow relays between the userspace stack and real sockets.
//!
//! Idle handling is per *flow*, not per direction: a long download with no upload (or a server-push UDP
//! stream) must not be torn down just because one direction is quiet.

use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use ipstack::{IpStackTcpStream, IpStackUdpStream};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpSocket, UdpSocket};
use tokio::time::timeout;

use crate::events::EventSink;

/// Max payload prefix reported per direction for TCP flows (enough for TLS ClientHello / HTTP head).
const TCP_CAPTURE: usize = 2048;
/// Short on purpose: the client already saw the handshake complete, so a slow failure stalls the app.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(6);
/// Apps keep pooled / push connections idle for a long time (the userspace stack allows 2 h).
pub const TCP_IDLE: Duration = Duration::from_secs(3600);
/// QUIC and other UDP flows must keep their source port across quiet periods or the server loses them.
pub const UDP_IDLE: Duration = Duration::from_secs(300);
const DNS_IDLE: Duration = Duration::from_secs(15);
const DNS_PORT: u16 = 53;

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

pub async fn tcp(id: u64, mut client: IpStackTcpStream, sink: EventSink) {
    let src = client.local_addr();
    let dst = client.peer_addr();
    sink.open(id, 6, src, dst);

    let up = Arc::new(AtomicU64::new(0));
    let down = Arc::new(AtomicU64::new(0));

    // Read the client's first bytes while the outbound connect is in flight, so the TLS SNI / HTTP Host
    // of connections that fail or stall is still recorded. `read` is cancel-safe, so if the connect wins
    // the race nothing is lost.
    let mut first = vec![0u8; 16 * 1024];
    let mut early: Option<usize> = None;
    let connect_fut = connect(dst);
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
        sink.data(id, false, &first[..take]);
        captured0 = take;
    }

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
            let (rr, rw) = remote.into_split();
            let _ = tokio::join!(
                pump(id, false, cr, rw, up.clone(), sink.clone(), captured0, act.clone()),
                pump(id, true, rr, cw, down.clone(), sink.clone(), 0, act.clone()),
            );
        }
    }
    sink.close(id, up.load(Ordering::Relaxed), down.load(Ordering::Relaxed));
}

async fn connect(dst: SocketAddr) -> Option<tokio::net::TcpStream> {
    let sock = if dst.is_ipv4() { TcpSocket::new_v4() } else { TcpSocket::new_v6() }.ok()?;
    let _ = sock.set_nodelay(true);
    timeout(CONNECT_TIMEOUT, sock.connect(dst)).await.ok()?.ok()
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
        counter.fetch_add(n as u64, Ordering::Relaxed);
    }
    let _ = to.shutdown().await;
}

pub async fn udp(id: u64, client: IpStackUdpStream, sink: EventSink) {
    let src = client.local_addr();
    let dst = client.peer_addr();
    sink.open(id, 17, src, dst);

    let up = Arc::new(AtomicU64::new(0));
    let down = Arc::new(AtomicU64::new(0));
    let is_dns = dst.port() == DNS_PORT;
    let limit = if is_dns { DNS_IDLE } else { UDP_IDLE };

    let bind: SocketAddr = if dst.is_ipv4() { "0.0.0.0:0" } else { "[::]:0" }.parse().unwrap();
    if let Ok(sock) = UdpSocket::bind(bind).await {
        if sock.connect(dst).await.is_ok() {
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
    }
    sink.close(id, up.load(Ordering::Relaxed), down.load(Ordering::Relaxed));
}
