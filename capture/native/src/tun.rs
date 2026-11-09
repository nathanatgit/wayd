// SPDX-License-Identifier: GPL-3.0-or-later
//! Async wrapper over the VpnService tun fd.

use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::pin::Pin;
use std::task::{ready, Context, Poll};

use tokio::io::unix::AsyncFd;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

pub struct TunDevice(AsyncFd<OwnedFd>);

impl TunDevice {
    /// Duplicates `fd` so Kotlin keeps ownership of the original, and switches it to non-blocking.
    /// Must be called from inside a Tokio runtime context.
    pub fn from_borrowed_fd(fd: i32) -> io::Result<Self> {
        // SAFETY: plain libc calls on a caller-provided fd; the dup is owned by `OwnedFd` below.
        let dup = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 3) };
        if dup < 0 {
            return Err(io::Error::last_os_error());
        }
        let owned = unsafe { OwnedFd::from_raw_fd(dup) };
        unsafe {
            let flags = libc::fcntl(owned.as_raw_fd(), libc::F_GETFL);
            if flags < 0 || libc::fcntl(owned.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK) < 0 {
                return Err(io::Error::last_os_error());
            }
        }
        // SAFETY: `owned` is a valid, open, non-blocking tun fd that nothing else registers with epoll.
        let afd = unsafe { AsyncFd::register(owned) }.map_err(|e| e.into_parts().1)?;
        Ok(TunDevice(afd))
    }
}

impl AsyncRead for TunDevice {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        loop {
            let mut guard = ready!(self.0.poll_read_ready(cx))?;
            let unfilled = buf.initialize_unfilled();
            let r = unsafe { libc::read(self.0.as_raw_fd(), unfilled.as_mut_ptr().cast(), unfilled.len()) };
            if r >= 0 {
                buf.advance(r as usize);
                return Poll::Ready(Ok(()));
            }
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::WouldBlock {
                guard.clear_ready();
                continue;
            }
            if err.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Poll::Ready(Err(err));
        }
    }
}

impl AsyncWrite for TunDevice {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        loop {
            let mut guard = ready!(self.0.poll_write_ready(cx))?;
            let r = unsafe { libc::write(self.0.as_raw_fd(), buf.as_ptr().cast(), buf.len()) };
            if r >= 0 {
                return Poll::Ready(Ok(r as usize));
            }
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::WouldBlock {
                guard.clear_ready();
                continue;
            }
            if err.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Poll::Ready(Err(err));
        }
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }
}
