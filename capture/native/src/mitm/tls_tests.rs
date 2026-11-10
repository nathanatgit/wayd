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

/// Exercise the actual relay order: app TLS -> WAYD MITM -> Mihomo socket -> HTTPS.
/// An OPEN event receives the same explicit owner/host/consent answers Kotlin supplies.
#[cfg(target_os = "android")]
async fn routed_capture(profile: &[u8], destination: std::net::SocketAddr, host: &'static str,
    mitm: Mitm, ca: &CaPem, path: &'static str) -> Vec<(bool, String, Vec<u8>)> {
    use tokio::time::{timeout, Duration};
    let route = crate::routing::Routing::new(profile, "0:com.example.probe").expect("valid test route");
    let shared = crate::shared::Shared::with_routing(Some(route));
    shared.set_mitm(Some(Arc::new(mitm)));
    let (app, client) = tokio::io::duplex(128 * 1024);
    let (sink, rx) = EventSink::channel();
    let local = shared.clone();
    let relay = tokio::spawn(async move {
        crate::relay::tcp_flow(71, client, "127.0.0.1:20001".parse().unwrap(), destination, sink, local).await;
    });
    let collector = tokio::spawn(async move {
        let deadline = tokio::time::Instant::now() + Duration::from_secs(40);
        let mut events = Vec::new();
        loop {
            while let Ok(event) = rx.try_recv() {
                if event[4] == crate::events::OPEN {
                    shared.blocking.info(71, "0:com.example.probe", false);
                    shared.blocking.info(71, host, true);
                    shared.decide(71, true);
                }
                let closed = event[4] == crate::events::CLOSE;
                events.push(event);
                if closed { return events; }
            }
            assert!(tokio::time::Instant::now() < deadline, "relay event timeout");
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
    });
    let mut tls = timeout(Duration::from_secs(20), TlsConnector::from(client_config(&ca.cert_pem))
        .connect(ServerName::try_from(host).unwrap(), app)).await.expect("TLS timed out").expect("app TLS failed");
    let request = format!("GET {path} HTTP/1.1\r\nHost: {host}\r\nUser-Agent: WAYD-Routing-Test\r\nConnection: close\r\n\r\n");
    tls.write_all(request.as_bytes()).await.unwrap();
    let mut response = Vec::new();
    timeout(Duration::from_secs(20), tls.read_to_end(&mut response)).await.expect("HTTPS response timeout").unwrap();
    assert!(response.starts_with(b"HTTP/1.1 200"), "HTTPS did not return 200");
    let _ = tls.shutdown().await;
    drop(tls);
    timeout(Duration::from_secs(5), relay).await.unwrap().unwrap();
    let events = collector.await.unwrap();
    assert!(events.iter().any(|e| e[4] == 8 && e[13] == 2), "no observed Proxied outcome");
    assert!(!events.iter().any(|e| e[4] == 8 && e[13] == 1), "unexpected Direct outcome");
    assert!(events.iter().any(|e| e[4] == crate::events::DATA), "no captured TLS metadata bytes");
    assert!(events.iter().any(|e| e[4] == crate::events::TLS_NOTE && e[13] == crate::events::TLS_INTERCEPTED), "not decrypted");
    let (sender, receiver) = std::sync::mpsc::channel();
    for event in events { sender.send(event).unwrap(); }
    drop(sender);
    let messages = decode_messages(&receiver);
    assert!(messages.iter().any(|m| !m.0 && m.1 == format!("{host}{path}")), "no plaintext request");
    assert!(messages.iter().any(|m| m.0 && !m.2.is_empty()), "no plaintext response body");
    messages
}

#[cfg(target_os = "android")]
#[tokio::test]
async fn android_mihomo_routing_and_https_decryption_capture_both_messages() {
    let server_ca = generate_ca("Test HTTPS Server CA").unwrap();
    let local_ca = generate_ca("WAYD Combined Routing Test CA").unwrap();
    let destination = spawn_server(&server_ca).await;
    let proxy = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let profile = format!("proxies:\n - {{name: test, type: http, server: 127.0.0.1, port: {}}}\nrules: ['MATCH,test']", proxy.local_addr().unwrap().port());
    let proxy_task = tokio::spawn(async move {
        let (mut client, _) = proxy.accept().await.unwrap();
        let mut head = Vec::new(); let mut byte = [0u8; 1];
        while !head.ends_with(b"\r\n\r\n") { client.read_exact(&mut byte).await.unwrap(); head.push(byte[0]); assert!(head.len() < 2048); }
        assert!(head.starts_with(format!("CONNECT {destination} ").as_bytes()));
        let mut upstream = TcpStream::connect(destination).await.unwrap();
        client.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await.unwrap();
        let _ = tokio::io::copy_bidirectional(&mut client, &mut upstream).await;
    });
    let mitm = Mitm::with_roots(&local_ca.cert_pem, &local_ca.key_pem, vec![], roots_from(&server_ca.cert_pem)).unwrap();
    let messages = routed_capture(profile.as_bytes(), destination, "localhost", mitm, &local_ca, "/routed-test").await;
    assert!(messages.iter().any(|m| m.0 && m.2 == b"hello"));
    tokio::time::timeout(std::time::Duration::from_secs(3), proxy_task).await.unwrap().unwrap();
}

/// Explicit opt-in only: requests public robots.txt, with no browser cookies/account.
#[cfg(target_os = "android")]
#[tokio::test]
#[ignore = "requires an explicitly supplied private routing fixture and Google destination"]
async fn android_live_google_via_mihomo_is_decrypted() {
    let profile = std::fs::read(std::env::var("WAYD_LIVE_ROUTING_PROFILE").expect("routing fixture required")).unwrap();
    let destination = std::env::var("WAYD_LIVE_GOOGLE_DESTINATION").expect("Google IP:443 required").parse().unwrap();
    let ca = generate_ca("WAYD Ephemeral Google Test CA").unwrap();
    let mitm = Mitm::new(&ca.cert_pem, &ca.key_pem, vec![]).unwrap();
    let messages = routed_capture(&profile, destination, "www.google.com", mitm, &ca, "/robots.txt").await;
    assert!(messages.iter().any(|m| m.0 && m.2.windows(10).any(|w| w == b"User-agent")), "Google robots content missing");
}
