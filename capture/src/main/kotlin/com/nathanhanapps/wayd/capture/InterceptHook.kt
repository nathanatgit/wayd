package com.nathanhanapps.wayd.capture

import javax.inject.Inject
import javax.inject.Singleton

/** What the native engine needs to decrypt TLS: the local CA and the hosts that must never be touched. */
class MitmConfig(val certPem: String, val keyPem: String, val excludedHosts: List<String>)

/** Supplied by the research flavor only. Called on the capture start path and once per new flow. */
interface InterceptProvider {
    /** Blocking. Null when interception is off, not consented, or no CA exists. */
    fun config(): MitmConfig?

    fun gate(): InterceptGate
}

/**
 * The only seam between capture and TLS interception. The standard flavor never registers a provider,
 * so nothing here can switch interception on there.
 */
@Singleton
class InterceptHook @Inject constructor() {
    @Volatile var provider: InterceptProvider? = null
}
