package com.nathanhanapps.wayd.capture

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** `clash` variant: the shared capture code reaches the real routing store and GeoIP database through this (D-049). */
@Singleton
class ClashRoutingBridge @Inject constructor(
    private val settings: RoutingSettings,
    private val geoIp: RoutingGeoIp,
    private val blocking: BlockingRules,
    @ApplicationContext private val context: Context,
) : RoutingBridge {
    /** The snapshot the running VPN started with; routing settings only change while the VPN is stopped. */
    @Volatile private var active = RoutingSnapshot()

    override suspend fun prepare(): RoutingSnapshot = settings.prepare().also { active = it }
    override fun affected(pkg: String?): Boolean = settings.affected(pkg)
    override suspend fun refresh() { settings.refresh() }
    override fun country(ip: String): String? = geoIp.country(ip)

    override suspend fun connect(host: String, port: Int): RoutedStream? {
        val snapshot = active
        if (!blocking.state.value.running) return null
        val owner = "${Process.myUid() / 100_000}:${context.packageName}"
        if (!snapshot.selects(owner)) return null
        val request = JSONObject().put("action", "connect").put("host", host).put("port", port).put("owner", owner).toString().toByteArray()
        val reply = withContext(Dispatchers.IO) {
            runCatching { JSONObject(String(NativeNet().nativeRoutingCommand(snapshot.profile, request))) }.getOrNull()
        } ?: throw IOException("route_failed")
        reply.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException(it) }
        val fd = reply.optInt("fd", -1).takeIf { it >= 0 } ?: throw IOException("route_failed")
        return RoutedStream(ParcelFileDescriptor.adoptFd(fd), reply.optString("route"))
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ClashRoutingModule {
    @Binds abstract fun bridge(impl: ClashRoutingBridge): RoutingBridge
}
