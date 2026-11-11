package com.nathanhanapps.wayd.capture

import android.os.ParcelFileDescriptor

/** What the VPN service is handed to start the native engine; empty means "no routing" (everything Direct). */
data class RoutingSnapshot(val profile: ByteArray = byteArrayOf(), val owners: String = "") {
    /**
     * Whether access control routes [owner] (`user:package`), mirroring the native `RouteSelection`: a whitelist names the routed
     * owners; a first line `!exclude:<user>` routes every owner of that user except the named ones.
     */
    fun selects(owner: String): Boolean {
        if (profile.isEmpty() || !owner.matches(OWNER)) return false
        val lines = owners.lines().filter { it.isNotEmpty() }
        val user = lines.firstOrNull()?.removePrefix("!exclude:")?.takeIf { lines.first().startsWith("!exclude:") }
            ?: return owner in lines
        return owner.substringBefore(':') == user && owner !in lines.drop(1)
    }

    private companion object {
        val OWNER = Regex("""^\d{1,10}:[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
    }
}

/** A Clash-routed TCP stream for WAYD's own external AI call (D-050). The caller runs TLS over it and must close [fd]. */
class RoutedStream(val fd: ParcelFileDescriptor, val route: String)

/**
 * The only routing surface the shared capture code sees (D-049). The `clash` variant binds it to the real profile store and
 * GeoIP database; `core` binds a no-op that never reads or deletes anything under no_backup/routing/.
 */
interface RoutingBridge {
    /** Invalid enabled settings throw: VPN startup fails rather than silently going Direct. */
    suspend fun prepare(): RoutingSnapshot

    /** True when a changed package must stop the VPN (a routed whitelist app was replaced or removed). */
    fun affected(pkg: String?): Boolean

    /** Reloads the settings state for the UI. */
    suspend fun refresh()

    /** Country code of [ip] from the shared GeoIP database, or null. */
    fun country(ip: String): String?

    /**
     * Opens host:port through Clash for WAYD's own external AI call (D-050), or null when routing does not apply (VPN stopped,
     * routing off, or access control does not select WAYD itself) and the normal network is used. Throws [java.io.IOException]
     * when routing applies but a rule rejects the host or the node fails: the call then fails, never going Direct.
     */
    suspend fun connect(host: String, port: Int): RoutedStream?
}
