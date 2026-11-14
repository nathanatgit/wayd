// SPDX-License-Identifier: GPL-3.0-or-later
//! Global block lists (Adblock/hosts subset, compiled by Kotlin). Hosts are stored as 64-bit hashes in sorted
//! vectors, so memory is ~8 bytes per entry and a lookup is a few binary searches (one per label suffix of the
//! host), independent of how many rules the lists contain. Evaluated once per connection, never per packet.
use std::collections::HashMap;

/// Reserved rule id reported for a list block; per-app rules use small positive (saved) or negative (session) ids.
pub const LIST_RULE: i64 = -1_000_000_000_000_000;
const MAX_BYTES: u64 = 64 * 1024 * 1024;
const MAX_ENTRIES: usize = 2_000_000;

#[derive(Default)]
pub struct HostList {
    exact: Vec<u64>,
    suffix: Vec<u64>,
    allow: Vec<u64>,
    important: Vec<u64>,
    /// Suffix rules limited to packages (`$app=`): hash → package names.
    scoped: HashMap<u64, Vec<String>>,
    pub entries: usize,
}

fn hash(s: &str) -> u64 {
    // FNV-1a, 64-bit. Hosts are already normalized to lower-case ASCII.
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in s.bytes() { h ^= b as u64; h = h.wrapping_mul(0x0000_0100_0000_01b3); }
    h
}

impl HostList {
    /// One entry per line: `<flag>\t<host>[\t<pkg>|<pkg>…]`. Flags: `=` exact host, `.` host and subdomains,
    /// `@` exception (host and subdomains), `!` important block (wins over exceptions). Any malformed line rejects
    /// the whole file: a half-loaded list must never look installed.
    pub fn parse(text: &str) -> Option<Self> {
        let mut l = Self::default();
        for line in text.lines() {
            if line.is_empty() { continue; }
            let mut f = line.split('\t');
            let (flag, host) = (f.next()?, f.next()?);
            if crate::blocking::normalize_host(host).as_deref() != Some(host) || !host.contains('.') { return None; }
            let h = hash(host);
            match (flag, f.next()) {
                ("=", None) => l.exact.push(h),
                (".", None) => l.suffix.push(h),
                ("@", None) => l.allow.push(h),
                ("!", None) => l.important.push(h),
                (".", Some(apps)) => {
                    let pkgs: Vec<String> = apps.split('|').filter(|p| !p.is_empty()).map(String::from).collect();
                    if pkgs.is_empty() || pkgs.len() > 64 { return None; }
                    l.scoped.entry(h).or_default().extend(pkgs);
                }
                _ => return None,
            }
            l.entries += 1;
            if l.entries > MAX_ENTRIES { return None; }
        }
        for v in [&mut l.exact, &mut l.suffix, &mut l.allow, &mut l.important] { v.sort_unstable(); v.dedup(); v.shrink_to_fit(); }
        Some(l)
    }

    pub fn load(path: &str) -> Option<Self> {
        if std::fs::metadata(path).ok()?.len() > MAX_BYTES { return None; }
        Self::parse(&std::fs::read_to_string(path).ok()?)
    }

    pub fn is_empty(&self) -> bool { self.entries == 0 }

    /// `owner` is `user:package` when the app is known. Precedence: important block → exception → block.
    pub fn blocks(&self, host: &str, owner: Option<&str>) -> bool {
        let pkg = owner.and_then(|o| o.split_once(':')).map(|(_, p)| p);
        let has = |v: &Vec<u64>, h: u64| v.binary_search(&h).is_ok();
        let (mut blocked, mut allowed) = (has(&self.exact, hash(host)), false);
        let mut s = host;
        loop {
            if !s.contains('.') { break; } // never match a bare top-level label
            let h = hash(s);
            if has(&self.important, h) { return true; }
            if has(&self.allow, h) { allowed = true; }
            if has(&self.suffix, h) { blocked = true; }
            if let (Some(p), Some(apps)) = (pkg, self.scoped.get(&h)) { if apps.iter().any(|a| a == p) { blocked = true; } }
            match s.split_once('.') { Some((_, rest)) => s = rest, None => break }
        }
        blocked && !allowed
    }
}


/// First question of a plain DNS query: (lower-case name, offset just past QTYPE/QCLASS). None for responses,
/// non-standard opcodes, compressed or malformed names: those are forwarded untouched.
pub fn dns_question(p: &[u8]) -> Option<(String, usize)> {
    if p.len() < 17 || p[2] & 0x80 != 0 || (p[2] >> 3) & 0x0f != 0 || u16::from_be_bytes([p[4], p[5]]) != 1 { return None; }
    let mut name = String::new();
    let mut i = 12;
    loop {
        let len = *p.get(i)? as usize;
        if len == 0 { i += 1; break; }
        if len > 63 || name.len() + len + 1 > 254 { return None; }
        let label = p.get(i + 1..i + 1 + len)?;
        if !label.iter().all(|c| c.is_ascii_alphanumeric() || *c == b'-' || *c == b'_') { return None; }
        if !name.is_empty() { name.push('.'); }
        name.extend(label.iter().map(|c| c.to_ascii_lowercase() as char));
        i += 1 + len;
    }
    if name.is_empty() || p.len() < i + 4 { return None; }
    Some((name, i + 4))
}

/// Local answer for a listed name: the unspecified address for A/AAAA (the app's connect fails at once, no retry
/// storm against other resolvers), an empty NOERROR answer for every other type.
pub fn dns_blocked_reply(query: &[u8], question_end: usize) -> Vec<u8> {
    let qtype = u16::from_be_bytes([query[question_end - 4], query[question_end - 3]]);
    let rdata: &[u8] = match qtype { 1 => &[0; 4], 28 => &[0; 16], _ => &[] };
    let mut r = Vec::with_capacity(question_end + 28);
    r.extend_from_slice(&query[..2]);
    r.extend_from_slice(&[0x80 | (query[2] & 0x01), 0x80]); // QR, copy RD; RA, NOERROR
    r.extend_from_slice(&[0, 1, 0, if rdata.is_empty() { 0 } else { 1 }, 0, 0, 0, 0]);
    r.extend_from_slice(&query[12..question_end]);
    if !rdata.is_empty() {
        r.extend_from_slice(&[0xc0, 0x0c]);
        r.extend_from_slice(&qtype.to_be_bytes());
        r.extend_from_slice(&[0, 1, 0, 0, 0, 60]); // IN, TTL 60 s
        r.extend_from_slice(&(rdata.len() as u16).to_be_bytes());
        r.extend_from_slice(rdata);
    }
    r
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn suffix_exact_exception_important_and_app_scope() {
        let l = HostList::parse("=\tads.example.com\n.\ttracker.test\n@\tok.tracker.test\n!\tbad.ok.tracker.test\n.\tscoped.test\tcom.a|com.b\n").unwrap();
        assert_eq!(l.entries, 5);
        assert!(l.blocks("ads.example.com", None));
        assert!(!l.blocks("x.ads.example.com", None)); // exact only
        assert!(l.blocks("tracker.test", None) && l.blocks("a.b.tracker.test", None));
        assert!(!l.blocks("nottracker.test", None)); // label boundary
        assert!(!l.blocks("ok.tracker.test", None) && !l.blocks("x.ok.tracker.test", None));
        assert!(l.blocks("bad.ok.tracker.test", None)); // important beats the exception
        assert!(l.blocks("s.scoped.test", Some("0:com.a")) && !l.blocks("s.scoped.test", Some("0:com.c")) && !l.blocks("s.scoped.test", None));
        assert!(!l.blocks("example.com", None));
    }

    #[test]
    fn malformed_lines_reject_the_whole_list() {
        assert!(HostList::parse("?\tx.test\n").is_none());
        assert!(HostList::parse(".\tUPPER.test\n").is_none());
        assert!(HostList::parse(".\tcom\n").is_none());
        assert!(HostList::parse("=\tx.test\textra\n").is_none());
        assert!(HostList::parse("").unwrap().is_empty());
    }


    fn query(name: &str, qtype: u16) -> Vec<u8> {
        let mut q = vec![0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
        for l in name.split('.') { q.push(l.len() as u8); q.extend_from_slice(l.as_bytes()); }
        q.push(0);
        q.extend_from_slice(&qtype.to_be_bytes());
        q.extend_from_slice(&[0, 1]);
        q
    }

    #[test]
    fn dns_question_and_local_answers() {
        let q = query("Ads.Example.COM", 1);
        let (name, end) = dns_question(&q).unwrap();
        assert_eq!(name, "ads.example.com");
        assert_eq!(end, q.len());
        let a = dns_blocked_reply(&q, end);
        assert_eq!(&a[..2], &q[..2]);
        assert_eq!(a[2] & 0x80, 0x80);
        assert_eq!(a[3] & 0x0f, 0);
        assert_eq!(&a[4..8], &[0, 1, 0, 1]);
        assert_eq!(&a[a.len() - 4..], &[0, 0, 0, 0]);
        let q6 = query("ads.example.com", 28);
        assert_eq!(dns_blocked_reply(&q6, q6.len()).len(), q6.len() + 12 + 16);
        let https = query("ads.example.com", 65);
        let r = dns_blocked_reply(&https, https.len());
        assert_eq!(&r[4..8], &[0, 1, 0, 0]); // NODATA
        assert_eq!(r.len(), https.len());
        // Responses, truncated packets and compressed names are never answered locally.
        let mut resp = q.clone(); resp[2] |= 0x80;
        assert!(dns_question(&resp).is_none());
        assert!(dns_question(&q[..q.len() - 3]).is_none());
        let mut comp = q.clone(); comp[12] = 0xc0;
        assert!(dns_question(&comp).is_none());
    }

    #[test]
    fn large_lists_stay_fast() {
        let text: String = (0..300_000).map(|i| format!(".\th{i}.example{}.test\n", i % 977)).collect();
        let l = HostList::parse(&text).unwrap();
        let t = std::time::Instant::now();
        let mut hits = 0;
        for i in 0..100_000 { if l.blocks(&format!("a.b.h{i}.example{}.test", i % 977), None) { hits += 1; } }
        assert_eq!(hits, 100_000);
        // Generous bound for slow CI hosts; on a phone this is a few microseconds per lookup.
        assert!(t.elapsed().as_millis() < 2_000, "{:?}", t.elapsed());
    }
}
