// SPDX-License-Identifier: GPL-3.0-or-later
//! Rewrite rules for decrypted HTTP/1.1 requests (phase 1, D-066): answer a request locally, remove or set request
//! headers, remove query parameters. Rules are per app and per host; a flow whose host has no rule never enters this
//! stage and keeps the byte-for-byte pass-through. The stage tracks request framing only as far as needed to find the
//! next request head; anything it cannot frame (upgrades, oversized heads, parse errors) switches the connection to
//! pass-through for good rather than guessing.
use std::collections::HashMap;
use std::sync::Mutex;

const MAX_HEAD: usize = 64 * 1024;
const MAX_RULES: usize = 2_000;

#[derive(Clone, Debug, PartialEq)]
pub enum Action {
    /// Answer locally and never contact the server: "204", "200-empty" or "200-json".
    Reject(String),
    HeaderRemove(String),
    HeaderSet(String, String),
    QueryRemove(String),
    /// Answer locally with a 302 or 307 to a fixed URL.
    Redirect(u16, String),
}

#[derive(Clone, Debug)]
pub struct Rule {
    pub id: i64,
    owner: String,
    /// Exact host, or `.suffix` for the host and its subdomains.
    host: String,
    /// Upper-case method, or empty for any.
    method: String,
    /// PREFIX, EXACT or CONTAINS against the request target (path and query), or REGEX against `https://host` + target.
    kind: String,
    pattern: String,
    regex: Option<regex::Regex>,
    action: Action,
}

impl Rule {
    fn host_matches(&self, host: &str) -> bool {
        match self.host.strip_prefix('.') {
            Some(s) => host == s || host.strip_suffix(s).is_some_and(|p| p.ends_with('.')),
            None => host == self.host,
        }
    }

    fn matches(&self, method: &str, target: &str, host: &str) -> bool {
        (self.method.is_empty() || self.method.eq_ignore_ascii_case(method)) && match self.kind.as_str() {
            "EXACT" => target == self.pattern,
            "CONTAINS" => target.contains(&self.pattern),
            "REGEX" => self.regex.as_ref().is_some_and(|r| r.is_match(&format!("https://{host}{target}"))),
            _ => target.starts_with(&self.pattern),
        }
    }
}

#[derive(Default)]
pub struct RewriteSet { rules: Vec<Rule>, hits: Mutex<HashMap<i64, u64>> }

impl RewriteSet {
    /// `WAYDRW1` header, then one rule per line: id, owner (`user:package`), host, method, match kind, pattern,
    /// action, argument; tab-separated. Any malformed line rejects the whole set.
    pub fn parse(text: &str) -> Option<Self> {
        let mut lines = text.lines();
        if lines.next()? != "WAYDRW1" { return None; }
        let mut rules = Vec::new();
        for line in lines {
            if line.is_empty() { continue; }
            let f: Vec<&str> = line.split('\t').collect();
            if f.len() != 8 || rules.len() >= MAX_RULES { return None; }
            let id: i64 = f[0].parse().ok()?;
            let host = f[2].strip_prefix('.').unwrap_or(f[2]);
            if !crate::blocking::valid_owner(f[1]) || crate::blocking::normalize_host(host).as_deref() != Some(host) { return None; }
            if !matches!(f[4], "PREFIX" | "EXACT" | "CONTAINS" | "REGEX") || f[5].is_empty() || f[5].len() > 2048 { return None; }
            // A pattern the engine cannot compile (look-around, back-references, too large) disables that one rule, not the whole set.
            let regex = if f[4] == "REGEX" {
                match regex::RegexBuilder::new(f[5]).size_limit(1 << 20).build() { Ok(r) => Some(r), Err(_) => continue }
            } else { None };
            let token = |s: &str| !s.is_empty() && s.len() <= 128 && s.bytes().all(|c| c.is_ascii_alphanumeric() || b"-_.".contains(&c));
            let action = match f[6] {
                "REJECT" if matches!(f[7], "204" | "200-empty" | "200-json") => Action::Reject(f[7].into()),
                "HEADER_REMOVE" if token(f[7]) => Action::HeaderRemove(f[7].into()),
                "HEADER_SET" => {
                    let (name, value) = f[7].split_once(": ")?;
                    if !token(name) || value.len() > 1024 || value.bytes().any(|c| c < 0x20 || c == 0x7f) { return None; }
                    Action::HeaderSet(name.into(), value.into())
                }
                "QUERY_REMOVE" if token(f[7]) => Action::QueryRemove(f[7].into()),
                "REDIRECT" => {
                    let (code, url) = f[7].split_once(' ')?;
                    let code: u16 = code.parse().ok()?;
                    if !matches!(code, 302 | 307) || !(url.starts_with("https://") || url.starts_with("http://")) || url.len() > 2048 || url.bytes().any(|c| c <= 0x20 || c == 0x7f) { return None; }
                    Action::Redirect(code, url.into())
                }
                _ => return None,
            };
            rules.push(Rule { id, owner: f[1].into(), host: f[2].into(), method: f[3].to_ascii_uppercase(), kind: f[4].into(), pattern: f[5].into(), regex, action });
        }
        Some(Self { rules, hits: Mutex::new(HashMap::new()) })
    }

    pub fn is_empty(&self) -> bool { self.rules.is_empty() }

    /// The stage for one decrypted flow, or None when no rule names this app and host (the common case).
    pub fn stage(&self, owner: &str, host: &str) -> Option<ReqStage> {
        let rules: Vec<Rule> = self.rules.iter().filter(|r| r.owner == owner && r.host_matches(host)).cloned().collect();
        (!rules.is_empty()).then(|| ReqStage { rules, state: St::Head, buf: Vec::new(), host: host.to_string() })
    }

    pub fn hit(&self, ids: &[i64]) {
        if ids.is_empty() { return; }
        let mut h = self.hits.lock().unwrap();
        for id in ids { *h.entry(*id).or_insert(0) += 1; }
    }

    /// `id\tcount` lines for the UI.
    pub fn hits_text(&self) -> String {
        self.hits.lock().unwrap().iter().map(|(id, n)| format!("{id}\t{n}\n")).collect()
    }
}

enum Chunk { Size(Vec<u8>), Data(u64), DataEnd(u8), Trailer(Vec<u8>) }
enum St { Head, Body { left: u64, drop: bool }, Chunked { st: Chunk, drop: bool }, Raw }

/// What to do with one read from the app.
#[derive(Default, Debug)]
pub struct Out {
    /// Bytes for the server (possibly rewritten).
    pub forward: Vec<u8>,
    /// Complete local responses for rejected requests, in order.
    pub local: Vec<Vec<u8>>,
    /// Requests whose head went to the server in this read.
    pub forwarded: u32,
    pub hits: Vec<i64>,
}

pub struct ReqStage { rules: Vec<Rule>, state: St, buf: Vec<u8>, host: String }

impl ReqStage {
    pub fn feed(&mut self, mut data: &[u8]) -> Out {
        let mut out = Out::default();
        while !data.is_empty() {
            match &mut self.state {
                St::Raw => { out.forward.extend_from_slice(data); break; }
                St::Body { left, drop } => {
                    let n = (*left).min(data.len() as u64) as usize;
                    if !*drop { out.forward.extend_from_slice(&data[..n]); }
                    *left -= n as u64;
                    data = &data[n..];
                    if *left == 0 { self.state = St::Head; }
                }
                St::Chunked { st, drop } => {
                    let drop = *drop;
                    let (used, done, bad) = chunk_step(st, data);
                    if !drop { out.forward.extend_from_slice(&data[..used]); }
                    data = &data[used..];
                    if bad { self.state = St::Raw; } else if done { self.state = St::Head; }
                }
                St::Head => {
                    self.buf.extend_from_slice(data);
                    data = &[];
                    let Some(end) = find_head_end(&self.buf) else {
                        if self.buf.len() > MAX_HEAD { self.state = St::Raw; out.forward.append(&mut self.buf); }
                        break;
                    };
                    let rest = self.buf.split_off(end);
                    let head = std::mem::take(&mut self.buf);
                    self.head(&head, &mut out);
                    // Bytes after the head belong to the body or the next request: run them through the new state.
                    if !rest.is_empty() {
                        let more = self.feed(&rest);
                        out.forward.extend(more.forward);
                        out.local.extend(more.local);
                        out.forwarded += more.forwarded;
                        out.hits.extend(more.hits);
                    }
                }
            }
        }
        out
    }

    fn head(&mut self, head: &[u8], out: &mut Out) {
        let mut headers = [httparse::EMPTY_HEADER; 96];
        let mut req = httparse::Request::new(&mut headers);
        let (method, target, version) = match req.parse(head) {
            Ok(httparse::Status::Complete(_)) => (req.method.unwrap_or(""), req.path.unwrap_or(""), req.version.unwrap_or(1)),
            _ => { self.state = St::Raw; out.forward.extend_from_slice(head); return; }
        };
        let mut chunked = false;
        let mut length: Option<u64> = None;
        let mut upgrade = method.eq_ignore_ascii_case("CONNECT");
        for h in req.headers.iter() {
            let v = std::str::from_utf8(h.value).unwrap_or("");
            if h.name.eq_ignore_ascii_case("transfer-encoding") && v.to_ascii_lowercase().contains("chunked") { chunked = true; }
            if h.name.eq_ignore_ascii_case("content-length") { length = v.trim().parse().ok(); }
            if h.name.eq_ignore_ascii_case("upgrade") { upgrade = true; }
        }
        let matched: Vec<&Rule> = self.rules.iter().filter(|r| r.matches(method, target, &self.host)).collect();
        let head_request = method.eq_ignore_ascii_case("HEAD");
        // The first local-answer rule (reject or redirect) decides; the request and its body then never reach the server.
        let reject = matched.iter().find_map(|r| match &r.action {
            Action::Reject(kind) => Some((r.id, local_response(kind, head_request))),
            Action::Redirect(code, url) => Some((r.id, redirect_response(*code, url))),
            _ => None,
        });
        let drop = reject.is_some() && !upgrade;
        if let (Some((id, reply)), false) = (&reject, upgrade) {
            out.hits.push(*id);
            out.local.push(reply.clone());
        } else if matched.is_empty() || upgrade {
            out.forward.extend_from_slice(head);
            out.forwarded += 1;
        } else {
            let mut new_target = target.to_string();
            let mut kept: Vec<(String, Vec<u8>)> = req.headers.iter().map(|h| (h.name.to_string(), h.value.to_vec())).collect();
            for r in &matched {
                match &r.action {
                    Action::HeaderRemove(name) => kept.retain(|(n, _)| !n.eq_ignore_ascii_case(name)),
                    Action::HeaderSet(name, value) => {
                        kept.retain(|(n, _)| !n.eq_ignore_ascii_case(name));
                        kept.push((name.clone(), value.clone().into_bytes()));
                    }
                    Action::QueryRemove(name) => new_target = remove_query(&new_target, name),
                    Action::Reject(_) | Action::Redirect(..) => {}
                }
                out.hits.push(r.id);
            }
            out.forward.extend_from_slice(format!("{method} {new_target} HTTP/1.{version}\r\n").as_bytes());
            for (n, v) in kept {
                out.forward.extend_from_slice(n.as_bytes());
                out.forward.extend_from_slice(b": ");
                out.forward.extend_from_slice(&v);
                out.forward.extend_from_slice(b"\r\n");
            }
            out.forward.extend_from_slice(b"\r\n");
            out.forwarded += 1;
        }
        self.state = if upgrade { St::Raw }
            else if chunked { St::Chunked { st: Chunk::Size(Vec::new()), drop } }
            else if let Some(n) = length.filter(|n| *n > 0) { St::Body { left: n, drop } }
            else { St::Head };
    }
}

fn find_head_end(b: &[u8]) -> Option<usize> { b.windows(4).position(|w| w == b"\r\n\r\n").map(|p| p + 4) }

/// Consumes as much of `data` as belongs to the chunked body: (bytes used, body finished, malformed).
fn chunk_step(st: &mut Chunk, data: &[u8]) -> (usize, bool, bool) {
    let mut i = 0;
    while i < data.len() {
        match st {
            Chunk::Size(line) => {
                line.push(data[i]);
                i += 1;
                if line.len() > 1024 { return (i, false, true); }
                if line.ends_with(b"\r\n") {
                    let text = String::from_utf8_lossy(&line[..line.len() - 2]).to_string();
                    let Ok(n) = u64::from_str_radix(text.split(';').next().unwrap_or("").trim(), 16) else { return (i, false, true); };
                    *st = if n == 0 { Chunk::Trailer(Vec::new()) } else { Chunk::Data(n) };
                }
            }
            Chunk::Data(left) => {
                let n = (*left).min((data.len() - i) as u64) as usize;
                *left -= n as u64;
                i += n;
                if *left == 0 { *st = Chunk::DataEnd(2); }
            }
            Chunk::DataEnd(left) => {
                *left -= 1;
                i += 1;
                if *left == 0 { *st = Chunk::Size(Vec::new()); }
            }
            Chunk::Trailer(line) => {
                line.push(data[i]);
                i += 1;
                if line.len() > 8192 { return (i, false, true); }
                if line.ends_with(b"\r\n") {
                    if line.len() == 2 { return (i, true, false); }
                    line.clear();
                }
            }
        }
    }
    (i, false, false)
}

fn remove_query(target: &str, name: &str) -> String {
    let (before, fragment) = target.split_once('#').map(|(a, b)| (a, Some(b))).unwrap_or((target, None));
    let Some((path, query)) = before.split_once('?') else { return target.to_string(); };
    let kept: Vec<&str> = query.split('&').filter(|p| p.split('=').next() != Some(name)).collect();
    let mut s = path.to_string();
    if !kept.is_empty() { s.push('?'); s.push_str(&kept.join("&")); }
    if let Some(f) = fragment { s.push('#'); s.push_str(f); }
    s
}

/// The marker header makes a local answer recognisable in the inspector and to the user.
fn local_response(kind: &str, head_request: bool) -> Vec<u8> {
    match kind {
        "200-json" if !head_request => b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nX-WAYD-Rewrite: reject\r\n\r\n{}".to_vec(),
        "200-empty" if !head_request => b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nX-WAYD-Rewrite: reject\r\n\r\n".to_vec(),
        _ => b"HTTP/1.1 204 No Content\r\nX-WAYD-Rewrite: reject\r\n\r\n".to_vec(),
    }
}

fn redirect_response(code: u16, url: &str) -> Vec<u8> {
    let reason = if code == 307 { "Temporary Redirect" } else { "Found" };
    format!("HTTP/1.1 {code} {reason}\r\nLocation: {url}\r\nContent-Length: 0\r\nX-WAYD-Rewrite: redirect\r\n\r\n").into_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;

    const OWNER: &str = "0:com.example.app";
    fn set(lines: &[&str]) -> RewriteSet { RewriteSet::parse(&format!("WAYDRW1\n{}\n", lines.join("\n"))).unwrap() }
    fn text(b: &[u8]) -> String { String::from_utf8_lossy(b).to_string() }

    #[test]
    fn parse_validates_and_scopes_by_app_and_host() {
        let s = set(&["1\t0:com.example.app\t.example.com\t\tPREFIX\t/collect\tREJECT\t204"]);
        assert!(s.stage(OWNER, "api.example.com").is_some() && s.stage(OWNER, "example.com").is_some());
        assert!(s.stage(OWNER, "notexample.com").is_none() && s.stage("0:com.other", "api.example.com").is_none());
        for bad in ["1\tbad owner\tx.com\t\tPREFIX\t/\tREJECT\t204", "1\t0:a.b\tX.com\t\tPREFIX\t/\tREJECT\t204",
            "1\t0:a.b\tx.com\t\tGLOB\t/\tREJECT\t204", "1\t0:a.b\tx.com\t\tPREFIX\t/\tREJECT\t500",
            "1\t0:a.b\tx.com\t\tPREFIX\t/\tHEADER_SET\tX-A: a\rb", "1\t0:a.b\tx.com\t\tPREFIX\t/\tHEADER_REMOVE\tbad name"] {
            assert!(RewriteSet::parse(&format!("WAYDRW1\n{bad}\n")).is_none(), "{bad}");
        }
        assert!(RewriteSet::parse("nope\n").is_none() && RewriteSet::parse("WAYDRW1\n").unwrap().is_empty());
    }

    #[test]
    fn reject_drops_the_request_and_its_body_but_keeps_the_next_one() {
        let s = set(&["7\t0:com.example.app\tapi.example.com\tPOST\tPREFIX\t/collect\tREJECT\t200-json"]);
        let mut st = s.stage(OWNER, "api.example.com").unwrap();
        // The head and body arrive split across reads, followed by an unrelated request on the same connection.
        let a = st.feed(b"POST /collect?x=1 HTTP/1.1\r\nHost: api.example.com\r\nContent-Le");
        assert!(a.forward.is_empty() && a.local.is_empty());
        let b = st.feed(b"ngth: 5\r\n\r\nhel");
        assert!(b.forward.is_empty());
        assert_eq!(b.local.len(), 1);
        assert!(text(&b.local[0]).starts_with("HTTP/1.1 200 OK") && text(&b.local[0]).ends_with("{}"));
        assert_eq!(b.hits, vec![7]);
        let c = st.feed(b"loGET /ok HTTP/1.1\r\nHost: api.example.com\r\n\r\n");
        assert_eq!(text(&c.forward), "GET /ok HTTP/1.1\r\nHost: api.example.com\r\n\r\n");
        assert_eq!(c.forwarded, 1);
        // GET to the same path is not matched by a POST rule.
        let d = st.feed(b"GET /collect HTTP/1.1\r\nHost: a\r\n\r\n");
        assert_eq!(d.forwarded, 1);
        assert!(d.local.is_empty());
    }

    #[test]
    fn header_and_query_edits_keep_the_body_and_framing() {
        let s = set(&[
            "1\t0:com.example.app\tapi.example.com\t\tPREFIX\t/v1\tHEADER_REMOVE\tX-Device-Id",
            "2\t0:com.example.app\tapi.example.com\t\tPREFIX\t/v1\tHEADER_SET\tUser-Agent: generic",
            "3\t0:com.example.app\tapi.example.com\t\tCONTAINS\tidfa=\tQUERY_REMOVE\tidfa",
        ]);
        let mut st = s.stage(OWNER, "api.example.com").unwrap();
        let o = st.feed(b"POST /v1/x?a=1&idfa=ABC&b=2#f HTTP/1.1\r\nHost: api.example.com\r\nx-device-id: 123\r\nUser-Agent: app/1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\nGET /other HTTP/1.1\r\n\r\n");
        let f = text(&o.forward);
        assert!(f.starts_with("POST /v1/x?a=1&b=2#f HTTP/1.1\r\n"), "{f}");
        assert!(!f.to_ascii_lowercase().contains("x-device-id") && f.contains("User-Agent: generic\r\n") && !f.contains("app/1"));
        assert!(f.contains("\r\n\r\n3\r\nabc\r\n0\r\n\r\nGET /other HTTP/1.1\r\n\r\n"));
        assert_eq!(o.forwarded, 2);
        assert_eq!(o.hits, vec![1, 2, 3]);
        s.hit(&o.hits);
        assert!(s.hits_text().contains("2\t1"));
    }

    #[test]
    fn redirect_answers_locally_and_validates_its_target() {
        let s = set(&["4\t0:com.example.app\tapi.example.com\tGET\tEXACT\t/old\tREDIRECT\t307 https://api.example.com/new"]);
        let mut st = s.stage(OWNER, "api.example.com").unwrap();
        let o = st.feed(b"GET /old HTTP/1.1\r\nHost: api.example.com\r\n\r\n");
        assert!(o.forward.is_empty());
        let r = text(&o.local[0]);
        assert!(r.starts_with("HTTP/1.1 307 Temporary Redirect\r\nLocation: https://api.example.com/new\r\n") && r.contains("X-WAYD-Rewrite: redirect"), "{r}");
        for bad in ["301 https://a.example/x", "302 ftp://a.example/x", "302 https://a.example/x y", "302"] {
            assert!(RewriteSet::parse(&format!("WAYDRW1\n4\t0:a.b\tx.com\t\tPREFIX\t/\tREDIRECT\t{bad}\n")).is_none(), "{bad}");
        }
    }

    #[test]
    fn regex_matches_the_full_url_and_a_bad_pattern_only_disables_its_rule() {
        let s = set(&[
            "5\t0:com.example.app\t.example.com\t\tREGEX\t^https?://api\\.example\\.com/v\\d+/(ads|track)\\b\tREJECT\t204",
            "6\t0:com.example.app\t.example.com\t\tREGEX\t(?=lookahead)\tREJECT\t204",
            "7\t0:com.example.app\t.example.com\t\tPREFIX\t/keep\tHEADER_REMOVE\tX-Id",
        ]);
        let mut st = s.stage(OWNER, "api.example.com").unwrap();
        assert_eq!(st.feed(b"GET /v2/ads?x=1 HTTP/1.1\r\nHost: api.example.com\r\n\r\n").hits, vec![5]);
        let pass = st.feed(b"GET /v2/adsense HTTP/1.1\r\nHost: api.example.com\r\n\r\n");
        assert!(pass.local.is_empty() && pass.forwarded == 1);
        // Rule 6 could not compile and is gone; rule 7 still works.
        assert_eq!(st.feed(b"GET /keep HTTP/1.1\r\nX-Id: 1\r\n\r\n").hits, vec![7]);
        // Same pattern, other host: the URL is built from the flow's host, so it does not match.
        let mut other = s.stage(OWNER, "cdn.example.com").unwrap();
        assert!(other.feed(b"GET /v2/ads HTTP/1.1\r\n\r\n").local.is_empty());
    }

    #[test]
    fn unframeable_traffic_falls_back_to_pass_through() {
        let s = set(&["1\t0:com.example.app\tapi.example.com\t\tPREFIX\t/\tREJECT\t204"]);
        let mut ws = s.stage(OWNER, "api.example.com").unwrap();
        let o = ws.feed(b"GET /socket HTTP/1.1\r\nUpgrade: websocket\r\n\r\n\x81\x05hello");
        assert!(o.local.is_empty());
        assert!(o.forward.ends_with(b"\x81\x05hello"));
        assert_eq!(text(&ws.feed(b"GET / HTTP/1.1\r\n\r\n").forward), "GET / HTTP/1.1\r\n\r\n"); // raw from now on
        let mut junk = s.stage(OWNER, "api.example.com").unwrap();
        let big = vec![b'a'; MAX_HEAD + 10];
        assert_eq!(junk.feed(&big).forward.len(), big.len());
        let mut bad = s.stage(OWNER, "api.example.com").unwrap();
        assert_eq!(text(&bad.feed(b"\x00\x01 nonsense\r\n\r\n").forward), "\u{0}\u{1} nonsense\r\n\r\n");
    }
}
