// SPDX-License-Identifier: GPL-3.0-or-later
//! TLS interception for the `research` flavor (compiled only with `--features mitm`).
//!
//! WAYD acts as a local middleman for apps the user chose: it terminates the app's TLS with a certificate signed by a
//! per-install local CA, opens a normal TLS connection to the real server, and copies the plaintext between them while
//! capturing HTTP/1.1 messages. Anything that fails (pinning, untrusted CA, non-HTTP) falls back to plain passthrough.

pub mod ca;
pub mod http1;
pub mod tls;

#[cfg(test)]
mod tls_tests;
