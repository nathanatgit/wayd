// SPDX-License-Identifier: GPL-3.0-or-later
//! Private transport-only C ABI. No paths, captures, CA material or DB handles.
use std::collections::HashSet;
use std::ffi::c_void;
use std::io;
use std::net::SocketAddr;
use std::os::fd::{FromRawFd, OwnedFd};
use std::sync::{Arc, OnceLock};

type Create = unsafe extern "C" fn(*const u8, i32) -> u64;
type Capabilities = unsafe extern "C" fn(u64) -> i32;
type Destroy = unsafe extern "C" fn(u64);
type Reserve = unsafe extern "C" fn(u64) -> u64;
type Dial = unsafe extern "C" fn(u64, *const u8, i32, *mut i32, *mut i32) -> u64;
struct Api { create: Create, capabilities: Capabilities, destroy: Destroy, close: Destroy, reserve: Reserve, dial: Dial }

fn api() -> Option<&'static Api> {
    static API: OnceLock<Option<Api>> = OnceLock::new();
    API.get_or_init(|| unsafe {
        // Deliberately keep the module loaded for the process lifetime.
        let library = libc::dlopen(c"libwayd_route.so".as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL);
        if library.is_null() { return None; }
        let version = libc::dlsym(library, c"WaydRouteVersion".as_ptr());
        if version.is_null() || std::mem::transmute::<*mut c_void, unsafe extern "C" fn() -> i32>(version)() != 1 { return None; }
        let create = libc::dlsym(library, c"WaydRouteCreate".as_ptr());
        let capabilities = libc::dlsym(library, c"WaydRouteCapabilities".as_ptr());
        let destroy = libc::dlsym(library, c"WaydRouteDestroy".as_ptr());
        let close = libc::dlsym(library, c"WaydRouteClose".as_ptr());
        let dial = libc::dlsym(library, c"WaydRouteDial".as_ptr());
        let reserve = libc::dlsym(library, c"WaydRouteReserve".as_ptr());
        if [create, capabilities, destroy, close, dial, reserve].iter().any(|p| p.is_null()) { return None; }
        Some(Api { create: std::mem::transmute::<*mut c_void, Create>(create), capabilities: std::mem::transmute::<*mut c_void, Capabilities>(capabilities), destroy: std::mem::transmute::<*mut c_void, Destroy>(destroy), close: std::mem::transmute::<*mut c_void, Destroy>(close), reserve: std::mem::transmute::<*mut c_void, Reserve>(reserve), dial: std::mem::transmute::<*mut c_void, Dial>(dial) })
    }).as_ref()
}

pub struct Routing { id: u64, api: &'static Api, selection: crate::blocking::RouteSelection, host: bool }
impl Routing {
    pub fn new(profile: &[u8], owners: &str) -> io::Result<Arc<Self>> {
        if profile.is_empty() || profile.len() > 48 * 1024 * 1024 { return Err(io::ErrorKind::InvalidInput.into()); }
        let selection = crate::blocking::RouteSelection::parse(owners)?;
        let api = api().ok_or(io::ErrorKind::Unsupported)?;
        let id = unsafe { (api.create)(profile.as_ptr(), profile.len() as i32) };
        if id == 0 { return Err(io::ErrorKind::InvalidInput.into()); }
        let flags = unsafe { (api.capabilities)(id) };
        Ok(Arc::new(Self { id, api, selection, host: flags & 2 != 0 }))
    }
    pub fn selected(&self, owner: &str) -> bool { self.selection.selected(owner) }
    pub fn needs_host(&self) -> bool { self.host }
    pub async fn dial(self: &Arc<Self>, dst: SocketAddr, owner: String, host: String, udp: bool) -> Result<(OwnedFd, Lease, u8), u8> {
        let id = unsafe { (self.api.reserve)(self.id) };
        if id == 0 { return Err(4); }
        // Dropping this async future cancels the Go dial immediately, including
        // when spawn_blocking has not started yet. The worker never owns this lease.
        let lease = Lease { id, route: self.clone() };
        let route = self.clone();
        let result = tokio::task::spawn_blocking(move || {
            let metadata = format!("{}\n{}\n{}\n{}", if udp { "udp" } else { "tcp" }, dst, owner, host);
            let mut fd = -1; let mut outcome = 4;
            let opened = unsafe { (route.api.dial)(id, metadata.as_ptr(), metadata.len() as i32, &mut fd, &mut outcome) };
            if opened == 0 { return Err(if outcome == 3 { 3 } else { 4 }); }
            Ok((unsafe { OwnedFd::from_raw_fd(fd) }, outcome as u8))
        }).await.map_err(|_| 4)??;
        Ok((result.0, lease, result.1))
    }
}
impl Drop for Routing { fn drop(&mut self) { unsafe { (self.api.destroy)(self.id) } } }

pub struct Lease { id: u64, route: Arc<Routing> }
impl Drop for Lease { fn drop(&mut self) { unsafe { (self.route.api.close)(self.id) } } }

pub fn stream(fd: OwnedFd) -> io::Result<tokio::net::UnixStream> {
    let stream = std::os::unix::net::UnixStream::from(fd);
    stream.set_nonblocking(true)?; tokio::net::UnixStream::from_std(stream)
}
pub fn datagram(fd: OwnedFd) -> io::Result<tokio::net::UnixDatagram> {
    let socket = std::os::unix::net::UnixDatagram::from(fd);
    socket.set_nonblocking(true)?; tokio::net::UnixDatagram::from_std(socket)
}

pub fn command(profile: &[u8], request: &[u8]) -> Vec<u8> {
    if profile.len() > 48 * 1024 * 1024 || request.len() > 48 * 1024 * 1024 { return Vec::new(); }
    let Some(_) = api() else { return Vec::new(); };
    unsafe {
        let library = libc::dlopen(c"libwayd_route.so".as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL);
        if library.is_null() { return Vec::new(); }
        let call = libc::dlsym(library, c"WaydRouteCommand".as_ptr());
        let free = libc::dlsym(library, c"WaydRouteFree".as_ptr());
        if call.is_null() || free.is_null() { libc::dlclose(library); return Vec::new(); }
        let call = std::mem::transmute::<*mut c_void, unsafe extern "C" fn(*const u8,i32,*const u8,i32,*mut i32)->*mut u8>(call);
        let free = std::mem::transmute::<*mut c_void, unsafe extern "C" fn(*mut u8)>(free);
        let mut len=0;
        let data=call(profile.as_ptr(),profile.len() as i32,request.as_ptr(),request.len() as i32,&mut len);
        let result=if !data.is_null() && (0..=70*1024*1024).contains(&len) { std::slice::from_raw_parts(data,len as usize).to_vec() } else { Vec::new() };
        if !data.is_null(){free(data)}; libc::dlclose(library); result
    }
}

#[cfg(all(test, target_os = "android"))]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::time::{timeout, Duration};

    #[test]
    fn android_extension_commands_and_shared_geoip() {
        let inspected = command(b"rules: ['MATCH,DIRECT']", br#"{"action":"inspect"}"#);
        assert!(String::from_utf8(inspected).unwrap().contains("MATCH,DIRECT"));
        let invalid = command(b"x", br#"{"action":"geoip-load","database":"aW52YWxpZA=="}"#);
        assert!(String::from_utf8(invalid).unwrap().contains("invalid_geoip"));
        if let Ok(path) = std::env::var("WAYD_GEOIP_TEST_REQUEST") {
            let request = std::fs::read(path).unwrap();
            assert_eq!(command(b"x", &request), br#"{"ok":true}"#);
            assert_eq!(command(b"x", br#"{"action":"geoip-lookup","ip":"223.5.5.5"}"#), br#"{"country":"CN"}"#);
            assert_eq!(command(b"x", br#"{"action":"geoip-lookup","ip":"192.168.1.1"}"#), br#"{"country":""}"#);
            assert_eq!(command(b"x", br#"{"action":"geoip-lookup","ip":"1.1.1.1"}"#), br#"{"country":""}"#);
        }
    }

    #[tokio::test]
    async fn android_go_transport_roundtrips_tcp_and_udp() {
        let route = Routing::new(b"rules: ['MATCH,DIRECT']", "0:com.example.probe").unwrap();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let (fd, lease, kind) = route.dial(listener.local_addr().unwrap(), "0:com.example.probe".into(), "".into(), false).await.unwrap();
        assert_eq!(kind, 1);
        let mut client = stream(fd).unwrap();
        let (mut server, _) = listener.accept().await.unwrap();
        client.write_all(b"ping").await.unwrap();
        let mut b = [0; 4]; timeout(Duration::from_secs(2), server.read_exact(&mut b)).await.unwrap().unwrap();
        assert_eq!(&b, b"ping"); server.write_all(b"pong").await.unwrap();
        timeout(Duration::from_secs(2), client.read_exact(&mut b)).await.unwrap().unwrap(); assert_eq!(&b, b"pong");
        drop(client); drop(lease);
        assert_eq!(timeout(Duration::from_secs(2), server.read(&mut b)).await.unwrap().unwrap(), 0);

        let server = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let (fd, lease, kind) = route.dial(server.local_addr().unwrap(), "0:com.example.probe".into(), "".into(), true).await.unwrap();
        assert_eq!(kind, 1); let client = datagram(fd).unwrap();
        client.send(b"ping").await.unwrap();
        let (n, peer) = timeout(Duration::from_secs(2), server.recv_from(&mut b)).await.unwrap().unwrap(); assert_eq!(n, 4);
        server.send_to(b"pong", peer).await.unwrap();
        timeout(Duration::from_secs(2), client.recv(&mut b)).await.unwrap().unwrap(); assert_eq!(&b, b"pong");
        drop(client); drop(lease);
    }

    #[tokio::test]
    async fn android_mihomo_proxy_roundtrip_preserves_half_close() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let profile = format!("proxies:\n - {{name: test, type: http, server: 127.0.0.1, port: {}}}\nrules: ['MATCH,test']", listener.local_addr().unwrap().port());
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut header = Vec::new(); let mut b = [0u8; 1];
            while !header.ends_with(b"\r\n\r\n") { stream.read_exact(&mut b).await.unwrap(); header.push(b[0]); assert!(header.len() < 2048); }
            assert!(header.starts_with(b"CONNECT 192.0.2.1:80 "));
            stream.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await.unwrap();
            let mut body = Vec::new(); timeout(Duration::from_secs(2), stream.read_to_end(&mut body)).await.unwrap().unwrap();
            assert_eq!(body, b"ping"); stream.write_all(b"pong").await.unwrap();
        });
        let route = Routing::new(profile.as_bytes(), "0:com.example.probe").unwrap();
        let (fd, lease, kind) = route.dial("192.0.2.1:80".parse().unwrap(), "0:com.example.probe".into(), "".into(), false).await.unwrap();
        assert_eq!(kind, 2); let mut client = stream(fd).unwrap();
        client.write_all(b"ping").await.unwrap(); client.shutdown().await.unwrap();
        let mut body = Vec::new(); timeout(Duration::from_secs(3), client.read_to_end(&mut body)).await.unwrap().unwrap();
        assert_eq!(body, b"pong"); server.await.unwrap(); drop(lease);
    }

    #[tokio::test]
    async fn android_cancellation_closes_a_pending_proxy_handshake() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let profile = format!("proxies:\n - {{name: test, type: http, server: 127.0.0.1, port: {}}}\nrules: ['MATCH,test']", listener.local_addr().unwrap().port());
        let route = Routing::new(profile.as_bytes(), "0:com.example.probe").unwrap();
        let task = tokio::spawn(async move { route.dial("192.0.2.1:80".parse().unwrap(), "0:com.example.probe".into(), "".into(), false).await });
        let (mut server, _) = timeout(Duration::from_secs(2), listener.accept()).await.unwrap().unwrap();
        let mut b = [0u8; 1024]; let n = server.read(&mut b).await.unwrap(); assert!(n > 0);
        task.abort(); let _ = task.await;
        let n = timeout(Duration::from_secs(2), server.read(&mut b)).await.unwrap().unwrap(); assert_eq!(n, 0);
    }
}
