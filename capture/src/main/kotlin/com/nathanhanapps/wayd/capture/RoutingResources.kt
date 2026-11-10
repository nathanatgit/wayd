package com.nathanhanapps.wayd.capture

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A failed resource download with a stable, URL-free code (same vocabulary as the native `download` command). */
class RoutingDownloadException(val code: String) : Exception(code)

/** Only explicit UI download actions call this client. No URL or payload logging. */
object RoutingDownloads {
    /**
     * Direct downloads use Android's own network stack (system DNS, per-app network policy, platform trust store) rather
     * than the Go client. `readTimeout` acts as the stall watchdog: a transfer that delivers no bytes for 25 s fails.
     */
    suspend fun fetch(address: String, limit: Int = 8 * 1024 * 1024): ByteArray = withContext(Dispatchers.IO) {
        var url = URL(address)
        try {
            repeat(6) {
                if (url.protocol != "https" || url.userInfo != null || url.host.isEmpty()) throw RoutingDownloadException("resource_url")
                val connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 25_000
                connection.setRequestProperty("User-Agent", "WAYD-Clash/1")
                try {
                    val code = connection.responseCode
                    if (code in 300..399) {
                        url = URL(url, connection.getHeaderField("Location") ?: throw RoutingDownloadException("resource_http_$code"))
                    } else {
                        if (code != 200) throw RoutingDownloadException("resource_http_$code")
                        if (connection.contentLengthLong > limit) throw RoutingDownloadException("resource_too_large")
                        val body = connection.inputStream.use { readBounded(it, limit) }
                        if (body.isEmpty()) throw RoutingDownloadException("resource_empty")
                        return@withContext body
                    }
                } finally { connection.disconnect() }
            }
            throw RoutingDownloadException("resource_redirects")
        } catch (e: RoutingDownloadException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RoutingDownloadException(classify(e))
        }
    }

    private fun classify(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "resource_dns"
        is java.net.SocketTimeoutException -> "resource_timeout"
        is javax.net.ssl.SSLException -> "resource_tls"
        is java.net.ConnectException, is java.net.NoRouteToHostException -> "resource_connect"
        is IllegalArgumentException -> "resource_too_large" // readBounded's limit check
        else -> "resource_network"
    }
    fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

/** One GeoIP asset for both routing and WAYD; never exported with captures. */
@Singleton
class RoutingGeoIp @Inject constructor(@ApplicationContext private val context: Context) {
    private val file get() = AtomicFile(File(context.noBackupFilesDir, "routing-resources/country.mmdb"))
    private val metadata get() = AtomicFile(File(context.noBackupFilesDir, "routing-resources/country.json"))
    @Volatile private var loaded = false
    private val cache = object : LinkedHashMap<String, String>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 4096
    }
    suspend fun load() = withContext(Dispatchers.IO) {
        if (!loaded && file.baseFile.exists()) synchronized(this@RoutingGeoIp) {
            if (!loaded) {
                val bytes = file.openRead().use { RoutingDownloads.readBounded(it, 32 * 1024 * 1024) }
                loadNative(bytes)
                loaded = true
            }
        }
    }
    private fun loadNative(bytes: ByteArray) {
        val request = JSONObject().put("action", "geoip-load").put("database", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val result = JSONObject(String(NativeNet().nativeRoutingCommand(byteArrayOf(1), request.toString().toByteArray()), Charsets.UTF_8))
        check(result.optBoolean("ok"))
    }
    suspend fun download(url: String) = withContext(Dispatchers.IO) {
        val bytes = RoutingDownloads.fetch(url, 32 * 1024 * 1024)
        install(bytes, url)
    }
    suspend fun install(bytes: ByteArray, url: String) = withContext(Dispatchers.IO) {
        synchronized(this@RoutingGeoIp) {
            loadNative(bytes) // Validate before replacing last known good asset.
            file.baseFile.parentFile?.mkdirs()
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) } catch (e: Throwable) { file.failWrite(output); throw e }
            val value = JSONObject().put("source", url).put("updated", System.currentTimeMillis())
                .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val meta = metadata.startWrite()
            try { meta.write(value.toString().toByteArray()); metadata.finishWrite(meta) } catch (e: Throwable) { metadata.failWrite(meta); throw e }
            loaded = true
            cache.clear()
        }
    }
    fun status(): JSONObject = runCatching { JSONObject(metadata.openRead().bufferedReader().use { it.readText() }) }.getOrDefault(JSONObject())
    @Synchronized fun country(ip: String): String? {
        if (!loaded) return null
        cache[ip]?.let { return it.takeIf(String::isNotEmpty) }
        val result = runCatching {
            val request = JSONObject().put("action", "geoip-lookup").put("ip", ip)
            JSONObject(String(NativeNet().nativeRoutingCommand(byteArrayOf(1), request.toString().toByteArray()), Charsets.UTF_8)).optString("country")
        }.getOrDefault("")
        cache[ip] = result
        return result.takeIf(String::isNotEmpty)
    }
}
