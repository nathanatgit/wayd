// SPDX-License-Identifier: GPL-3.0-or-later
//! WAYD userspace network stack.
//!
//! Reads raw IP packets from the VPN tun fd, terminates TCP/UDP with `ipstack`, relays each flow to
//! the real destination over normal sockets (the app itself is excluded from the VPN with
//! `addDisallowedApplication`, so these sockets bypass the tunnel) and reports flow events to Kotlin
//! through a polled binary queue. No payload is stored here; only bounded prefixes are reported.

pub mod events;
pub mod blocking;
pub mod hostlist;
#[cfg(unix)]
mod routing;

#[cfg(feature = "mitm")]
pub mod mitm;

#[cfg(any(unix, test))]
mod shared;
#[cfg(unix)]
mod engine;
#[cfg(any(unix, test))]
mod relay;
#[cfg(unix)]
mod tun;
