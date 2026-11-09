package com.nathanhanapps.wayd.capture

/** JNI bridge to the Rust netstack (capture/native). One instance per recording session. */
class NativeNet {
    /** Starts the stack on [tunFd] (duplicated natively). Returns a handle, or 0 on failure. */
    external fun nativeStart(tunFd: Int, mtu: Int): Long

    /** Stops the stack. Call exactly once, after polling has stopped. */
    external fun nativeStop(handle: Long)

    /** Blocks up to [timeoutMs]; returns bytes written to [buf], 0 on timeout, -1 when stopped. */
    external fun nativePoll(handle: Long, buf: ByteArray, timeoutMs: Int): Int

    companion object {
        init {
            System.loadLibrary("wayd_net")
        }
    }
}
