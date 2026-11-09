// SPDX-License-Identifier: GPL-3.0-or-later
//! Binary event encoding. All integers big-endian. Each event: `u32 total_len | u8 type | u64 flow_id | body`.
//!
//! * `OPEN  (1)`: `u8 proto(6|17) | u8 ip_version | u16 src_port | u16 dst_port | [16] src | [16] dst`
//!   (IPv4 addresses are left-aligned in the 16 bytes; use `ip_version` to know how many are valid)
//! * `DATA  (2)`: `u8 dir(0=up,1=down) | u16 len | bytes` — bounded prefix of the flow payload
//! * `CLOSE (3)`: `u64 bytes_up | u64 bytes_down`
//! * `MESSAGE (4)`: `u8 response | u16 status | u8 method_len | method | u16 url_len | url | u32 headers_len | headers |
//!   u64 body_total | u8 truncated | u32 body_len | body` (decrypted HTTP/1.1 message, research flavor only)
//! * `PROGRESS (6)`: `u8 dir(0=up,1=down) | u64 total_bytes_so_far` — sent every few seconds while a flow moves data, so
//!   flows still open when recording stops keep their byte counts
//! * `TLS_NOTE (5)`: `u8 kind` (1 intercepted, 2 app rejected our certificate, 3 upstream TLS failed, 4 skipped host)

use std::net::{IpAddr, SocketAddr};
use std::sync::mpsc::{channel, Receiver, Sender};

pub const OPEN: u8 = 1;
pub const DATA: u8 = 2;
pub const CLOSE: u8 = 3;
pub const MESSAGE: u8 = 4;
pub const TLS_NOTE: u8 = 5;
pub const PROGRESS: u8 = 6;

/// `TLS_NOTE` kinds.
pub const TLS_INTERCEPTED: u8 = 1;
pub const TLS_PINNED: u8 = 2;
pub const TLS_UPSTREAM_FAILED: u8 = 3;
pub const TLS_SKIPPED: u8 = 4;

#[derive(Clone)]
pub struct EventSink(Sender<Vec<u8>>);

impl EventSink {
    pub fn channel() -> (EventSink, Receiver<Vec<u8>>) {
        let (tx, rx) = channel();
        (EventSink(tx), rx)
    }

    fn send(&self, ty: u8, id: u64, body: &[u8]) {
        let mut ev = Vec::with_capacity(13 + body.len());
        ev.extend_from_slice(&((13 + body.len()) as u32).to_be_bytes());
        ev.push(ty);
        ev.extend_from_slice(&id.to_be_bytes());
        ev.extend_from_slice(body);
        let _ = self.0.send(ev);
    }

    pub fn open(&self, id: u64, proto: u8, src: SocketAddr, dst: SocketAddr) {
        let mut b = Vec::with_capacity(38);
        b.push(proto);
        b.push(if dst.is_ipv4() { 4 } else { 6 });
        b.extend_from_slice(&src.port().to_be_bytes());
        b.extend_from_slice(&dst.port().to_be_bytes());
        b.extend_from_slice(&addr16(src.ip()));
        b.extend_from_slice(&addr16(dst.ip()));
        self.send(OPEN, id, &b);
    }

    pub fn data(&self, id: u64, down: bool, bytes: &[u8]) {
        let n = bytes.len().min(u16::MAX as usize);
        let mut b = Vec::with_capacity(3 + n);
        b.push(down as u8);
        b.extend_from_slice(&(n as u16).to_be_bytes());
        b.extend_from_slice(&bytes[..n]);
        self.send(DATA, id, &b);
    }

    #[allow(clippy::too_many_arguments)]
    pub fn message(&self, id: u64, response: bool, status: u16, method: &str, url: &str, headers: &str, body_total: u64, truncated: bool, body: &[u8]) {
        let m = &method.as_bytes()[..method.len().min(255)];
        let u = &url.as_bytes()[..url.len().min(u16::MAX as usize)];
        let h = headers.as_bytes();
        let mut b = Vec::with_capacity(32 + m.len() + u.len() + h.len() + body.len());
        b.push(response as u8);
        b.extend_from_slice(&status.to_be_bytes());
        b.push(m.len() as u8);
        b.extend_from_slice(m);
        b.extend_from_slice(&(u.len() as u16).to_be_bytes());
        b.extend_from_slice(u);
        b.extend_from_slice(&(h.len() as u32).to_be_bytes());
        b.extend_from_slice(h);
        b.extend_from_slice(&body_total.to_be_bytes());
        b.push(truncated as u8);
        b.extend_from_slice(&(body.len() as u32).to_be_bytes());
        b.extend_from_slice(body);
        self.send(MESSAGE, id, &b);
    }

    pub fn tls_note(&self, id: u64, kind: u8) {
        self.send(TLS_NOTE, id, &[kind]);
    }

    pub fn progress(&self, id: u64, down: bool, total: u64) {
        let mut b = Vec::with_capacity(9);
        b.push(down as u8);
        b.extend_from_slice(&total.to_be_bytes());
        self.send(PROGRESS, id, &b);
    }

    pub fn close(&self, id: u64, up: u64, down: u64) {
        let mut b = Vec::with_capacity(16);
        b.extend_from_slice(&up.to_be_bytes());
        b.extend_from_slice(&down.to_be_bytes());
        self.send(CLOSE, id, &b);
    }
}

fn addr16(ip: IpAddr) -> [u8; 16] {
    let mut out = [0u8; 16];
    match ip {
        IpAddr::V4(a) => out[..4].copy_from_slice(&a.octets()),
        IpAddr::V6(a) => out.copy_from_slice(&a.octets()),
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn open_event_layout() {
        let (sink, rx) = EventSink::channel();
        sink.open(7, 6, "10.0.0.2:40000".parse().unwrap(), "93.184.216.34:443".parse().unwrap());
        let ev = rx.recv().unwrap();
        assert_eq!(u32::from_be_bytes(ev[0..4].try_into().unwrap()) as usize, ev.len());
        assert_eq!(ev[4], OPEN);
        assert_eq!(u64::from_be_bytes(ev[5..13].try_into().unwrap()), 7);
        assert_eq!(ev[13], 6);
        assert_eq!(ev[14], 4);
        assert_eq!(u16::from_be_bytes([ev[15], ev[16]]), 40000);
        assert_eq!(u16::from_be_bytes([ev[17], ev[18]]), 443);
        assert_eq!(&ev[19..23], &[10, 0, 0, 2]);
        assert_eq!(&ev[35..39], &[93, 184, 216, 34]);
    }
}
