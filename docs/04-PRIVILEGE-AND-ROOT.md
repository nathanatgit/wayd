# 04 · Privilege levels, Shizuku and root

## Levels

```kotlin
enum class Privilege { NONE, ADB, ROOT }
```

- `ADB`: Shizuku running as shell (uid 2000).
- `ROOT`: Shizuku running as root (`Shizuku.getUid() == 0`), or libsu with Magisk / KernelSU / APatch.

`PrivilegeDetector` re-checks on app start, on Shizuku binder events, and before each privileged job.

## Shell abstraction

```kotlin
interface PrivilegedShell {
    val level: Privilege
    suspend fun exec(cmd: Command): ShellResult
}

sealed interface Command {
    val requires: Privilege
    // ADB
    data class DumpAppOps(val pkg: String?) : Command { override val requires = Privilege.ADB }
    data class DumpAlarms(val filter: String?) : Command { override val requires = Privilege.ADB }
    data class DumpNetstatsDetail(val uid: Int?) : Command { override val requires = Privilege.ADB }
    data class DumpPackage(val pkg: String) : Command { override val requires = Privilege.ADB }
    data class DisableUserApp(val pkg: String) : Command { override val requires = Privilege.ADB }
    data class InstallExisting(val pkg: String) : Command { override val requires = Privilege.ADB }
    // ROOT
    data class NflogChainUp(val group: Int) : Command { override val requires = Privilege.ROOT }
    object NflogChainDown : Command { override val requires = Privilege.ROOT }
    data class LoadBpf(val program: BpfProgram) : Command { override val requires = Privilege.ROOT }
    object UnloadAllBpf : Command { override val requires = Privilege.ROOT }
    data class BlockUid(val uid: Int) : Command { override val requires = Privilege.ROOT }
    data class UnblockUid(val uid: Int) : Command { override val requires = Privilege.ROOT }
}
```

**Rules**
- Commands are rendered to argv by a single `CommandRenderer` with strict validation (package names match `^[a-zA-Z0-9_.]+$`, UIDs are ints).
- No free-form shell strings anywhere in production code paths.
- The developer console (from AppDualZuku) sits in the Danger zone behind its own warning.
- The `ai` module has **no** dependency on `privilege`.

Reuse AppDualZuku's `ShellClient` / Shizuku UserService implementation inside `ShizukuShell`.

## Collectors (ADB)

| Collector | Command | Output → |
|---|---|---|
| AppOpsCollector | `dumpsys appops` (+ historical ops where available) | `PermEvent` |
| AlarmCollector | `dumpsys alarm` | `AlarmInfo` |
| NetstatsCollector | `dumpsys netstats detail` or `NetworkStatsManager` | per-UID history incl. before recording |
| PackageCollector | `dumpsys package <pkg>` | granted runtime/install perms, receivers |
| UsageCollector | `UsageStatsManager` (usage access) | foreground minutes |

Parsers must be tolerant: output format varies by Android version and OEM. Keep sample outputs from real devices in `collectors/src/test/resources/`.

## Root features (Danger zone, consent-gated)

| Feature | Approach | Notes |
|---|---|---|
| VPN-bypass detection | iptables/ip6tables chain `WAYD_LOG` in `OUTPUT` with `-m owner --uid-owner` + `NFLOG --nflog-group N`; read via netlink | Traffic seen by NFLOG but not by tun = bypass |
| TLS plaintext | eBPF uprobes on `SSL_write`/`SSL_read` (libssl / Conscrypt BoringSSL), keyed by pid→uid | Requires kernel BTF/uprobe support (most GKI 5.10+). Detect at runtime |
| TLS plaintext fallback | Frida gadget / LSPosed module hooking Conscrypt / OkHttp | Per-app opt-in |
| File access | fanotify or eBPF tracepoint on `openat` for sensitive paths (contacts DB, DCIM, clipboard service) | Aggregate, don't log every syscall |
| Firewall | iptables per-UID REJECT | Frees the VPN slot if user prefers |
| Deep APK | read `/data/dalvik-cache`, vdex/oat; DEX dump from memory for packed apps | |
| System CA | Magisk module; on Android 14+ must overlay Conscrypt APEX cacerts | MITM of system apps |

**Root plaintext capture uses the same per-app opt-in and consent as MITM** (see 08).

## Safety and cleanup

- Every root action has an inverse; a `RootStateJournal` records what was installed.
- On recording stop, app start after crash, and via "Restore network" button: run inverses for everything in the journal.
- Use only WAYD-owned chain names / map names; never flush other chains.
- If any step fails: roll back, drop to ADB level, inform the user.

## Coverage

### Implemented M5 package inventory

`Command.ListPackages` is a parameter-free allowlist entry for `pm list packages --user current`, exposed through the fixed `IPrivilegedService.listPackages` call. APK scanning reuses this only when Shizuku is already granted; it never opens a permission dialog automatically. Replies are capped at 600 KiB and parsed to at most 5,000 validated package names, including the special `android` package. No APK bytes, private directories, installation or root modification are accessed through this call. The UserService version is now 2 so an old binder cannot accidentally serve the new API. Direct PackageManager queries can still fail for packages hidden by a ROM; those remain unavailable in the scan counts.

Even with root, cellular baseband traffic (IMS/VoLTE, SMS, carrier provisioning, A-GPS over modem) is invisible. Always state this in the coverage panel.
