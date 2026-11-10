package com.nathanhanapps.wayd.capture

/** JNI bridge to the Rust netstack (capture/native). One instance per recording session. */
class NativeNet {
    external fun nativePolicyVersion(): Int

    /** Starts the stack on [tunFd] (duplicated natively). Returns a handle, or 0 on failure. */
    external fun nativeStart(tunFd: Int, mtu: Int, policy: String): Long

    external fun nativeSetPolicy(handle: Long, policy: String): Boolean

    external fun nativeFlowInfo(handle: Long, flowId: Long, value: String, host: Boolean)

    /** Stops the stack. Call exactly once, after polling has stopped. */
    external fun nativeStop(handle: Long)

    /** Blocks up to [timeoutMs]; returns bytes written to [buf], 0 on timeout, -1 when stopped. */
    external fun nativePoll(handle: Long, buf: ByteArray, timeoutMs: Int): Int

    // ---- TLS interception controls (research flavor; harmless stubs in the standard library) ----

    external fun nativeHasMitm(): Boolean

    /** Returns `certPem` + U+001F + `keyPem`, or "" on failure. */
    external fun nativeGenerateCa(commonName: String): String

    /** System-store file name (OpenSSL subject_hash_old) for [certPem], or "". */
    external fun nativeSubjectHash(certPem: String): String

    /** [excludedHosts] is a comma-separated list of host suffixes that are never intercepted. */
    external fun nativeConfigureMitm(handle: Long, certPem: String, keyPem: String, excludedHosts: String): Boolean

    external fun nativeDisableMitm(handle: Long)

    /** Answers the per-flow "may this flow be intercepted?" question. */
    external fun nativeDecide(handle: Long, flowId: Long, intercept: Boolean)

    companion object {
        init {
            System.loadLibrary("wayd_net")
        }
    }
}
