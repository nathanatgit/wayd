// SPDX-License-Identifier: GPL-3.0-or-later
//! Engine: tun fd -> ipstack -> relays, plus the JNI surface.
//!
//! Reads raw IP packets from the VPN tun fd, terminates TCP/UDP with `ipstack`, relays each flow to
//! the real destination over normal sockets (the app itself is excluded from the VPN with
//! `addDisallowedApplication`, so these sockets bypass the tunnel) and reports flow events to Kotlin
//! through a polled binary queue. No payload is stored here; only bounded prefixes are reported.


use std::sync::atomic::AtomicU64;
use std::sync::mpsc::{Receiver, RecvTimeoutError};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use ipstack::{IpStack, IpStackConfig, IpStackStream, TcpConfig};
use jni::objects::{JByteArray, JObject};
use jni::sys::{jint, jlong};
use jni::EnvUnowned;
use tokio::runtime::Runtime;
use tokio::sync::oneshot;
use tokio::task::{JoinHandle, JoinSet};

use crate::events::EventSink;
use crate::shared::Shared;
use crate::{relay, tun};

struct Engine {
    shared: Arc<Shared>,
    runtime: Runtime,
    stop: oneshot::Sender<()>,
    task: JoinHandle<()>,
    rx: Mutex<Receiver<Vec<u8>>>,
    /// Event that did not fit into the previous poll buffer.
    pending: Mutex<Option<Vec<u8>>>,
}

#[cfg(test)]
fn start_engine(tun_fd: i32, mtu: u16, policy: &str) -> std::io::Result<Engine> {
    start_engine_routed(tun_fd, mtu, policy, None)
}

fn start_engine_routed(tun_fd: i32, mtu: u16, policy: &str, routing: Option<Arc<crate::routing::Routing>>) -> std::io::Result<Engine> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2) // small on purpose: battery
        .enable_all()
        .thread_name("wayd-net")
        .build()?;
    let (sink, rx) = EventSink::channel();
    let shared = Shared::with_routing(routing);
    if !shared.blocking.install(policy) {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, "invalid policy"));
    }
    let shared_for_tasks = shared.clone();
    let _guard = runtime.enter(); // AsyncFd registration needs a runtime context
    let dev = tun::TunDevice::from_borrowed_fd(tun_fd)?;

    let (stop, mut stopped) = oneshot::channel();
    let task = runtime.spawn(async move {
        let mut cfg = IpStackConfig::default();
        let _ = cfg.mtu(mtu);
        cfg.packet_information(false);
        // Defaults are tuned for short-lived proxy sessions: a 60 s TCP idle limit resets pooled/push
        // connections and 16 KB windows throttle streaming. Relay-level idle limits are shorter than these.
        let mut tcp = TcpConfig::default();
        tcp.timeout = Duration::from_secs(7200);
        tcp.max_unacked_bytes = 256 * 1024;
        tcp.read_buffer_size = 64 * 1024;
        cfg.with_tcp_config(tcp);
        cfg.udp_timeout(Duration::from_secs(600));
        let mut stack = IpStack::new(cfg, dev);
        let ids = Arc::new(AtomicU64::new(1));
        let mut relays = JoinSet::new();
        loop {
            let stream = tokio::select! {
                biased;
                _ = &mut stopped => break,
                Some(_) = relays.join_next(), if !relays.is_empty() => continue,
                stream = stack.accept() => match stream { Ok(stream) => stream, Err(_) => break },
            };
            let sink = sink.clone();
            let shared = shared_for_tasks.clone();
            let id = ids.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            match stream {
                IpStackStream::Tcp(s) => {
                    relays.spawn(relay::tcp(id, s, sink, shared));
                }
                IpStackStream::Udp(s) => {
                    relays.spawn(relay::udp(id, s, sink, shared));
                }
                // ICMP and other transports are dropped: nothing to record for M1.
                _ => {}
            }
        }
        // IpStackTcpStream::drop synchronously waits for its protocol task. Keep the
        // runtime alive until every relay destructor finishes, then release the stack/TUN.
        relays.shutdown().await;
        drop(stack);
    });

    drop(_guard);
    Ok(Engine { shared, runtime, stop, task, rx: Mutex::new(rx), pending: Mutex::new(None) })
}

fn stop_engine(engine: Engine) {
    let Engine { runtime, stop, task, .. } = engine;
    let _ = stop.send(());
    // Called from Kotlin's polling thread, outside this Tokio runtime. Bound shutdown
    // even if a dependency fails, without stopping protocol tasks before relay cleanup.
    runtime.block_on(async { let _ = tokio::time::timeout(Duration::from_secs(2), task).await; });
    runtime.shutdown_timeout(Duration::from_secs(1));
}

/// Fills `out` with as many whole events as fit. Returns bytes written, 0 on timeout, -1 when stopped.
fn poll_engine(e: &Engine, out: &mut [u8], timeout_ms: u64) -> i32 {
    let rx = e.rx.lock().unwrap();
    let mut pending = e.pending.lock().unwrap();
    let mut n = 0usize;
    loop {
        let ev = match pending.take() {
            Some(ev) => ev,
            None => {
                let wait = if n == 0 { Duration::from_millis(timeout_ms) } else { Duration::ZERO };
                match rx.recv_timeout(wait) {
                    Ok(ev) => ev,
                    Err(RecvTimeoutError::Timeout) => return n as i32,
                    Err(RecvTimeoutError::Disconnected) => return if n == 0 { -1 } else { n as i32 },
                }
            }
        };
        if n + ev.len() > out.len() {
            if n == 0 {
                return 0; // event larger than the buffer: drop it rather than wedge the queue
            }
            *pending = Some(ev);
            return n as i32;
        }
        out[n..n + ev.len()].copy_from_slice(&ev);
        n += ev.len();
    }
}

// ---- JNI (class com.nathanhanapps.wayd.capture.NativeNet) ----

#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_start<'l>(mut env: EnvUnowned<'l>, _this: JObject<'l>, tun_fd: jint, mtu: jint, policy: jni::objects::JString<'l>, profile: JByteArray<'l>, owners: jni::objects::JString<'l>) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
        let policy = policy.try_to_string(env)?;
        if profile.len(env)? > 48 * 1024 * 1024 { return Ok(0); }
        let bytes = env.convert_byte_array(&profile)?;
        let routing = if bytes.is_empty() { None } else {
            let owners = owners.try_to_string(env)?;
            match crate::routing::Routing::new(&bytes, &owners) { Ok(r) => Some(r), Err(_) => return Ok(0) }
        };
        Ok(match start_engine_routed(tun_fd, mtu.clamp(1280, 65535) as u16, &policy, routing) {
            Ok(e) => Box::into_raw(Box::new(e)) as jlong,
            Err(_) => 0,
        })
    }).resolve::<jni::errors::LogErrorAndDefault>()
}

#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_validate_routing<'l>(mut env: EnvUnowned<'l>, _this: JObject<'l>, profile: JByteArray<'l>) -> jni::sys::jboolean {
    env.with_env(|env| -> jni::errors::Result<bool> {
        if profile.len(env)? > 48 * 1024 * 1024 { return Ok(false); }
        let bytes = env.convert_byte_array(&profile)?;
        Ok(crate::routing::Routing::new(&bytes, "0:com.example.validation").is_ok())
    }).resolve::<jni::errors::LogErrorAndDefault>()
}

#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_set_policy<'l>(mut env: EnvUnowned<'l>, _this: JObject<'l>, handle: jlong, policy: jni::objects::JString<'l>) -> jni::sys::jboolean {
    if handle == 0 { return false; }
    // SAFETY: BlockingRules serializes all bridge calls with engine destruction.
    let engine = unsafe { &*(handle as *const Engine) };
    env.with_env(|env| -> jni::errors::Result<bool> {
        Ok(engine.shared.blocking.install(&policy.try_to_string(env)?))
    }).resolve::<jni::errors::LogErrorAndDefault>()
}

#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_flow_info<'l>(mut env: EnvUnowned<'l>, _this: JObject<'l>, handle: jlong, flow: jlong, value: jni::objects::JString<'l>, host: jni::sys::jboolean) {
    if handle == 0 { return; }
    // SAFETY: BlockingRules serializes all bridge calls with engine destruction.
    let engine = unsafe { &*(handle as *const Engine) };
    let _ = env.with_env(|env| -> jni::errors::Result<()> {
        engine.shared.blocking.info(flow as u64, &value.try_to_string(env)?, host);
        Ok(())
    }).resolve::<jni::errors::LogErrorAndDefault>();
}

#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_stop<'l>(_env: EnvUnowned<'l>, _this: JObject<'l>, handle: jlong) {
    if handle != 0 {
        // SAFETY: handle came from `native_start` and Kotlin calls stop exactly once.
        let e = unsafe { Box::from_raw(handle as *mut Engine) };
        stop_engine(*e);
    }
}

#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_poll<'l>(
    mut env: EnvUnowned<'l>,
    _this: JObject<'l>,
    handle: jlong,
    buf: JByteArray<'l>,
    timeout_ms: jint,
) -> jint {
    if handle == 0 {
        return -1;
    }
    // SAFETY: the engine outlives polling; Kotlin stops polling before calling `native_stop`.
    let engine = unsafe { &*(handle as *const Engine) };
    env.with_env(|env| -> jni::errors::Result<jint> {
        let cap = buf.len(env)?;
        let mut tmp = vec![0u8; cap];
        let n = poll_engine(engine, &mut tmp, timeout_ms.max(0) as u64);
        if n > 0 {
            let signed: Vec<i8> = tmp[..n as usize].iter().map(|b| *b as i8).collect();
            buf.set_region(env, 0, &signed)?;
        }
        Ok(n)
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

// ---- MITM controls (research flavor; stubs in the standard build) ----

#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_policy_version<'l>(_env: EnvUnowned<'l>, _this: JObject<'l>) -> jint { 2 }

#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_has_mitm<'l>(_env: EnvUnowned<'l>, _this: JObject<'l>) -> jni::sys::jboolean {
    cfg!(feature = "mitm")
}

/// Generates a CA. Returns `certPem` + U+001F + `keyPem`, or an empty string on failure / in the standard build.
#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_generate_ca<'l>(mut env: EnvUnowned<'l>, _this: JObject<'l>, cn: jni::objects::JString<'l>) -> jni::objects::JString<'l> {
    env.with_env(|env| -> jni::errors::Result<jni::objects::JString<'l>> {
        #[cfg(feature = "mitm")]
        {
            let name = cn.try_to_string(env)?;
            let out = match crate::mitm::ca::generate_ca(&name) {
                Ok(ca) => format!("{}\u{1F}{}", ca.cert_pem, ca.key_pem),
                Err(_) => String::new(),
            };
            return jni::objects::JString::from_str(env, &out);
        }
        #[cfg(not(feature = "mitm"))]
        {
            let _ = cn;
            jni::objects::JString::from_str(env, "")
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// OpenSSL `subject_hash_old` of a certificate PEM (the system-store file name), or "".
#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_subject_hash<'l>(mut env: EnvUnowned<'l>, _this: JObject<'l>, pem: jni::objects::JString<'l>) -> jni::objects::JString<'l> {
    env.with_env(|env| -> jni::errors::Result<jni::objects::JString<'l>> {
        #[cfg(feature = "mitm")]
        {
            let p = pem.try_to_string(env)?;
            return jni::objects::JString::from_str(env, &crate::mitm::ca::subject_hash_old(&p).unwrap_or_default());
        }
        #[cfg(not(feature = "mitm"))]
        {
            let _ = pem;
            jni::objects::JString::from_str(env, "")
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Turns interception on for this engine. `excluded` is a comma-separated list of host suffixes never intercepted.
#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_configure_mitm<'l>(
    mut env: EnvUnowned<'l>,
    _this: JObject<'l>,
    handle: jlong,
    cert: jni::objects::JString<'l>,
    key: jni::objects::JString<'l>,
    excluded: jni::objects::JString<'l>,
) -> jni::sys::jboolean {
    if handle == 0 {
        return false;
    }
    // SAFETY: the engine outlives control calls; Kotlin stops using the handle before `native_stop`.
    let engine = unsafe { &*(handle as *const Engine) };
    env.with_env(|env| -> jni::errors::Result<jni::sys::jboolean> {
        #[cfg(feature = "mitm")]
        {
            let (c, k, x) = (cert.try_to_string(env)?, key.try_to_string(env)?, excluded.try_to_string(env)?);
            let list = x.split(',').map(|s| s.to_string()).collect();
            return Ok(match crate::mitm::tls::Mitm::new(&c, &k, list) {
                Ok(m) => {
                    engine.shared.set_mitm(Some(Arc::new(m)));
                    true
                }
                Err(_) => false,
            });
        }
        #[cfg(not(feature = "mitm"))]
        {
            let _ = (engine, cert, key, excluded, env);
            Ok(false)
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_disable_mitm<'l>(_env: EnvUnowned<'l>, _this: JObject<'l>, handle: jlong) {
    if handle == 0 {
        return;
    }
    // SAFETY: see `native_configure_mitm`.
    let engine = unsafe { &*(handle as *const Engine) };
    #[cfg(feature = "mitm")]
    engine.shared.set_mitm(None);
    #[cfg(not(feature = "mitm"))]
    let _ = engine;
}

/// Kotlin's answer to "may this flow be intercepted?".
#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_decide<'l>(_env: EnvUnowned<'l>, _this: JObject<'l>, handle: jlong, flow_id: jlong, intercept: jni::sys::jboolean) {
    if handle == 0 {
        return;
    }
    // SAFETY: see `native_configure_mitm`.
    let engine = unsafe { &*(handle as *const Engine) };
    engine.shared.decide(flow_id as u64, intercept);
}

#[cfg(all(test, any(target_os = "linux", target_os = "android")))]
mod lifecycle_tests {
    use super::*;
    use std::os::fd::AsRawFd;
    use std::os::unix::net::UnixDatagram;

    fn checksum(bytes: &[u8]) -> u16 {
        let mut sum: u32 = bytes.chunks(2).map(|c| {
            u16::from_be_bytes([c[0], *c.get(1).unwrap_or(&0)]) as u32
        }).sum();
        while sum > 0xffff { sum = (sum & 0xffff) + (sum >> 16); }
        !(sum as u16)
    }

    // Synthetic SYNs terminate inside IpStack. The app rule keeps every relay from dialing.
    fn syn(port: u16) -> Vec<u8> {
        let mut p = vec![0u8; 40];
        p[0] = 0x45;
        p[2..4].copy_from_slice(&40u16.to_be_bytes());
        p[8] = 64; p[9] = 6;
        p[12..16].copy_from_slice(&[10, 111, 222, 2]);
        p[16..20].copy_from_slice(&[192, 0, 2, 1]);
        p[20..22].copy_from_slice(&port.to_be_bytes());
        p[22..24].copy_from_slice(&80u16.to_be_bytes());
        p[27] = 1; p[32] = 0x50; p[33] = 2;
        p[34..36].copy_from_slice(&4096u16.to_be_bytes());
        let ip_sum = checksum(&p[..20]);
        p[10..12].copy_from_slice(&ip_sum.to_be_bytes());
        let mut pseudo = p[12..20].to_vec();
        pseudo.extend_from_slice(&[0, 6, 0, 20]);
        pseudo.extend_from_slice(&p[20..]);
        p[36..38].copy_from_slice(&checksum(&pseudo).to_be_bytes());
        p
    }

    #[test]
    fn stopping_blocked_tcp_releases_tun_duplicate() {
        for iteration in 0..12 {
            let (device, peer) = UnixDatagram::pair().unwrap();
            let target = std::fs::read_link(format!("/proc/self/fd/{}", device.as_raw_fd())).unwrap();
            let engine = start_engine(device.as_raw_fd(), 1500,
                "WAYD1\t1\n1\t0:org.example.probe\tAPP\t*").unwrap();
            for port in 12000..12032 { peer.send(&syn(port)).unwrap(); }
            let deadline = std::time::Instant::now() + Duration::from_millis(500);
            let mut opened = 0;
            while opened < 32 && std::time::Instant::now() < deadline {
                opened += engine.rx.lock().unwrap().try_iter().filter(|e| e[4] == crate::events::OPEN).count();
                std::thread::sleep(Duration::from_millis(1));
            }
            assert_eq!(opened, 32, "fixture must create real TCP streams");
            if iteration % 3 != 0 {
                for id in 1..=32 { engine.shared.blocking.info(id, "0:org.example.probe", false); }
                std::thread::sleep(Duration::from_millis(iteration % 3));
            }
            stop_engine(engine);
            std::thread::sleep(Duration::from_millis(300));
            let copies = std::fs::read_dir("/proc/self/fd").unwrap().flatten()
                .filter(|e| std::fs::read_link(e.path()).ok().as_ref() == Some(&target)).count();
            assert_eq!(copies, 1, "native TUN duplicate survived Stop");
            let workers = std::fs::read_dir("/proc/self/task").unwrap().flatten()
                .filter(|e| std::fs::read_to_string(e.path().join("comm"))
                    .is_ok_and(|name| name.trim() == "wayd-net")).count();
            assert_eq!(workers, 0, "native runtime worker survived Stop");
        }
    }
}

#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_routing_command<'l>(mut env: EnvUnowned<'l>, _this: JObject<'l>, profile: JByteArray<'l>, request: JByteArray<'l>) -> JByteArray<'l> {
    env.with_env(|env| -> jni::errors::Result<JByteArray<'l>> {
        if profile.len(env)? > 48*1024*1024 || request.len(env)? > 48*1024*1024 { return env.byte_array_from_slice(&[]); }
        let bytes=env.convert_byte_array(&profile)?;
        let request=env.convert_byte_array(&request)?;
        env.byte_array_from_slice(&crate::routing::command(&bytes,&request))
    }).resolve::<jni::errors::LogErrorAndDefault>()
}
