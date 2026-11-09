// SPDX-License-Identifier: GPL-3.0-or-later
//! Passive HTTP/1.x message capture over a plaintext byte stream.
//!
//! One [`Capture`] watches ONE direction (requests or responses). It never changes the bytes (the caller forwards them
//! unmodified); if anything looks wrong it switches to `Raw` and stops capturing, so a parser bug can never break a
//! connection. All input is hostile: heads are size-limited, bodies are captured only up to `BODY_CAP`.

use std::collections::VecDeque;

pub const BODY_CAP: usize = 128 * 1024;
const MAX_HEAD: usize = 32 * 1024;
const MAX_HEADERS: usize = 96;

/// A captured HTTP message (one direction). For responses `method`/`url` come from the paired request.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Message {
    pub response: bool,
    pub method: String,
    pub url: String,
    pub status: u16,
    /// Raw header lines, `Name: value\r\n` each.
    pub headers: String,
    pub body: Vec<u8>,
    /// Total body size seen (may exceed `body.len()` when truncated).
    pub body_total: u64,
    pub truncated: bool,
}

#[derive(Debug)]
enum State {
    Head,
    /// Fixed-length body: bytes still to read.
    Fixed(u64),
    Chunked(ChunkState),
    /// Response body delimited by connection close.
    UntilClose,
    /// Not HTTP, upgraded (WebSocket) or malformed: stop capturing.
    Raw,
}

#[derive(Debug)]
enum ChunkState {
    Size,
    Data(u64),
    DataEnd,
    Trailer,
}

pub struct Capture {
    response: bool,
    state: State,
    buf: Vec<u8>,
    cur: Option<Message>,
    /// Request line info waiting for its response (shared by the two directions through `Pairing`).
    out: VecDeque<Message>,
    /// For responses: method of the request being answered (HEAD has no body).
    pending_method: VecDeque<(String, String)>,
}

impl Capture {
    pub fn new(response: bool) -> Self {
        Capture { response, state: State::Head, buf: Vec::new(), cur: None, out: VecDeque::new(), pending_method: VecDeque::new() }
    }

    /// Provides the request (method, url) a response will answer; call when a request message completes.
    pub fn push_request_context(&mut self, method: String, url: String) {
        self.pending_method.push_back((method, url));
    }

    /// Completed messages since the last call.
    pub fn take(&mut self) -> Vec<Message> {
        self.out.drain(..).collect()
    }

    /// Flushes a body-until-close message when the connection closes.
    pub fn finish(&mut self) {
        if matches!(self.state, State::UntilClose) {
            self.complete();
        } else if let Some(m) = self.cur.take() {
            // connection ended mid-body: keep what we have, flagged as truncated
            let mut m = m;
            m.truncated = true;
            self.out.push_back(m);
        }
    }

    pub fn feed(&mut self, data: &[u8]) {
        if matches!(self.state, State::Raw) || data.is_empty() {
            return;
        }
        self.buf.extend_from_slice(data);
        loop {
            let progressed = match self.state {
                State::Raw => false,
                State::Head => self.parse_head(),
                State::Fixed(_) => self.read_fixed(),
                State::Chunked(_) => self.read_chunked(),
                State::UntilClose => self.read_until_close(),
            };
            if !progressed {
                break;
            }
        }
        if matches!(self.state, State::Head) && self.buf.len() > MAX_HEAD {
            self.go_raw();
        }
    }

    fn go_raw(&mut self) {
        self.state = State::Raw;
        self.buf = Vec::new();
    }

    fn parse_head(&mut self) -> bool {
        if self.buf.is_empty() {
            return false;
        }
        let mut headers = [httparse::EMPTY_HEADER; MAX_HEADERS];
        let (head_len, mut msg, chunked, content_len, upgrade) = if self.response {
            let mut r = httparse::Response::new(&mut headers);
            match r.parse(&self.buf) {
                Ok(httparse::Status::Complete(n)) => {
                    let (m, u) = self.pending_method.front().cloned().unwrap_or_default();
                    let h = collect(r.headers);
                    let st = r.code.unwrap_or(0);
                    (n, Message { response: true, method: m, url: u, status: st, headers: h.text, body: Vec::new(), body_total: 0, truncated: false }, h.chunked, h.content_length, h.upgrade || st == 101)
                }
                Ok(httparse::Status::Partial) => return false,
                Err(_) => {
                    self.go_raw();
                    return false;
                }
            }
        } else {
            let mut r = httparse::Request::new(&mut headers);
            match r.parse(&self.buf) {
                Ok(httparse::Status::Complete(n)) => {
                    let h = collect(r.headers);
                    let host = h.host.clone().unwrap_or_default();
                    let path = r.path.unwrap_or("").to_string();
                    let url = if path.starts_with("http://") || path.starts_with("https://") || host.is_empty() { path } else { format!("{host}{path}") };
                    (n, Message { response: false, method: r.method.unwrap_or("").to_string(), url, status: 0, headers: h.text, body: Vec::new(), body_total: 0, truncated: false }, h.chunked, h.content_length, h.upgrade)
                }
                Ok(httparse::Status::Partial) => return false,
                Err(_) => {
                    self.go_raw();
                    return false;
                }
            }
        };
        self.buf.drain(..head_len);

        if upgrade {
            // WebSocket / CONNECT-style upgrade: record the head, then stop parsing this connection.
            self.out.push_back(msg);
            self.pending_pop_if_response();
            self.go_raw();
            return false;
        }

        let no_body = self.response && {
            let head_method = self.pending_method.front().map(|p| p.0.as_str()).unwrap_or("");
            head_method.eq_ignore_ascii_case("HEAD") || msg.status == 204 || msg.status == 304 || (100..200).contains(&msg.status)
        };
        if no_body {
            self.out.push_back(msg);
            self.pending_pop_if_response();
            return !self.buf.is_empty();
        }
        if chunked {
            self.cur = Some(msg);
            self.state = State::Chunked(ChunkState::Size);
        } else if let Some(n) = content_len {
            if n == 0 {
                self.out.push_back(std::mem::take(&mut msg));
                self.pending_pop_if_response();
                return !self.buf.is_empty();
            }
            self.cur = Some(msg);
            self.state = State::Fixed(n);
        } else if self.response {
            self.cur = Some(msg);
            self.state = State::UntilClose;
        } else {
            self.out.push_back(msg);
            return !self.buf.is_empty();
        }
        true
    }

    fn pending_pop_if_response(&mut self) {
        if self.response {
            self.pending_method.pop_front();
        }
    }

    fn append_body(&mut self, data: &[u8]) {
        if let Some(m) = self.cur.as_mut() {
            m.body_total += data.len() as u64;
            let room = BODY_CAP.saturating_sub(m.body.len());
            if room > 0 {
                m.body.extend_from_slice(&data[..data.len().min(room)]);
            }
            if m.body_total as usize > BODY_CAP {
                m.truncated = true;
            }
        }
    }

    fn complete(&mut self) {
        if let Some(m) = self.cur.take() {
            self.out.push_back(m);
        }
        self.pending_pop_if_response();
        self.state = State::Head;
    }

    fn read_fixed(&mut self) -> bool {
        let State::Fixed(remaining) = self.state else { return false };
        if self.buf.is_empty() {
            return false;
        }
        let take = (remaining as usize).min(self.buf.len());
        let chunk: Vec<u8> = self.buf.drain(..take).collect();
        self.append_body(&chunk);
        let left = remaining - take as u64;
        if left == 0 {
            self.complete();
            true
        } else {
            self.state = State::Fixed(left);
            false
        }
    }

    fn read_until_close(&mut self) -> bool {
        if self.buf.is_empty() {
            return false;
        }
        let chunk = std::mem::take(&mut self.buf);
        self.append_body(&chunk);
        false
    }

    fn read_chunked(&mut self) -> bool {
        loop {
            let State::Chunked(ref cs) = self.state else { return false };
            match cs {
                ChunkState::Size => {
                    let Some(pos) = find_crlf(&self.buf) else {
                        if self.buf.len() > 1024 {
                            self.go_raw();
                        }
                        return false;
                    };
                    let line = String::from_utf8_lossy(&self.buf[..pos]).to_string();
                    let size_str = line.split(';').next().unwrap_or("").trim();
                    let Ok(size) = u64::from_str_radix(size_str, 16) else {
                        self.go_raw();
                        return false;
                    };
                    self.buf.drain(..pos + 2);
                    self.state = State::Chunked(if size == 0 { ChunkState::Trailer } else { ChunkState::Data(size) });
                }
                ChunkState::Data(n) => {
                    let n = *n;
                    if self.buf.is_empty() {
                        return false;
                    }
                    let take = (n as usize).min(self.buf.len());
                    let chunk: Vec<u8> = self.buf.drain(..take).collect();
                    self.append_body(&chunk);
                    let left = n - take as u64;
                    self.state = State::Chunked(if left == 0 { ChunkState::DataEnd } else { ChunkState::Data(left) });
                    if left > 0 {
                        return false;
                    }
                }
                ChunkState::DataEnd => {
                    if self.buf.len() < 2 {
                        return false;
                    }
                    if &self.buf[..2] != b"\r\n" {
                        self.go_raw();
                        return false;
                    }
                    self.buf.drain(..2);
                    self.state = State::Chunked(ChunkState::Size);
                }
                ChunkState::Trailer => {
                    // trailers end with an empty line
                    let Some(pos) = find_crlf(&self.buf) else {
                        if self.buf.len() > MAX_HEAD {
                            self.go_raw();
                        }
                        return false;
                    };
                    let empty = pos == 0;
                    self.buf.drain(..pos + 2);
                    if empty {
                        self.complete();
                        return !self.buf.is_empty();
                    }
                }
            }
        }
    }
}

struct Collected {
    text: String,
    host: Option<String>,
    chunked: bool,
    content_length: Option<u64>,
    upgrade: bool,
}

fn collect(headers: &[httparse::Header]) -> Collected {
    let mut c = Collected { text: String::new(), host: None, chunked: false, content_length: None, upgrade: false };
    for h in headers {
        let value = String::from_utf8_lossy(h.value).to_string();
        let lname = h.name.to_ascii_lowercase();
        match lname.as_str() {
            "host" => c.host = Some(value.trim().to_ascii_lowercase()),
            "transfer-encoding" if value.to_ascii_lowercase().contains("chunked") => c.chunked = true,
            "content-length" => c.content_length = value.trim().parse().ok(),
            "upgrade" => c.upgrade = true,
            _ => {}
        }
        c.text.push_str(h.name);
        c.text.push_str(": ");
        c.text.push_str(&value);
        c.text.push_str("\r\n");
    }
    c
}

fn find_crlf(b: &[u8]) -> Option<usize> {
    b.windows(2).position(|w| w == b"\r\n")
}

impl Default for Message {
    fn default() -> Self {
        Message { response: false, method: String::new(), url: String::new(), status: 0, headers: String::new(), body: Vec::new(), body_total: 0, truncated: false }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn feed_all(c: &mut Capture, parts: &[&[u8]]) -> Vec<Message> {
        for p in parts {
            c.feed(p);
        }
        c.take()
    }

    #[test]
    fn request_with_body_split_across_reads() {
        let mut c = Capture::new(false);
        let m = feed_all(&mut c, &[b"POST /v1/track?id=1 HTTP/1.1\r\nHost: Api.Example.com\r\nContent-Length: 11\r\n\r\nhello", b" world", b"GET /next HTTP/1.1\r\nHost: api.example.com\r\n\r\n"]);
        assert_eq!(m.len(), 2);
        assert_eq!(m[0].method, "POST");
        assert_eq!(m[0].url, "api.example.com/v1/track?id=1");
        assert_eq!(m[0].body, b"hello world");
        assert_eq!(m[1].url, "api.example.com/next");
        assert!(m[1].body.is_empty());
    }

    #[test]
    fn response_chunked_and_pairing() {
        let mut c = Capture::new(true);
        c.push_request_context("GET".into(), "x.example/a".into());
        let m = feed_all(&mut c, &[b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: text/plain\r\n\r\n5\r\nhello\r\n", b"6\r\n world\r\n0\r\n\r\n"]);
        assert_eq!(m.len(), 1);
        assert_eq!(m[0].status, 200);
        assert_eq!(m[0].url, "x.example/a");
        assert_eq!(m[0].body, b"hello world");
        assert!(m[0].headers.contains("Content-Type: text/plain"));
    }

    #[test]
    fn head_204_and_zero_length_have_no_body_wait() {
        let mut c = Capture::new(true);
        c.push_request_context("HEAD".into(), "h/".into());
        c.push_request_context("GET".into(), "g/".into());
        let m = feed_all(&mut c, &[b"HTTP/1.1 200 OK\r\nContent-Length: 500\r\n\r\n", b"HTTP/1.1 204 No Content\r\n\r\n"]);
        assert_eq!(m.len(), 2);
        assert_eq!(m[0].method, "HEAD");
        assert_eq!(m[1].status, 204);
    }

    #[test]
    fn body_is_capped_but_counted() {
        let mut c = Capture::new(true);
        c.push_request_context("GET".into(), "big/".into());
        let n = BODY_CAP + 50_000;
        let head = format!("HTTP/1.1 200 OK\r\nContent-Length: {n}\r\n\r\n");
        c.feed(head.as_bytes());
        c.feed(&vec![7u8; n]);
        let m = c.take();
        assert_eq!(m.len(), 1);
        assert_eq!(m[0].body.len(), BODY_CAP);
        assert_eq!(m[0].body_total, n as u64);
        assert!(m[0].truncated);
    }

    #[test]
    fn websocket_upgrade_stops_capture() {
        let mut c = Capture::new(true);
        c.push_request_context("GET".into(), "ws/".into());
        let m = feed_all(&mut c, &[b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n\x81\x05hello"]);
        assert_eq!(m.len(), 1);
        assert_eq!(m[0].status, 101);
        c.feed(b"more binary frames");
        assert!(c.take().is_empty());
    }

    #[test]
    fn garbage_and_oversized_heads_go_raw_without_panicking() {
        let mut c = Capture::new(false);
        c.feed(b"\x16\x03\x01\x02\x00 this is not http at all\r\n\r\n");
        assert!(c.take().is_empty());
        let mut c = Capture::new(false);
        c.feed(b"GET / HTTP/1.1\r\n");
        c.feed(&vec![b'a'; 40_000]);
        c.feed(b"\r\n\r\n");
        assert!(c.take().is_empty());
        let mut c = Capture::new(true);
        c.push_request_context("GET".into(), "t/".into());
        c.feed(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nZZZ\r\n");
        assert!(c.take().is_empty());
    }

    #[test]
    fn until_close_body_flushes_on_finish() {
        let mut c = Capture::new(true);
        c.push_request_context("GET".into(), "u/".into());
        c.feed(b"HTTP/1.0 200 OK\r\n\r\nstreamed data");
        assert!(c.take().is_empty());
        c.finish();
        let m = c.take();
        assert_eq!(m.len(), 1);
        assert_eq!(m[0].body, b"streamed data");
    }
}
