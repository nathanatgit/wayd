# 08 · MITM decryption, consent and privacy statement

Applies to: MITM (CA-based) and root plaintext capture (eBPF/hooks). Both are "content capture" features.

## UX flow

1. Tools / Settings → **HTTPS decryption**. The page is directly visible, with a shared risk notice and two trust-method cards: manual user-CA installation and system-wide trust requiring root (D-058).
2. Toggle "Decrypt HTTPS content" → full-screen warning (cannot be dismissed by back/scrim tap).
3. Privacy statement screen (scroll to end required) → checkbox "I understand" → **Agree and continue** (destructive-styled) / **Cancel**.
4. CA generation → system dialog to install the user CA (or Magisk module on root).
5. **Per-app picker** — empty by default. Banking, payment, government, and password-manager apps are excluded and can't be added (list maintained in code).
6. Record `ConsentRecord(feature="MITM", statementVersion, acceptedAt)`.

Revoke at any time: "Disable and remove CA" is directly visible on the HTTPS decryption page when configured, and in the recording notification menu when MITM is active. The method cards are visible before consent, but certificate actions remain disabled until consent and CA creation succeed. Opening the page never installs a certificate, enables decryption or requests root.

## Warning text (draft, EN)

> **Decrypting HTTPS is risky.**
> WAYD will act as a middleman for the apps you choose so it can read their encrypted traffic. This may expose passwords, messages, payment details and other sensitive content — to WAYD on this phone only.
> - Most apps refuse user-installed certificates; those apps will be shown as "metadata only".
> - Some apps may stop working while this is on.
> - Anyone who obtains WAYD's certificate key could intercept your traffic. WAYD stores it encrypted and deletes it when you turn this off.
> - Never enable this on a phone you don't own or for people who haven't agreed.

## 警告文本（草稿，中文）

> **解密 HTTPS 存在风险。**
> 在干嘛 将作为你所选应用的"中间人"，以读取其加密流量。这可能会暴露密码、聊天内容、支付信息等敏感内容——这些内容只保存在本机的 在干嘛 中。
> - 大多数应用不信任用户安装的证书，这些应用只会显示"仅元数据"。
> - 开启期间部分应用可能无法正常使用。
> - 若他人获取 在干嘛 的证书私钥，可能拦截你的流量。私钥加密保存，关闭功能时会删除。
> - 请勿在不属于你的手机上、或在他人未同意的情况下开启。

## Privacy statement (draft outline)

1. What WAYD collects (traffic metadata, cleartext/decrypted content for chosen apps, permission usage, APK facts).
2. Where it is stored (this device only, encrypted; excluded from backup).
3. Distinguish local inspection copies from forwarded app traffic. No automatic uploads of captures, reports or AI analysis; explicit exports and user-initiated model/database downloads use user-chosen destinations. Clash routing (`clash` variant) sends selected apps' traffic to the configured proxy; explain operator visibility (see 14-OPTIONAL-ROUTING.md).
4. Retention defaults and how to change them.
5. How to export and how to wipe.
6. Third-party data: traffic may contain other people's information (messages); user is responsible for lawful use.
7. Contact and statement version/date.

Statement versions are integers; changing text → bump version → re-consent required.

## Implementation notes

- CA: generate per install (EC P-256 or RSA-2048), 1-year validity, name "WAYD Local CA – <device-random>". Private key encrypted with a Keystore-held key.
- Leaf certs generated on demand, cached in memory (LRU).
- On handshake failure → passthrough that host, add to `PinnedHostCache`, mark flows METADATA.
- Never MITM: hosts on an exclusion list (banking, OS update, Play/vendor store, WAYD's own downloads).
- Ships in the single build (D-048); inert until this consent is recorded.
- Turning off: remove leaf cache, delete CA key, prompt user to remove the installed CA (link to settings), remove Magisk module on root.

## Legal note (non-binding)

This is not legal advice. Review PIPL (个人信息保护法) and local rules before public distribution; keep all processing on-device and avoid collecting anything about the developer side.

## Decryption over routed flows

A separate Clash/FlClash VPN replaces WAYD's and is not supported. With Clash routing, local blocks gate forwarding, the routing transport carries the upstream stream, and WAYD performs server-authenticated TLS over it; TLS fallback keeps the original route and never switches a failed proxy to Direct.

Decrypted messages need: active decryption consent, the app selected, the app trusting the local CA, and Record traffic on to save them. A Proxied outcome, SNI or a working page is not proof: require `TLS_INTERCEPTED` plus MESSAGE events for request and response. The parser negotiates HTTP/1.1 over TCP; QUIC/HTTP3, pinning, rejected user CA, exclusions and IPv6 gaps stay visible as coverage limits. Native regression and live Google tests: [12-TESTING.md](12-TESTING.md).
