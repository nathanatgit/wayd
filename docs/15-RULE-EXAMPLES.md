# Rule examples / 规则示例

These are illustrative, per-app rules. Only APP, exact HOST and exact IP blocking are implemented today. Create those from app history and inspect them in Controls. JSON import, regex predicates, local responses and response rewriting are **not implemented**; the examples below are a design vocabulary, not importable configuration. Example domains are placeholders, not a recommended blocklist.

以下示例均限定单个应用。目前仅支持整个应用、精确主机名、精确 IP 阻止，可从应用历史创建，在“管控”检查。JSON 导入、正则条件、本地回复与响应改写尚未实现，下面的 JSON 仅用于说明设计，不能直接导入。

## Blocking (available) / 阻止（已支持）

Example: select `org.example.app` in Apps, open a connection whose visible SNI/HTTP Host is `metrics.example.com`, and choose Block forever. The scope is this app AND this exact host; it does not include subdomains or other apps. Block this time lasts until WAYD VPN stops, independently of the Record traffic toggle. App/user/signer binding and fresh per-app consent are supplied by WAYD, never by this illustrative JSON.

```json
{
  "app": { "packageName": "org.example.app" },
  "lifetime": "persistent",
  "match": { "hostExact": "metrics.example.com" },
  "action": { "type": "BLOCK" }
}
```

Whole-app blocking is an explicit separate choice. An exact-IP block can affect multiple sites sharing that address. Unknown/shared ownership or hidden hostnames remain coverage gaps. All matching blocks win before future routing; a local-reply rule cannot override a block.

## Static local response (planned) / 本地固定回复（规划）

Return a small app-compatible JSON document without forwarding the matching HTTP request. This is the preferred initial customization primitive: exact host + method + path, fixed bytes, no scripts or per-request AI. The actual server API must accept the selected schema; an arbitrary empty object can break an app.

```json
{
  "app": { "packageName": "org.example.app" },
  "match": {
    "hostExact": "api.example.com",
    "method": "GET",
    "pathExact": "/v1/promotions"
  },
  "action": {
    "type": "LOCAL_RESPONSE",
    "status": 200,
    "headers": {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store"
    },
    "bodyUtf8": "{\"items\":[]}"
  }
}
```

The future engine must calculate byte framing itself. A 204 response has no body; do not attach JSON to it ([HTTP semantics](https://www.rfc-editor.org/rfc/rfc9110.html#name-204-no-content)). Plain HTTP needs a supported parser; HTTPS requires successful research interception and explicit per-app consent. Pinned/encrypted/unsupported protocols cannot be assumed editable. TLS setup may already contact the server even when the request is withheld. No measured battery savings are claimed.

## Replace a response (planned) / 替换响应（规划）

Same scoped matcher, but `REWRITE_RESPONSE` replaces a supported complete upstream response body with fixed JSON. Unlike a local response, the request has already reached the server. Initial implementation should accept only bounded supported JSON/UTF-8 responses, repair framing/encoding/cache validators, reject unsupported encodings/streams, and never edit retained original evidence. Prefer a whole small body template or bounded JSON-field operation over regex applied to arbitrary JSON or HTML. General JavaScript/HTML script injection is not proposed.

```json
{
  "app": { "packageName": "org.example.app" },
  "match": {
    "hostExact": "api.example.com",
    "method": "GET",
    "pathExact": "/v1/promotions"
  },
  "action": {
    "type": "REWRITE_RESPONSE",
    "bodyUtf8": "{\"items\":[]}",
    "contentType": "application/json; charset=utf-8"
  }
}
```

## Regex examples (planned) / 正则示例（规划）

Current search boxes are **literal text**: typing these patterns there does not enable regex or create a rule. Future regex must name a field and use bounded Rust-regex matching, compiled once per policy revision. Rust regex excludes lookaround and backreferences; limits on pattern and searched data remain necessary ([official syntax and guidance](https://docs.rs/regex/latest/regex/)).

| Target field | Pattern | Matches | Does not match |
|---|---|---|---|
| Normalized host | `^(ads|metrics)\.example\.com$` | `ads.example.com`, `metrics.example.com` | `badmetrics.example.com`, `ads.example.com.evil.test` |
| Normalized host | `^([a-z0-9-]+\.)*example\.com$` | `example.com`, `a.b.example.com` | `notexample.com` |
| Path only (without query) | `^/v[12]/(ads|promotions)(/.*)?$` | `/v1/ads`, `/v2/promotions/list` | `/v1/adsettings`, `/v3/ads` |

An illustrative future JSON predicate needs escaped backslashes:

```json
{
  "app": { "packageName": "org.example.app" },
  "match": {
    "hostExact": "api.example.com",
    "method": "GET",
    "pathRegex": "^/v[12]/(ads|promotions)(/.*)?$"
  },
  "action": { "type": "LOCAL_RESPONSE", "status": 200,
    "headers": { "Content-Type": "application/json" }, "bodyUtf8": "{\"items\":[]}" }
}
```

For a hostRegex in JSON, write `"hostRegex": "^(ads|metrics)\\.example\\.com$"`. Prefer exact host/path or a typed domain-suffix predicate when sufficient. Test positive and negative examples before activation; match only necessary fields, never the whole payload by default. Backup/import and Clash mapping follow [the control specification](13-BLOCKING.md); neither grants consent nor permits a route to bypass a local block.
