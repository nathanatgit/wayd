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

/** Only explicit UI download actions call this client. No URL or payload logging. */
object RoutingDownloads {
    suspend fun fetch(address: String, limit: Int = 8 * 1024 * 1024): ByteArray = withContext(Dispatchers.IO) {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https" && url.userInfo == null && url.host.isNotEmpty())
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                if (connection.responseCode in 300..399) {
                    url = URL(url, requireNotNull(connection.getHeaderField("Location")))
                } else {
                    require(connection.responseCode in 200..299)
                    require(connection.contentLengthLong <= limit)
                    return@withContext connection.inputStream.use { readBounded(it, limit) }
                }
            } finally { connection.disconnect() }
        }
        error("redirect_limit")
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
