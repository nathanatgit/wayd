# To-do · Hostname coverage (P1)

Target (M1): hostname on ≥ 90% of measurable TCP connections. Last sample: 78–85%. Labels, attention, block lists and rewrite all key on the hostname, so this caps their value.

## What exists

- SNI and HTTP Host from the first ≤ 16 KiB of each TCP connection.
- DNS answers (UDP 53) map an address to the queried name, now with TTL (D-067).
- A name seen on one connection names later unnamed connections to the same address for 24 h, source `INFERRED`; seeded from recorded connections at VPN start (D-067).

## Tasks

| # | Task | Why | Notes |
|---|---|---|---|
| 1 | **Measure first.** Export a capture before and after D-067 and run `scripts/Measure-Capture.ps1`; break missing names down by protocol, port and owner. | Nobody knows which case dominates the missing 15–22%. | Record the numbers in the handoff. |
| 2 | Session-scoped coverage panel, with UID-only owners separated and inferred names shown as their own share. | The panel aggregates all stored flows and now mixes direct and inferred names. | UI + one DAO query. |
| 3 | QUIC Initial SNI. | QUIC shows no name of its own; today only the DNS stage and inferred names cover it. | Needs Initial-packet decryption (version salts, AES-GCM). New crypto code in the Rust relay: Opus-level task, ask Nat first. `HostSource.QUIC_SNI` already exists. |
| 4 | DNS over TCP (port 53). | Large answers and some resolvers use it. | Parse the 2-byte length prefix; reuse `DnsParser`. |
| 5 | Answers to non-first questions and CNAME chains. | Minor accuracy. | Check `DnsParser` output on real captures before changing it. |
| 6 | Several names on one address. | A CDN address can serve many names; the memory keeps the latest. | Keep the last few names per address and mark the result ambiguous instead of guessing; show "one of N names". |

## Not solvable here

- Encrypted DNS inside apps (DoH/DoT), ECH: no name is visible. State it in the coverage panel.
- Names for connections that never send client bytes.
