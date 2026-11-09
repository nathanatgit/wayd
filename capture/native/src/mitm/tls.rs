// SPDX-License-Identifier: GPL-3.0-or-later
//! TLS interception of one connection.
//!
//! Order matters: read the app's ClientHello first (to learn the SNI), then connect upstream, and only if the
//! upstream TLS succeeds answer the app with a locally issued certificate. If anything before that fails the app has
//! seen nothing yet and the relay falls back to plain passthrough (replaying the recorded bytes), so interception can
//! never turn a working connection into a broken one. Only HTTP/1.1 is offered (ALPN) so captured traffic is parseable.

use std::collections::{HashMap, HashSet};
use std::io;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};
use std::time::Duration;

use rustls::pki_types::ServerName;
use rustls::server::Acceptor;
use rustls::{ClientConfig, RootCertStore, ServerConfig};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};
use tokio::net::TcpStream;
use tokio::time::timeout;
use tokio_rustls::{LazyConfigAcceptor, TlsConnector};

use super::ca::Authority;
use super::http1::{Capture, Message};
use crate::events::EventSink;

const UPSTREAM_TIMEOUT: Duration = Duration::from_secs(8);
const CLIENT_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const RECORD_CAP: usize = 64 * 1024;
const LEAF_CACHE_MAX: usize = 512;

pub enum Outcome {
    /// Interception ran to completion. (bytes up, bytes down)
    Done(u64, u64),
    /// The app refused our certificate (pinning or untrusted CA); the connection is gone, host is now remembered.
    ClientRejected,
    /// Nothing was sent to the app; replay these recorded bytes to a plain connection instead.
    Fallback(Vec<u8>),
}

/// Reads a prefix first (bytes the relay already consumed) and records everything read, so a fallback can replay it.
pub struct Recording<S> {
    inner: S,
    prefix: Vec<u8>,
    pos: usize,
    pub record: Vec<u8>,
}

impl<S> Recording<S> {
    pub fn new(inner: S, prefix: Vec<u8>) -> Self {
        Recording { inner, prefix, pos: 0, record: Vec::new() }
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for Recording<S> {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        if self.pos < self.prefix.len() {
            let n = (self.prefix.len() - self.pos).min(buf.remaining());
            let (a, b) = (self.pos, self.pos + n);
            buf.put_slice(&self.prefix[a..b]);
            self.pos = b;
            if self.record.len() < RECORD_CAP {
                let take = n.min(RECORD_CAP - self.record.len());
                let chunk = self.prefix[a..a + take].to_vec();
                self.record.extend_from_slice(&chunk);
            }
            return Poll::Ready(Ok(()));
        }
        let before = buf.filled().len();
        let r = Pin::new(&mut self.inner).poll_read(cx, buf);
        if let Poll::Ready(Ok(())) = &r {
            let new = buf.filled()[before..].to_vec();
            if self.record.len() < RECORD_CAP {
                let take = new.len().min(RECORD_CAP - self.record.len());
                self.record.extend_from_slice(&new[..take]);
            }
        }
        r
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for Recording<S> {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.inner).poll_write(cx, buf)
    }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_flush(cx)
    }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}

pub struct Mitm {
    authority: Authority,
    provider: Arc<rustls::crypto::CryptoProvider>,
    upstream: Arc<ClientConfig>,
    leaf_configs: Mutex<HashMap<String, Arc<ServerConfig>>>,
    excluded: Vec<String>,
    pinned: Mutex<HashSet<String>>,
}

impl Mitm {
    pub fn new(cert_pem: &str, key_pem: &str, excluded: Vec<String>) -> Result<Self, String> {
        Self::with_roots(cert_pem, key_pem, excluded, RootCertStore { roots: webpki_roots::TLS_SERVER_ROOTS.to_vec() })
    }

    /// Like [`Mitm::new`] with explicit trust anchors for the upstream side (used by tests).
    pub fn with_roots(cert_pem: &str, key_pem: &str, excluded: Vec<String>, roots: RootCertStore) -> Result<Self, String> {
        let authority = Authority::from_pem(cert_pem, key_pem)?;
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let mut upstream = ClientConfig::builder_with_provider(provider.clone())
            .with_safe_default_protocol_versions()
            .map_err(|e| e.to_string())?
            .with_root_certificates(roots)
            .with_no_client_auth();
        upstream.alpn_protocols = vec![b"http/1.1".to_vec()];
        Ok(Mitm {
            authority,
            provider,
            upstream: Arc::new(upstream),
            leaf_configs: Mutex::new(HashMap::new()),
            excluded: excluded.into_iter().map(|h| h.trim().trim_start_matches('.').to_ascii_lowercase()).filter(|h| !h.is_empty()).collect(),
            pinned: Mutex::new(HashSet::new()),
        })
    }

    /// True for hosts that must never be intercepted: the exclusion list (banking etc.) and hosts that rejected us.
    pub fn skip_host(&self, host: &str) -> bool {
        let h = host.trim_end_matches('.').to_ascii_lowercase();
        self.excluded.iter().any(|e| h == *e || h.ends_with(&format!(".{e}"))) || self.pinned.lock().unwrap().contains(&h)
    }

    fn server_config(&self, host: &str) -> Result<Arc<ServerConfig>, String> {
        if let Some(c) = self.leaf_configs.lock().unwrap().get(host) {
            return Ok(c.clone());
        }
        let (cert, key) = self.authority.issue_leaf(host)?;
        let mut cfg = ServerConfig::builder_with_provider(self.provider.clone())
            .with_safe_default_protocol_versions()
            .map_err(|e| e.to_string())?
            .with_no_client_auth()
            .with_single_cert(vec![cert], key)
            .map_err(|e| e.to_string())?;
        cfg.alpn_protocols = vec![b"http/1.1".to_vec()];
        let cfg = Arc::new(cfg);
        let mut cache = self.leaf_configs.lock().unwrap();
        if cache.len() >= LEAF_CACHE_MAX {
            cache.clear();
        }
        cache.insert(host.to_string(), cfg.clone());
        Ok(cfg)
    }

    pub async fn intercept<S>(&self, client: &mut Recording<S>, remote: TcpStream, id: u64, sink: &EventSink) -> Outcome
    where
        S: AsyncRead + AsyncWrite + Unpin + Send,
    {
        // 1. learn the SNI from the ClientHello (nothing is sent to the app yet)
        let start = match LazyConfigAcceptor::new(Acceptor::default(), &mut *client).await {
            Ok(s) => s,
            Err(_) => return Outcome::Fallback(std::mem::take(&mut client.record)),
        };
        let Some(sni) = start.client_hello().server_name().map(|s| s.to_ascii_lowercase()) else {
            drop(start);
            return Outcome::Fallback(std::mem::take(&mut client.record));
        };
        if self.skip_host(&sni) {
            drop(start);
            sink.tls_note(id, crate::events::TLS_SKIPPED);
            return Outcome::Fallback(std::mem::take(&mut client.record));
        }
        let Ok(name) = ServerName::try_from(sni.clone()) else {
            drop(start);
            return Outcome::Fallback(std::mem::take(&mut client.record));
        };

        // 2. real TLS to the server first: if this fails we have not touched the app's handshake
        let connector = TlsConnector::from(self.upstream.clone());
        let upstream = match timeout(UPSTREAM_TIMEOUT, connector.connect(name, remote)).await {
            Ok(Ok(u)) => u,
            _ => {
                drop(start);
                sink.tls_note(id, crate::events::TLS_UPSTREAM_FAILED);
                return Outcome::Fallback(std::mem::take(&mut client.record));
            }
        };

        // 3. answer the app with a locally issued certificate
        let Ok(cfg) = self.server_config(&sni) else {
            drop(start);
            return Outcome::Fallback(std::mem::take(&mut client.record));
        };
        let app = match timeout(CLIENT_HANDSHAKE_TIMEOUT, start.into_stream(cfg)).await {
            Ok(Ok(s)) => s,
            _ => {
                // the app (or its pinning) refused our certificate: remember the host so the next try is passthrough
                self.pinned.lock().unwrap().insert(sni.clone());
                sink.tls_note(id, crate::events::TLS_PINNED);
                return Outcome::ClientRejected;
            }
        };
        sink.tls_note(id, crate::events::TLS_INTERCEPTED);

        // 4. copy plaintext both ways while capturing HTTP/1.1
        let (up, down) = pump_plain(app, upstream, id, sink).await;
        Outcome::Done(up, down)
    }
}

/// Forwards bytes unchanged in both directions; copies of them feed the HTTP capture. One task handles both
/// directions so requests and responses can be paired without locks.
async fn pump_plain<A, U>(app: A, upstream: U, id: u64, sink: &EventSink) -> (u64, u64)
where
    A: AsyncRead + AsyncWrite + Unpin,
    U: AsyncRead + AsyncWrite + Unpin,
{
    let (mut ar, mut aw) = tokio::io::split(app);
    let (mut ur, mut uw) = tokio::io::split(upstream);
    let mut req = Capture::new(false);
    let mut resp = Capture::new(true);
    let mut ab = vec![0u8; 32 * 1024];
    let mut ub = vec![0u8; 32 * 1024];
    let (mut up, mut down) = (0u64, 0u64);
    let (mut a_open, mut u_open) = (true, true);
    let idle = Duration::from_secs(3600);
    let mut last_report = std::time::Instant::now();

    while a_open || u_open {
        tokio::select! {
            r = ar.read(&mut ab), if a_open => match r {
                Ok(n) if n > 0 => {
                    if uw.write_all(&ab[..n]).await.is_err() { break; }
                    up += n as u64;
                    if last_report.elapsed() >= Duration::from_secs(2) {
                        sink.progress(id, false, up);
                        sink.progress(id, true, down);
                        last_report = std::time::Instant::now();
                    }
                    req.feed(&ab[..n]);
                    for m in req.take() {
                        resp.push_request_context(m.method.clone(), m.url.clone());
                        emit(sink, id, &m);
                    }
                }
                _ => { a_open = false; let _ = uw.shutdown().await; }
            },
            r = ur.read(&mut ub), if u_open => match r {
                Ok(n) if n > 0 => {
                    if aw.write_all(&ub[..n]).await.is_err() { break; }
                    down += n as u64;
                    if last_report.elapsed() >= Duration::from_secs(2) {
                        sink.progress(id, false, up);
                        sink.progress(id, true, down);
                        last_report = std::time::Instant::now();
                    }
                    resp.feed(&ub[..n]);
                    for m in resp.take() { emit(sink, id, &m); }
                }
                _ => { u_open = false; let _ = aw.shutdown().await; }
            },
            _ = tokio::time::sleep(idle) => break,
        }
    }
    resp.finish();
    for m in resp.take() { emit(sink, id, &m); }
    req.finish();
    for m in req.take() { emit(sink, id, &m); }
    (up, down)
}

fn emit(sink: &EventSink, id: u64, m: &Message) {
    sink.message(id, m.response, m.status, &m.method, &m.url, &m.headers, m.body_total, m.truncated, &m.body);
}
