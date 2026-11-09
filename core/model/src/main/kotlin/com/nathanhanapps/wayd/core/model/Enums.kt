package com.nathanhanapps.wayd.core.model

enum class Privilege { NONE, ADB, ROOT }
enum class Proto { TCP, UDP }
enum class HostSource { DNS, SNI, HTTP_HOST, QUIC_SNI }
enum class NetType { WIFI, CELLULAR, OTHER }
enum class TrackerCategory { ADS, ANALYTICS, CRASH, PUSH, CDN, OS, CARRIER, FIRST_PARTY, UNKNOWN }
enum class CaptureSource { VPN, NFLOG, BYPASS }
enum class Dir { UP, DOWN }

/** Never upgrade a tier without a real source. */
enum class Tier { PLAINTEXT, DECRYPTED, METADATA }
enum class TierSource { HTTP, MITM, EBPF, FRIDA }
enum class ApkFactKind { PERMISSION, RECEIVER, SERVICE, SDK, ENDPOINT, NSC, NATIVE_LIB, PACKER }
enum class Severity { INFO, LOW, MEDIUM, HIGH }
enum class UserState { NEW, SEEN, IGNORED }
enum class BlockScope { APP, HOST, APP_HOST }

enum class DataType {
    DEVICE_ID, ADVERTISING_ID, ANDROID_ID, IMEI_SERIAL, PHONE_NUMBER,
    LOCATION_PRECISE, LOCATION_COARSE, WIFI_BSSID, APP_LIST, APP_USAGE,
    CONTACTS, SMS, CLIPBOARD, SCREEN_CONTENT, FILES_MEDIA, ACCOUNT,
    NETWORK_INFO, OTHER,
}

/** Typed evidence reference prefixes, e.g. `f:812`. */
enum class EvidenceKind(val prefix: Char) {
    FLOW('f'), MESSAGE('m'), DNS('d'), PERM_EVENT('p'), APK_FACT('a'), FINDING('x'),
}

data class EvidenceRef(val kind: EvidenceKind, val id: Long) {
    override fun toString(): String = "${kind.prefix}:$id"

    companion object {
        fun parse(s: String): EvidenceRef? {
            if (s.length < 3 || s[1] != ':') return null
            val kind = EvidenceKind.entries.firstOrNull { it.prefix == s[0] } ?: return null
            val id = s.substring(2).toLongOrNull() ?: return null
            return EvidenceRef(kind, id)
        }
    }
}
