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

use ipstack::{IpStack, IpStackConfig, IpStackStream};
use jni::objects::{JByteArray, JObject};
use jni::sys::{jint, jlong};
use jni::EnvUnowned;
use tokio::runtime::Runtime;

use crate::events::EventSink;
use crate::{relay, tun};

struct Engine {
    runtime: Runtime,
    rx: Mutex<Receiver<Vec<u8>>>,
    /// Event that did not fit into the previous poll buffer.
    pending: Mutex<Option<Vec<u8>>>,
}

fn start_engine(tun_fd: i32, mtu: u16) -> std::io::Result<Engine> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2) // small on purpose: battery
        .enable_all()
        .thread_name("wayd-net")
        .build()?;
    let (sink, rx) = EventSink::channel();
    let _guard = runtime.enter(); // AsyncFd registration needs a runtime context
    let dev = tun::TunDevice::from_borrowed_fd(tun_fd)?;

    runtime.spawn(async move {
        let mut cfg = IpStackConfig::default();
        let _ = cfg.mtu(mtu);
        cfg.packet_information(false);
        cfg.udp_timeout(Duration::from_secs(30));
        let mut stack = IpStack::new(cfg, dev);
        let ids = Arc::new(AtomicU64::new(1));
        while let Ok(stream) = stack.accept().await {
            let sink = sink.clone();
            let id = ids.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            match stream {
                IpStackStream::Tcp(s) => {
                    tokio::spawn(relay::tcp(id, s, sink));
                }
                IpStackStream::Udp(s) => {
                    tokio::spawn(relay::udp(id, s, sink));
                }
                // ICMP and other transports are dropped: nothing to record for M1.
                _ => {}
            }
        }
    });

    drop(_guard);
    Ok(Engine { runtime, rx: Mutex::new(rx), pending: Mutex::new(None) })
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
pub fn native_start<'l>(_env: EnvUnowned<'l>, _this: JObject<'l>, tun_fd: jint, mtu: jint) -> jlong {
    match start_engine(tun_fd, mtu.clamp(1280, 65535) as u16) {
        Ok(e) => Box::into_raw(Box::new(e)) as jlong,
        Err(_) => 0,
    }
}

#[jni::jni_mangle("com.nathanhanapps.wayd.capture.NativeNet")]
pub fn native_stop<'l>(_env: EnvUnowned<'l>, _this: JObject<'l>, handle: jlong) {
    if handle != 0 {
        // SAFETY: handle came from `native_start` and Kotlin calls stop exactly once.
        let e = unsafe { Box::from_raw(handle as *mut Engine) };
        e.runtime.shutdown_background();
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
