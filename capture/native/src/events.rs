// SPDX-License-Identifier: GPL-3.0-or-later
//! Binary event encoding. All integers big-endian. Each event: `u32 total_len | u8 type | u64 flow_id | body`.
//!
//! * `OPEN  (1)`: `u8 proto(6|17) | u8 ip_version | u16 src_port | u16 dst_port | [16] src | [16] dst`
//!   (IPv4 addresses are left-aligned in the 16 bytes; use `ip_version` to know how many are valid)
//! * `DATA  (2)`: `u8 dir(0=up,1=down) | u16 len | bytes` — bounded prefix of the flow payload
//! * `CLOSE (3)`: `u64 bytes_up | u64 bytes_down`

use std::net::{IpAddr, SocketAddr};
use std::sync::mpsc::{channel, Receiver, Sender};

pub const OPEN: u8 = 1;
pub const DATA: u8 = 2;
pub const CLOSE: u8 = 3;

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
