// SPDX-License-Identifier: GPL-3.0-or-later
//! State shared by all flows of one engine: the optional MITM engine and per-flow interception decisions.
//!
//! Policy (which apps may be intercepted) lives in Kotlin because only Kotlin knows which app owns a flow. For each
//! new flow the relay registers a decision slot *before* announcing the flow, Kotlin answers through
//! `nativeDecide`, and the relay waits briefly for that answer. No answer (or "no") means plain passthrough.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use tokio::sync::oneshot;

pub struct Shared {
    #[cfg(feature = "mitm")]
    mitm: std::sync::RwLock<Option<Arc<crate::mitm::tls::Mitm>>>,
    decisions: Mutex<HashMap<u64, oneshot::Sender<bool>>>,
}

impl Shared {
    pub fn new() -> Arc<Self> {
        Arc::new(Shared {
            #[cfg(feature = "mitm")]
            mitm: std::sync::RwLock::new(None),
            decisions: Mutex::new(HashMap::new()),
        })
    }

    #[cfg(feature = "mitm")]
    pub fn mitm(&self) -> Option<Arc<crate::mitm::tls::Mitm>> {
        self.mitm.read().unwrap().clone()
    }

    #[cfg(feature = "mitm")]
    pub fn set_mitm(&self, m: Option<Arc<crate::mitm::tls::Mitm>>) {
        *self.mitm.write().unwrap() = m;
    }

    pub fn mitm_active(&self) -> bool {
        #[cfg(feature = "mitm")]
        {
            self.mitm.read().unwrap().is_some()
        }
        #[cfg(not(feature = "mitm"))]
        {
            false
        }
    }

    pub fn register(&self, id: u64) -> oneshot::Receiver<bool> {
        let (tx, rx) = oneshot::channel();
        self.decisions.lock().unwrap().insert(id, tx);
        rx
    }

    pub fn decide(&self, id: u64, intercept: bool) {
        if let Some(tx) = self.decisions.lock().unwrap().remove(&id) {
            let _ = tx.send(intercept);
        }
    }

    /// Drops an unanswered slot (timeout or flow end) so the map cannot grow.
    pub fn forget(&self, id: u64) {
        self.decisions.lock().unwrap().remove(&id);
    }
}
