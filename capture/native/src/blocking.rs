// SPDX-License-Identifier: GPL-3.0-or-later
//! Local policy gate, independent of MITM and any future routing transport.
use std::collections::{HashMap, HashSet};
use std::net::IpAddr;
use std::sync::atomic::AtomicU64;
use std::sync::{Arc, Mutex};
use tokio::sync::watch;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Decision { pub kind: u8, pub revision: u64, pub rule: i64 }

#[derive(Clone)]
struct Rule { id: i64, owner: String, kind: String, target: String }

pub struct Policy { pub revision: u64, rules: Vec<Rule> }

impl Policy {
    pub fn parse(text: &str) -> Option<Self> {
        if text.len() > 512 * 1024 { return None; }
        let mut lines = text.lines();
        let header: Vec<_> = lines.next()?.split('\t').collect();
        if header.len() != 2 || header[0] != "WAYD1" { return None; }
        let revision = header[1].parse().ok()?;
        let mut rules = Vec::new();
        let mut ids = HashSet::new();
        for line in lines {
            let f: Vec<_> = line.split('\t').collect();
            if f.len() != 4 || rules.len() >= 1000 { return None; }
            let id: i64 = f[0].parse().ok()?;
            if id == 0 || !ids.insert(id) || !valid_owner(f[1]) { return None; }
            let valid = match f[2] {
                "APP" => f[3] == "*",
                "HOST" => normalize_host(f[3]).as_deref() == Some(f[3]),
                "IP" => f[3].parse::<IpAddr>().is_ok(),
                _ => false,
            };
            if !valid { return None; }
            rules.push(Rule { id, owner: f[1].into(), kind: f[2].into(), target: f[3].into() });
        }
        Some(Self { revision, rules })
    }

    fn blocked(&self, m: &Meta) -> Option<Decision> {
        self.rules.iter().find(|r| m.owner.as_deref() == Some(&r.owner) && match r.kind.as_str() {
            "APP" => true,
            "IP" => r.target.parse::<IpAddr>().ok() == Some(m.ip),
            "HOST" => m.host.as_deref() == Some(&r.target),
            _ => false,
        }).map(|r| Decision { kind: 1, revision: self.revision, rule: r.id })
    }
}

pub(crate) fn valid_owner(s: &str) -> bool {
    let Some((user, pkg)) = s.split_once(':') else { return false; };
    user.parse::<u32>().is_ok() && !pkg.is_empty() && pkg.len() <= 255 &&
        pkg.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'.' || c == b'_')
}

pub fn normalize_host(s: &str) -> Option<String> {
    let s = s.strip_suffix('.').unwrap_or(s);
    if s.is_empty() || s.len() > 253 || !s.is_ascii() || s.parse::<IpAddr>().is_ok() { return None; }
    if !s.split('.').all(|l| !l.is_empty() && l.len() <= 63 && !l.starts_with('-') && !l.ends_with('-') &&
        l.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'-')) { return None; }
    Some(s.to_ascii_lowercase())
}

struct Meta { owner: Option<String>, host: Option<String>, ip: IpAddr, control: Arc<FlowControl> }
struct State { policy: Policy, flows: HashMap<u64, Meta> }

pub struct FlowControl {
    pub up: Arc<AtomicU64>, pub down: Arc<AtomicU64>,
    pub owner: watch::Sender<bool>, pub host: watch::Sender<bool>, pub stop: watch::Sender<Decision>,
}

impl FlowControl {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            up: Arc::new(AtomicU64::new(0)), down: Arc::new(AtomicU64::new(0)),
            owner: watch::channel(false).0, host: watch::channel(false).0,
            stop: watch::channel(Decision::default()).0,
        })
    }

    pub async fn cancelled(&self) -> Decision {
        let mut rx = self.stop.subscribe();
        loop {
            let d = *rx.borrow_and_update();
            if d.kind != 0 { return d; }
            if rx.changed().await.is_err() { return Decision { kind: 2, ..Decision::default() }; }
        }
    }

    pub async fn owner_ready(&self) {
        let mut rx = self.owner.subscribe();
        while !*rx.borrow_and_update() { if rx.changed().await.is_err() { break; } }
    }

    pub async fn host_ready(&self) {
        let mut rx = self.host.subscribe();
        while !*rx.borrow_and_update() { if rx.changed().await.is_err() { break; } }
    }
}

pub struct Blocking(Mutex<State>);

impl Blocking {
    pub fn new() -> Self {
        Self(Mutex::new(State { policy: Policy { revision: 0, rules: Vec::new() }, flows: HashMap::new() }))
    }

    pub fn install(&self, text: &str) -> bool {
        let Some(policy) = Policy::parse(text) else { return false; };
        let mut s = self.0.lock().unwrap();
        if policy.revision <= s.policy.revision { return false; }
        s.policy = policy;
        for m in s.flows.values() { if let Some(d) = s.policy.blocked(m) { m.control.stop.send_replace(d); } }
        true
    }

    pub fn open(&self, id: u64, ip: IpAddr) -> Arc<FlowControl> {
        let c = FlowControl::new();
        self.0.lock().unwrap().flows.insert(id, Meta { owner: None, host: None, ip, control: c.clone() });
        c
    }

    pub fn close(&self, id: u64) { self.0.lock().unwrap().flows.remove(&id); }
    pub fn active(&self) -> bool { !self.0.lock().unwrap().policy.rules.is_empty() }

    pub fn metadata(&self, id: u64) -> (String, String) {
        self.0.lock().unwrap().flows.get(&id).map(|m| (m.owner.clone().unwrap_or_default(), m.host.clone().unwrap_or_default())).unwrap_or_default()
    }

    pub fn info(&self, id: u64, value: &str, host: bool) {
        let mut s = self.0.lock().unwrap();
        if let Some(m) = s.flows.get_mut(&id) {
            if host {
                m.host = normalize_host(value);
                m.control.host.send_replace(true);
            } else {
                m.owner = valid_owner(value).then(|| value.into());
                m.control.owner.send_replace(true);
            }
        }
        if let Some(m) = s.flows.get(&id) {
            if let Some(d) = s.policy.blocked(m) { m.control.stop.send_replace(d); }
        }
    }

    pub fn needs_host(&self, id: u64) -> bool {
        let s = self.0.lock().unwrap();
        s.flows.get(&id).is_some_and(|m| s.policy.rules.iter().any(|r| r.kind == "HOST" && m.owner.as_deref() == Some(&r.owner)))
    }

    pub fn outcome(&self, id: u64, error: bool) -> Decision {
        let s = self.0.lock().unwrap();
        let mut d = Decision { kind: if error { 2 } else { 5 }, revision: s.policy.revision, rule: 0 };
        if let Some(m) = s.flows.get(&id) {
            if let Some(blocked) = s.policy.blocked(m) { return blocked; }
            if !error && !s.policy.rules.is_empty() {
                if m.owner.is_none() { d.kind = 3; }
                else if m.host.is_none() && s.policy.rules.iter().any(|r| r.kind == "HOST" && m.owner.as_deref() == Some(&r.owner)) { d.kind = 4; }
            }
        }
        d
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn snapshots_are_atomic_scoped_and_revisioned() {
        let p = Blocking::new();
        let c = p.open(1, "192.0.2.1".parse().unwrap());
        p.info(1, "0:org.example.app", false);
        p.info(1, "Metrics.Example.COM.", true);
        assert!(p.install("WAYD1\t1\n8\t0:org.example.other\tHOST\tmetrics.example.com"));
        assert_eq!(p.outcome(1, false).kind, 5);
        assert!(!p.install("WAYD1\t2\n9\t0:org.example.app\tAPP\t*\ninvalid"));
        assert_eq!(p.outcome(1, false).revision, 1);
        assert!(p.install("WAYD1\t2\n9\t0:org.example.app\tHOST\tmetrics.example.com"));
        assert_eq!(c.stop.borrow().rule, 9);
        assert!(!p.install("WAYD1\t2"));
        assert!(p.install("WAYD1\t3"));
        assert_eq!(p.outcome(1, false).kind, 5);
    }

    #[test]
    fn unknown_identity_and_host_are_not_guessed() {
        let p = Blocking::new(); p.open(1, "2001:db8::1".parse().unwrap());
        assert!(p.install("WAYD1\t1\n1\t0:org.example.app\tHOST\texample.com"));
        assert_eq!(p.outcome(1, false).kind, 3);
        p.info(1, "10:org.example.app", false);
        assert!(!p.needs_host(1));
        p.info(1, "0:org.example.app", false);
        assert!(p.needs_host(1)); assert_eq!(p.outcome(1, false).kind, 4);
        p.info(1, "notexample.com", true);
        assert_eq!(p.outcome(1, false).kind, 5);
    }

    #[test]
    fn ip_rules_and_invalid_inputs() {
        let p = Blocking::new(); p.open(1, "2001:db8::1".parse().unwrap()); p.info(1, "0:android", false);
        assert!(p.install("WAYD1\t1\n-1\t0:android\tIP\t2001:db8::1"));
        assert_eq!(p.outcome(1, false).rule, -1);
        for bad in ["WAYD1\t2\n0\t0:android\tAPP\t*", "WAYD1\t2\n1\t0:android\tALLOW\t*", "WAYD1\t2\n1\t0:android\tHOST\texample.com\n1\t0:android\tAPP\t*"] { assert!(!p.install(bad)); }
        for host in ["", "*.example.com", "evil\r\nhost", "example..com", "192.0.2.1"] { assert!(normalize_host(host).is_none()); }
    }
}
