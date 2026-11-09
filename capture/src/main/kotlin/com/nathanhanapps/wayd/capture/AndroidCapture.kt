package com.nathanhanapps.wayd.capture

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.nathanhanapps.wayd.core.model.NetType
import java.net.InetSocketAddress

/** Resolves connection owners with [ConnectivityManager.getConnectionOwnerUid] (allowed for the active VPN app). */
class AndroidUidResolver(context: Context) : UidResolver {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val pm = context.packageManager
    private val pkgCache = HashMap<Int, String>()

    override fun resolve(proto: Int, src: InetSocketAddress, dst: InetSocketAddress): Owner? {
        val uid = cm.getConnectionOwnerUid(proto, src, dst)
        if (uid == Process.INVALID_UID) return null
        val pkg = pkgCache.getOrPut(uid) {
            pm.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
        }
        return Owner(uid, pkg)
    }
}

class AndroidCaptureEnv(context: Context) : CaptureEnv {
    private val power = context.getSystemService(PowerManager::class.java)
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var cachedNet = NetType.OTHER
    private var cachedAt = -NET_TTL_MS

    override fun now() = System.currentTimeMillis()

    override fun screenOn() = power.isInteractive

    /** The VPN is the active network, so look at the underlying (non-VPN) ones; cached to stay cheap. */
    override fun netType(): NetType {
        val t = SystemClock.elapsedRealtime()
        if (t - cachedAt < NET_TTL_MS) return cachedNet
        cachedAt = t
        cachedNet = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .firstOrNull {
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            ?.let {
                when {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetType.WIFI
                    it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetType.CELLULAR
                    else -> NetType.OTHER
                }
            } ?: NetType.OTHER
        return cachedNet
    }

    private companion object {
        const val NET_TTL_MS = 5_000L
    }
}
