// SPDX-License-Identifier: GPL-3.0-or-later
//! End-to-end tests of the interception engine over real TLS on loopback.

use std::sync::Arc;

use rustls::pki_types::ServerName;
use rustls::{ClientConfig, RootCertStore, ServerConfig};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio_rustls::{TlsAcceptor, TlsConnector};

use super::ca::{generate_ca, Authority, CaPem};
use super::tls::{Mitm, Outcome, Recording};
use crate::events::EventSink;

fn roots_from(pem: &str) -> RootCertStore {
    let mut roots = RootCertStore::empty();
    let mut r = std::io::BufReader::new(pem.as_bytes());
    for c in rustls_pemfile::certs(&mut r) {
        roots.add(c.unwrap()).unwrap();
    }
    roots
}

/// A tiny HTTPS/1.1 server with its own CA, answering every request with `hello`.
async fn spawn_server(server_ca: &CaPem) -> std::net::SocketAddr {
    let auth = Authority::from_pem(&server_ca.cert_pem, &server_ca.key_pem).unwrap();
    let (cert, key) = auth.issue_leaf("localhost").unwrap();
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut cfg = ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(vec![cert], key)
        .unwrap();
    cfg.alpn_protocols = vec![b"http/1.1".to_vec()];
    let acceptor = TlsAcceptor::from(Arc::new(cfg));
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        while let Ok((tcp, _)) = listener.accept().await {
            let acceptor = acceptor.clone();
            tokio::spawn(async move {
                let Ok(mut tls) = acceptor.accept(tcp).await else { return };
                let mut buf = vec![0u8; 4096];
                let mut got = Vec::new();
                while !got.windows(4).any(|w| w == b"\r\n\r\n") {
                    match tls.read(&mut buf).await {
                        Ok(n) if n > 0 => got.extend_from_slice(&buf[..n]),
                        _ => return,
                    }
                }
                let _ = tls
                    .write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello")
                    .await;
                let _ = tls.shutdown().await;
            });
        }
    });
    addr
}

fn client_config(trust: &str) -> Arc<ClientConfig> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut cfg = ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_root_certificates(roots_from(trust))
        .with_no_client_auth();
    cfg.alpn_protocols = vec![b"http/1.1".to_vec()];
    Arc::new(cfg)
}

/// Decodes MESSAGE events (see events.rs) into (is_response, url, body).
fn decode_messages(rx: &std::sync::mpsc::Receiver<Vec<u8>>) -> Vec<(bool, String, Vec<u8>)> {
    let mut out = Vec::new();
    while let Ok(ev) = rx.try_recv() {
        if ev[4] != crate::events::MESSAGE {
            continue;
        }
        let b = &ev[13..];
        let response = b[0] == 1;
        let mlen = b[3] as usize;
        let ulen = u16::from_be_bytes([b[4 + mlen], b[5 + mlen]]) as usize;
        let url = String::from_utf8_lossy(&b[6 + mlen..6 + mlen + ulen]).to_string();
        let hpos = 6 + mlen + ulen;
        let hlen = u32::from_be_bytes([b[hpos], b[hpos + 1], b[hpos + 2], b[hpos + 3]]) as usize;
        let bpos = hpos + 4 + hlen + 8 + 1;
        let blen = u32::from_be_bytes([b[bpos], b[bpos + 1], b[bpos + 2], b[bpos + 3]]) as usize;
        out.push((response, url, b[bpos + 4..bpos + 4 + blen].to_vec()));
    }
    out
}

#[tokio::test]
async fn app_trusting_only_our_ca_gets_a_working_connection_and_we_capture_both_messages() {
    let server_ca = generate_ca("Test Server CA").unwrap();
    let wayd_ca = generate_ca("WAYD Local CA - test").unwrap();
    let addr = spawn_server(&server_ca).await;
    let mitm = Mitm::with_roots(&wayd_ca.cert_pem, &wayd_ca.key_pem, vec![], roots_from(&server_ca.cert_pem)).unwrap();

    let (app_end, wayd_end) = tokio::io::duplex(64 * 1024);
    let (sink, rx) = EventSink::channel();
    let remote = TcpStream::connect(addr).await.unwrap();
    let task = tokio::spawn(async move {
        let mut rec = Recording::new(wayd_end, Vec::new());
        let out = mitm.intercept(&mut rec, remote, 7, &sink).await;
        (matches!(out, Outcome::Done(_, _)), sink)
    });

    // The "app" trusts ONLY the WAYD CA, like an app on a phone where the user installed it.
    let connector = TlsConnector::from(client_config(&wayd_ca.cert_pem));
    let mut tls = connector
        .connect(ServerName::try_from("localhost").unwrap(), app_end)
        .await
        .expect("app must accept the local certificate");
    tls.write_all(b"GET /secret?id=42 HTTP/1.1\r\nHost: localhost\r\nUser-Agent: test\r\n\r\n").await.unwrap();
    let mut resp = Vec::new();
    let _ = tls.read_to_end(&mut resp).await;
    let text = String::from_utf8_lossy(&resp).to_string();
    assert!(text.starts_with("HTTP/1.1 200 OK"), "{text}");
    assert!(text.ends_with("hello"));
    // a real app closes its side when it is done; the engine waits for that before finishing
    let _ = tls.shutdown().await;
    drop(tls);

    let (done, _sink) = task.await.unwrap();
    assert!(done, "interception should complete");
    let msgs = decode_messages(&rx);
    assert_eq!(msgs.len(), 2, "request and response captured");
    assert!(!msgs[0].0 && msgs[0].1 == "localhost/secret?id=42");
    assert!(msgs[1].0 && msgs[1].1 == "localhost/secret?id=42" && msgs[1].2 == b"hello");
}

#[tokio::test]
async fn app_that_rejects_our_certificate_marks_the_host_and_next_time_is_skipped() {
    let server_ca = generate_ca("Test Server CA").unwrap();
    let wayd_ca = generate_ca("WAYD Local CA - test").unwrap();
    let addr = spawn_server(&server_ca).await;
    let mitm = Arc::new(Mitm::with_roots(&wayd_ca.cert_pem, &wayd_ca.key_pem, vec![], roots_from(&server_ca.cert_pem)).unwrap());

    let (app_end, wayd_end) = tokio::io::duplex(64 * 1024);
    let (sink, _rx) = EventSink::channel();
    let remote = TcpStream::connect(addr).await.unwrap();
    let m2 = mitm.clone();
    let task = tokio::spawn(async move {
        let mut rec = Recording::new(wayd_end, Vec::new());
        matches!(m2.intercept(&mut rec, remote, 8, &sink).await, Outcome::ClientRejected)
    });
    // a pinned app: trusts a DIFFERENT CA, so it refuses our certificate
    let other = generate_ca("Some Other CA").unwrap();
    let connector = TlsConnector::from(client_config(&other.cert_pem));
    assert!(connector.connect(ServerName::try_from("localhost").unwrap(), app_end).await.is_err());
    assert!(task.await.unwrap(), "engine must report ClientRejected");
    assert!(mitm.skip_host("localhost"), "rejected host must be remembered");
}

#[tokio::test]
async fn non_tls_traffic_falls_back_with_the_recorded_bytes() {
    let wayd_ca = generate_ca("WAYD Local CA - test").unwrap();
    let mitm = Mitm::new(&wayd_ca.cert_pem, &wayd_ca.key_pem, vec![]).unwrap();
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let (mut app_end, wayd_end) = tokio::io::duplex(4096);
    let (sink, _rx) = EventSink::channel();
    let remote = TcpStream::connect(addr).await.unwrap();
    app_end.write_all(b"GET / HTTP/1.1\r\nHost: plain\r\n\r\n").await.unwrap();
    let mut rec = Recording::new(wayd_end, Vec::new());
    match mitm.intercept(&mut rec, remote, 9, &sink).await {
        Outcome::Fallback(bytes) => assert!(bytes.starts_with(b"GET / HTTP/1.1")),
        _ => panic!("expected fallback for non-TLS bytes"),
    }
}

#[test]
fn exclusion_list_matches_on_label_boundaries() {
    let ca = generate_ca("t").unwrap();
    let m = Mitm::new(&ca.cert_pem, &ca.key_pem, vec!["alipay.com".into(), ".icbc.com.cn".into()]).unwrap();
    assert!(m.skip_host("alipay.com"));
    assert!(m.skip_host("mobilegw.alipay.com"));
    assert!(m.skip_host("WWW.ICBC.COM.CN."));
    assert!(!m.skip_host("notalipay.com"));
    assert!(!m.skip_host("alipay.com.evil.example"));
}
