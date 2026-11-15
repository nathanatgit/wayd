# rwprobe — test app for rewrite rules, block lists and the DNS stage

A one-screen Android app (`com.nathanhanapps.wayd.rwprobe`) that sends a fixed set of requests and prints what came back, so each WAYD rule gets a visible line. It is a standalone Gradle project, not part of the WAYD build, and it sends only constant marker values (`PROBE-IDFA-0000`, `PROBE-DEVICE-0000`).

## Build and install

```
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr"
export ANDROID_HOME=C:/Users/Nathan/AppData/Local/Android/Sdk
./gradlew -p tools/rwprobe assembleDebug
adb -s 320146880841 install -r tools/rwprobe/build/outputs/apk/debug/rwprobe-debug.apk
```

## Prepare WAYD (Nat does this on the phone)

1. WAYD's user CA installed, decryption on, and `com.nathanhanapps.wayd.rwprobe` added to the decrypted apps. The probe trusts user CAs.
2. Controls → Rewrite rules, all for package `com.nathanhanapps.wayd.rwprobe`, host `httpbin.org`:

| # | Method | Match | Pattern | Action | Argument |
|---|---|---|---|---|---|
| 1 | any | Contains | `idfa=` | Remove query parameter | `idfa` |
| 2 | any | Starts with | `/anything/strip` | Remove header | `X-Device-Id` |
| 3 | any | Starts with | `/anything/strip` | Set header | `User-Agent: generic` |
| 4 | POST | Starts with | `/anything/collect` | Answer locally | `200-json` |
| 5 | GET | Exact | `/anything/old` | Redirect | `307 https://httpbin.org/anything/new` |
| 6 | any | Regex | `^https://httpbin\.org/anything/v\d+/ads\b` | Answer locally | `204` |

3. Controls → Block lists: import a list that contains the "blocked" host shown in the probe (default `example.org`, chosen because it is normally reachable, so a failure can only come from the list; a one-line file `||example.org^` is enough) and enable it.
4. Start the WAYD VPN.

## Run

Open the probe and tap **Run probe**, or from a PC:

```
adb shell am start -n com.nathanhanapps.wayd.rwprobe/.MainActivity --ez run true
adb shell cat /sdcard/Android/data/com.nathanhanapps.wayd.rwprobe/files/result.txt
```

Optional extras: `--es echo https://<other echo server>/anything`, `--es blocked <host>`. The echo server must return the request (path, query, headers, body) in its response body.

## Reading the result

| Line | Meaning |
|---|---|
| `PASS` / `FAIL` | Must hold with or without rules: requests no rule names still reach the server, the connection stays usable after a local answer, a near-miss path is not matched. A `FAIL` means WAYD broke something. |
| `INFO … yes / no` | Whether a WAYD rule acted. All `no` without rules or without decryption; all `yes` when the six rules and the list are in place. |
| Step 9 timing | Compare the average with the block list off and on to see the hostname-wait cost. |

Expected with everything set up: steps 2, 3, 5, 6, 7, 8 show `INFO … yes`; steps 1, 4, 5 (second line) and 7 (near miss) show `PASS`. In WAYD, the rewrite rules' hit counts rise, the inspector shows the local answers marked `X-WAYD-Rewrite`, and the block-list counters show one blocked name.

## Limits

- Uses HTTP/1.1 through `HttpURLConnection`; it does not test QUIC.
- The default echo server is a public service (`httpbin.org`); if it is unreachable, point `echo` at any echo endpoint and adjust the rule host.
