# 13 · Blocking and content control (M7 design)

Goal (Nat, 2026-11-09): the user has full control over what leaves the phone. Tap a connection in the history, block that host, that
domain, or just one URL path; support wildcards and regex; apps and content can both be blocked.

## What is technically possible

| Level | Needs | Works for |
|---|---|---|
| **App** (block all traffic of an app) | VPN only | every app |
| **Host / domain** (SNI, DNS name, IP) | VPN only | every TLS app and plain HTTP, even pinned apps. The host is known from the ClientHello SNI, the HTTP Host header or the DNS answer. |
| **Path / URL / header / body** (`baidu.com/s/`, query parameters, request bodies) | Decryption: the research build with the CA, and the CA must be trusted by that app (user CA for apps that honour it; system-wide toggle for the rest) | HTTP (always) and HTTPS of apps that accept the CA. Not possible for pinned apps or apps with their own protocol (e.g. WeChat MMTLS): for those only host / app level exists. |

QUIC (UDP 443) cannot be inspected; blocking an intercepted app's QUIC makes it fall back to TCP (already done) and a blocked host's QUIC is dropped.
DoH/DoT hide DNS names, so DNS-level blocking is best effort; SNI-level blocking still works.

## Rule model

`BlockRule` (entity exists: scope APP / HOST / APP_HOST) grows into:

- `app` (package, or any), `action` (BLOCK, ALLOW, LOG_ONLY), `enabled`, `createdAt`, hit counter and last hit.
- `pattern` with an explicit kind:
  - **domain wildcard** `*.baidu.com` = `baidu.com` and every subdomain; `baidu.com` = exactly that name; `*baidu*` anywhere.
  - **URL pattern** `baidu.com/s/` = host plus path prefix (`/s/*` is the same); `baidu.com/s?wd=*` matches query text. Needs decryption, so it is flagged "HTTPS needs decryption for this app" in the editor.
  - **regex** (`re:` prefix) applied to host or to the full `host/path?query` string. Compiled once, with a length and complexity cap (no catastrophic backtracking: use RE2-style `regex` crate in Rust).
  - **IP / CIDR** for connections without a name.
- Optional method and content-type filters, and "request" vs "response body contains" for decrypted traffic.
- ALLOW rules win over BLOCK rules of the same specificity, then the most specific pattern wins; ties go to BLOCK.

## Where it is enforced

Kotlin owns policy and attribution; the hot path stays native.

1. Kotlin compiles the rule list and pushes an immutable snapshot to the engine (`nativeSetRules`, version counter).
2. Host / app level: the relay evaluates after the first client bytes (SNI / Host) and before connecting upstream; a blocked flow is reset (TCP RST) and logged as BLOCKED, without ever reaching the network. UDP/53 queries for blocked names get NXDOMAIN. Per-app scoping uses the decision slot Kotlin already answers (it carries the app's rule set id).
3. Path / content level: inside the MITM HTTP/1.1 loop; a blocked request gets a synthetic `403` (or is dropped, user's choice) and is never sent upstream; a blocked response body can be replaced with an empty body. Logged with the rule that matched.

## UX

- **Connection history → tap a connection → "Block…"** sheet offering, for that connection: this exact host, the registrable domain with wildcard (`*.baidu.com`), the URL path (if decrypted), the whole app, or "edit pattern…" with a live preview of how many recorded connections it would have matched.
- **Rules screen**: list with hit counts, enable/disable, test box ("would this URL be blocked?"), import/export as JSON.
- **Safety**: confirmation for rules that match a lot (`*`, the app's only host), an always-visible "pause all blocking" switch in the recording notification, and automatic suggestion to turn a rule to LOG_ONLY first ("what would break?").
- Everything is local; rules never leave the device.

## Order of work

1. Rule model + matcher in pure Kotlin with a large unit-test table (wildcards, regex, precedence) shared as the spec for the Rust matcher.
2. Rust matcher + `nativeSetRules`, host/app-level blocking and logging (standard flavor too: it needs no decryption).
3. Connection-history actions and rules screen.
4. Path/content-level rules in the MITM loop (research flavor only).
