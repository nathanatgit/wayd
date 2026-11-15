// SPDX-License-Identifier: GPL-3.0-or-later
//! DNS rules (D-072): local answers for chosen names and record-type blocks, compiled by Kotlin from hosts files,
//! dnsmasq `address=` lines (the formats Daedalus reads) and hand-made rules. Checked after the block lists, so a
//! listed name is always blocked. Only plain UDP queries to port 53 are seen; encrypted DNS is not.
use std::collections::{HashMap, HashSet};
use std::net::{Ipv4Addr, Ipv6Addr};

const MAX_BYTES: u64 = 64 * 1024 * 1024;
const MAX_ENTRIES: usize = 2_000_000;
const MAX_ADDRS: usize = 16;

#[derive(Default, Debug, PartialEq)]
pub struct Answer {
    pub v4: Vec<Ipv4Addr>,
    pub v6: Vec<Ipv6Addr>,
    pub block: bool,
}

#[derive(Default)]
pub struct DnsRules {
    exact: HashMap<u64, Answer>,
    suffix: HashMap<u64, Answer>,
    types: HashSet<u16>,
    pub entries: usize,
}

fn hash(s: &str) -> u64 {
    // FNV-1a, 64-bit, as in hostlist.rs. Names are normalized lower-case ASCII.
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in s.bytes() { h ^= b as u64; h = h.wrapping_mul(0x0000_0100_0000_01b3); }
    h
}

impl DnsRules {
    /// One entry per line: `T\t<qtype>` blocks a record type for every name; `<flag>\t<host>\t<action>` with flag `=`
    /// (exact name) or `.` (name and subdomains) and action `A\t<ipv4>`, `AAAA\t<ipv6>` or `BLOCK`. Several address
    /// lines for one name add records. Any malformed line rejects the whole file.
    pub fn parse(text: &str) -> Option<Self> {
        let mut r = Self::default();
        for line in text.lines() {
            if line.is_empty() { continue; }
            let f: Vec<&str> = line.split('\t').collect();
            if f[0] == "T" {
                if f.len() != 2 { return None; }
                let t: u16 = f[1].parse().ok()?;
                if t == 0 || t == 255 { return None; }
                r.types.insert(t);
            } else {
                if f.len() < 3 { return None; }
                let host = f[1];
                if crate::blocking::normalize_host(host).as_deref() != Some(host) || !host.contains('.') { return None; }
                let map = match f[0] { "=" => &mut r.exact, "." => &mut r.suffix, _ => return None };
                let a = map.entry(hash(host)).or_default();
                match (f[2], f.get(3), f.len()) {
                    ("A", Some(ip), 4) => { let ip: Ipv4Addr = ip.parse().ok()?; if a.v4.len() < MAX_ADDRS && !a.v4.contains(&ip) { a.v4.push(ip); } }
                    ("AAAA", Some(ip), 4) => { let ip: Ipv6Addr = ip.parse().ok()?; if a.v6.len() < MAX_ADDRS && !a.v6.contains(&ip) { a.v6.push(ip); } }
                    ("BLOCK", None, 3) => a.block = true,
                    _ => return None,
                }
            }
            r.entries += 1;
            if r.entries > MAX_ENTRIES { return None; }
        }
        Some(r)
    }

    pub fn load(path: &str) -> Option<Self> {
        if std::fs::metadata(path).ok()?.len() > MAX_BYTES { return None; }
        Self::parse(&std::fs::read_to_string(path).ok()?)
    }

    pub fn is_empty(&self) -> bool { self.entries == 0 }

    /// Exact rule first, then the most specific suffix rule (the name itself, then each parent), as dnsmasq does.
    /// A bare top-level label never matches.
    pub fn lookup(&self, name: &str) -> Option<&Answer> {
        if let Some(a) = self.exact.get(&hash(name)) { return Some(a); }
        let mut s = name;
        while s.contains('.') {
            if let Some(a) = self.suffix.get(&hash(s)) { return Some(a); }
            s = s.split_once('.')?.1;
        }
        None
    }

    /// Local reply for a query, or None to forward it. A name with addresses answers A/AAAA from them; the other family
    /// and HTTPS/SVCB records (which can carry the real addresses as hints) get an empty answer so the mapping holds.
    /// Other types for a mapped name are forwarded. A blocked name answers like a block list. Then type blocks apply.
    pub fn reply(&self, query: &[u8], question_end: usize, name: &str) -> Option<Vec<u8>> {
        let qtype = u16::from_be_bytes([query[question_end - 4], query[question_end - 3]]);
        if let Some(a) = self.lookup(name) {
            if a.block && a.v4.is_empty() && a.v6.is_empty() { return Some(crate::hostlist::dns_blocked_reply(query, question_end)); }
            let rdata: Option<Vec<Vec<u8>>> = match qtype {
                1 => Some(if a.v4.is_empty() && a.block { vec![vec![0; 4]] } else { a.v4.iter().map(|ip| ip.octets().to_vec()).collect() }),
                28 => Some(if a.v6.is_empty() && a.block { vec![vec![0; 16]] } else { a.v6.iter().map(|ip| ip.octets().to_vec()).collect() }),
                64 | 65 => Some(Vec::new()),
                _ if a.block => Some(Vec::new()),
                _ => None,
            };
            if let Some(rdata) = rdata { return Some(answer(query, question_end, qtype, &rdata)); }
        }
        self.types.contains(&qtype).then(|| answer(query, question_end, qtype, &[]))
    }
}

/// NOERROR reply echoing the question, with one record per `rdata` (TTL 60 s); none means an empty (NODATA) answer.
pub fn answer(query: &[u8], question_end: usize, qtype: u16, rdata: &[Vec<u8>]) -> Vec<u8> {
    let mut r = Vec::with_capacity(question_end + rdata.len() * 28);
    r.extend_from_slice(&query[..2]);
    r.extend_from_slice(&[0x80 | (query[2] & 0x01), 0x80]); // QR, copy RD; RA, NOERROR
    r.extend_from_slice(&[0, 1]);
    r.extend_from_slice(&(rdata.len() as u16).to_be_bytes());
    r.extend_from_slice(&[0, 0, 0, 0]);
    r.extend_from_slice(&query[12..question_end]);
    for d in rdata {
        r.extend_from_slice(&[0xc0, 0x0c]);
        r.extend_from_slice(&qtype.to_be_bytes());
        r.extend_from_slice(&[0, 1, 0, 0, 0, 60]);
        r.extend_from_slice(&(d.len() as u16).to_be_bytes());
        r.extend_from_slice(d);
    }
    r
}

#[cfg(test)]
mod tests {
    use super::*;

    fn query(name: &str, qtype: u16) -> Vec<u8> {
        let mut q = vec![0xab, 0xcd, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
        for l in name.split('.') { q.push(l.len() as u8); q.extend_from_slice(l.as_bytes()); }
        q.push(0);
        q.extend_from_slice(&qtype.to_be_bytes());
        q.extend_from_slice(&[0, 1]);
        q
    }

    fn ancount(r: &[u8]) -> u16 { u16::from_be_bytes([r[6], r[7]]) }

    #[test]
    fn exact_and_most_specific_suffix() {
        let r = DnsRules::parse("=\thost.test\tA\t10.0.0.1\n.\texample.test\tA\t10.0.0.2\n.\tsub.example.test\tA\t10.0.0.3\n.\tgone.test\tBLOCK\n").unwrap();
        assert_eq!(r.lookup("host.test").unwrap().v4, vec![Ipv4Addr::new(10, 0, 0, 1)]);
        assert!(r.lookup("x.host.test").is_none()); // exact only
        assert_eq!(r.lookup("example.test").unwrap().v4, vec![Ipv4Addr::new(10, 0, 0, 2)]);
        assert_eq!(r.lookup("a.b.example.test").unwrap().v4, vec![Ipv4Addr::new(10, 0, 0, 2)]);
        assert_eq!(r.lookup("x.sub.example.test").unwrap().v4, vec![Ipv4Addr::new(10, 0, 0, 3)]);
        assert!(r.lookup("notexample.test").is_none());
        assert!(r.lookup("a.gone.test").unwrap().block);
    }

    #[test]
    fn answers_per_type() {
        let r = DnsRules::parse("=\tm.test\tA\t1.2.3.4\n=\tm.test\tA\t1.2.3.5\n=\tb.test\tBLOCK\nT\t65\n").unwrap();
        let q = query("m.test", 1);
        let a = r.reply(&q, q.len(), "m.test").unwrap();
        assert_eq!(ancount(&a), 2);
        assert_eq!(&a[a.len() - 4..], &[1, 2, 3, 5]);
        let q6 = query("m.test", 28);
        assert_eq!(ancount(&r.reply(&q6, q6.len(), "m.test").unwrap()), 0); // other family: empty, so the mapping holds
        let https = query("m.test", 65);
        assert_eq!(ancount(&r.reply(&https, https.len(), "m.test").unwrap()), 0);
        let txt = query("m.test", 16);
        assert!(r.reply(&txt, txt.len(), "m.test").is_none()); // forwarded
        let qb = query("b.test", 28);
        let b = r.reply(&qb, qb.len(), "b.test").unwrap();
        assert_eq!(ancount(&b), 1);
        assert_eq!(&b[b.len() - 16..], &[0; 16]);
        // Type block for every other name; other types pass.
        let other = query("other.test", 65);
        assert_eq!(ancount(&r.reply(&other, other.len(), "other.test").unwrap()), 0);
        let other_a = query("other.test", 1);
        assert!(r.reply(&other_a, other_a.len(), "other.test").is_none());
    }

    #[test]
    fn reply_echoes_id_and_question() {
        let r = DnsRules::parse("=\tm.test\tAAAA\t2001:db8::1\n").unwrap();
        let q = query("m.test", 28);
        let a = r.reply(&q, q.len(), "m.test").unwrap();
        assert_eq!(&a[..2], &q[..2]);
        assert_eq!(a[2] & 0x80, 0x80);
        assert_eq!(&a[12..q.len()], &q[12..]);
        assert_eq!(&a[a.len() - 16..], &"2001:db8::1".parse::<Ipv6Addr>().unwrap().octets());
    }

    #[test]
    fn malformed_lines_reject_the_whole_file() {
        for bad in ["=\tx.test\tA\t300.1.1.1\n", "=\tx.test\tAAAA\t1.2.3.4\n", "?\tx.test\tBLOCK\n", ".\tcom\tBLOCK\n", "=\tX.test\tBLOCK\n",
            "=\tx.test\tBLOCK\textra\n", "T\tabc\n", "T\t0\n", "=\tx.test\n"] {
            assert!(DnsRules::parse(bad).is_none(), "{bad:?}");
        }
        assert!(DnsRules::parse("").unwrap().is_empty());
    }
}
