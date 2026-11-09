// SPDX-License-Identifier: GPL-3.0-or-later
//! Per-flow relays between the userspace stack and real sockets.

use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use ipstack::{IpStackTcpStream, IpStackUdpStream};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpSocket, UdpSocket};
use tokio::time::timeout;

use crate::events::EventSink;

/// Max payload prefix reported per direction for TCP flows (enough for TLS ClientHello / HTTP head).
const TCP_CAPTURE: usize = 2048;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const TCP_IDLE: Duration = Duration::from_secs(300);
const UDP_IDLE: Duration = Duration::from_secs(30);
const DNS_PORT: u16 = 53;

pub async fn tcp(id: u64, client: IpStackTcpStream, sink: EventSink) {
    let src = client.local_addr();
    let dst = client.peer_addr();
    sink.open(id, 6, src, dst);

    let up = Arc::new(AtomicU64::new(0));
    let down = Arc::new(AtomicU64::new(0));

    if let Some(remote) = connect(dst).await {
        let (cr, cw) = tokio::io::split(client);
        let (rr, rw) = remote.into_split();
        let _ = tokio::join!(
            pump(id, false, cr, rw, up.clone(), sink.clone()),
            pump(id, true, rr, cw, down.clone(), sink.clone()),
        );
    }
    sink.close(id, up.load(Ordering::Relaxed), down.load(Ordering::Relaxed));
}

async fn connect(dst: SocketAddr) -> Option<tokio::net::TcpStream> {
    let sock = if dst.is_ipv4() { TcpSocket::new_v4() } else { TcpSocket::new_v6() }.ok()?;
    let _ = sock.set_nodelay(true);
    timeout(CONNECT_TIMEOUT, sock.connect(dst)).await.ok()?.ok()
}

/// Copies `from` to `to`, counting bytes and reporting the first `TCP_CAPTURE` bytes.
async fn pump<R, W>(id: u64, down: bool, mut from: R, mut to: W, counter: Arc<AtomicU64>, sink: EventSink)
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let mut buf = vec![0u8; 16 * 1024];
    let mut captured = 0usize;
    loop {
        let n = match timeout(TCP_IDLE, from.read(&mut buf)).await {
            Ok(Ok(n)) if n > 0 => n,
            _ => break,
        };
        if captured < TCP_CAPTURE {
            let take = n.min(TCP_CAPTURE - captured);
            sink.data(id, down, &buf[..take]);
            captured += take;
        }
        if to.write_all(&buf[..n]).await.is_err() {
            break;
        }
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

    let bind: SocketAddr = if dst.is_ipv4() { "0.0.0.0:0" } else { "[::]:0" }.parse().unwrap();
    if let Ok(sock) = UdpSocket::bind(bind).await {
        if sock.connect(dst).await.is_ok() {
            let sock = Arc::new(sock);
            let (mut cr, mut cw) = tokio::io::split(client);

            let up_task = {
                let (sock, up, sink) = (sock.clone(), up.clone(), sink.clone());
                async move {
                    let mut buf = vec![0u8; 65535];
                    let mut first = true;
                    while let Ok(Ok(n)) = timeout(UDP_IDLE, cr.read(&mut buf)).await {
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
                let (sock, down, sink) = (sock.clone(), down.clone(), sink.clone());
                async move {
                    let mut buf = vec![0u8; 65535];
                    let mut first = true;
                    while let Ok(Ok(n)) = timeout(UDP_IDLE, sock.recv(&mut buf)).await {
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
            // A UDP "flow" ends when either direction goes idle or fails.
            tokio::select! { _ = up_task => {}, _ = down_task => {} }
        }
    }
    sink.close(id, up.load(Ordering::Relaxed), down.load(Ordering::Relaxed));
}
