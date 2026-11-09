package com.nathanhanapps.wayd.ai

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** A place a model file can be downloaded from. Hugging Face is often unreachable from mainland China, so a mirror is offered. */
class ModelSource(val label: String, val url: String)

/** A `.litertlm` model WAYD knows about (sizes and SHA-256 are the published values of the files). */
class ModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Minimum total RAM in GiB for a comfortable experience. */
    val minRamGb: Double,
    val sources: List<ModelSource>,
)

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : ModelState
    data object Verifying : ModelState
    data class Ready(val path: String, val sizeBytes: Long) : ModelState
    data class Failed(val reason: String) : ModelState
}

object ModelCatalog {
    val E2B = ModelSpec(
        id = "gemma-4-E2B-it",
        displayName = "Gemma 4 E2B",
        fileName = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        minRamGb = 5.5,
        sources = listOf(
            ModelSource("Hugging Face", "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"),
            ModelSource("ModelScope", "https://www.modelscope.cn/models/litert-community/gemma-4-E2B-it-litert-lm/resolve/master/gemma-4-E2B-it.litertlm"),
        ),
    )
    val E4B = ModelSpec(
        id = "gemma-4-E4B-it",
        displayName = "Gemma 4 E4B",
        fileName = "gemma-4-E4B-it.litertlm",
        sizeBytes = 3_659_530_240,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        minRamGb = 7.5,
        sources = listOf(
            ModelSource("Hugging Face", "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"),
            ModelSource("ModelScope", "https://www.modelscope.cn/models/litert-community/gemma-4-E4B-it-litert-lm/resolve/master/gemma-4-E4B-it.litertlm"),
        ),
    )
    val all = listOf(E2B, E4B)

    /** The biggest model the device's RAM suits; null if even the small one would be a squeeze. */
    fun recommended(totalRamGb: Double): ModelSpec? = all.lastOrNull { totalRamGb >= it.minRamGb }
}

/**
 * Downloads, verifies and deletes model files in app-private storage. The only network use in WAYD besides the
 * relayed traffic: it happens when the user taps "Download", to the host they chose (AGENTS.md rule 1).
 */
@Singleton
class ModelManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir = File(context.filesDir, "models")
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val _states = MutableStateFlow(ModelCatalog.all.associate { it.id to initialState(it) })
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    fun totalRamGb(): Double {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    /** The model the engine should load: the user's choice if installed, else any installed one. */
    fun activeModel(): Pair<ModelSpec, String>? {
        val chosen = prefs.getString(KEY_ACTIVE, null)
        val ready = ModelCatalog.all.filter { _states.value[it.id] is ModelState.Ready }
        val spec = ready.firstOrNull { it.id == chosen } ?: ready.firstOrNull() ?: return null
        return spec to (_states.value[spec.id] as ModelState.Ready).path
    }

    fun setActive(spec: ModelSpec) {
        prefs.edit().putString(KEY_ACTIVE, spec.id).apply()
    }

    /** Custom mirror base URL entered by the user; replaces the catalog URL host+path for one model. */
    fun customUrl(spec: ModelSpec): String? = prefs.getString("url_" + spec.id, null)

    fun setCustomUrl(spec: ModelSpec, url: String?) {
        prefs.edit().apply { if (url.isNullOrBlank()) remove("url_" + spec.id) else putString("url_" + spec.id, url.trim()) }.apply()
    }

    fun delete(spec: ModelSpec) {
        File(dir, spec.fileName).delete()
        File(dir, spec.fileName + ".part").delete()
        set(spec, ModelState.NotInstalled)
    }

    /**
     * Downloads [spec] from [url], resuming a partial file, then verifies the SHA-256. Cancel by cancelling the caller's
     * coroutine; the partial file is kept so the next attempt resumes.
     */
    suspend fun download(spec: ModelSpec, url: String) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val part = File(dir, spec.fileName + ".part")
        val target = File(dir, spec.fileName)
        if (target.exists() && target.length() == spec.sizeBytes) {
            set(spec, ModelState.Ready(target.absolutePath, target.length()))
            return@withContext
        }
        val have = if (part.exists()) part.length() else 0L
        if (StatFs(dir.absolutePath).availableBytes < spec.sizeBytes - have + FREE_MARGIN) {
            set(spec, ModelState.Failed("not enough free storage"))
            return@withContext
        }
        try {
            fetch(spec, url, part)
            set(spec, ModelState.Verifying)
            val sum = sha256(part)
            if (!sum.equals(spec.sha256, ignoreCase = true)) {
                part.delete()
                set(spec, ModelState.Failed("checksum mismatch; the partial file was removed"))
                return@withContext
            }
            if (!part.renameTo(target)) error("could not move the file into place")
            set(spec, ModelState.Ready(target.absolutePath, target.length()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            set(spec, initialState(spec))
            throw e
        } catch (t: Throwable) {
            set(spec, ModelState.Failed(t.message ?: t.javaClass.simpleName))
        }
    }

    private suspend fun fetch(spec: ModelSpec, url: String, part: File) {
        var have = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WAYD-model-download")
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == 200 && have > 0) {
                // The server ignored the range request: start over.
                part.delete()
                have = 0
            } else if (code != 200 && code != 206) {
                error("server answered HTTP $code")
            }
            val total = spec.sizeBytes
            RandomAccessFile(part, "rw").use { out ->
                out.seek(have)
                val buf = ByteArray(256 * 1024)
                var done = have
                var lastReport = 0L
                conn.inputStream.use { input ->
                    while (coroutineContext.isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastReport > 300_000_000L) {
                            set(spec, ModelState.Downloading(done, total))
                            lastReport = now
                        }
                    }
                }
                if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                if (done != total) error("download ended early ($done of $total bytes)")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun initialState(spec: ModelSpec): ModelState {
        val f = File(dir, spec.fileName)
        if (f.exists() && f.length() == spec.sizeBytes) return ModelState.Ready(f.absolutePath, f.length())
        val part = File(dir, spec.fileName + ".part")
        return if (part.exists() && part.length() > 0) ModelState.Downloading(part.length(), spec.sizeBytes) else ModelState.NotInstalled
    }

    private fun set(spec: ModelSpec, state: ModelState) {
        _states.value = _states.value + (spec.id to state)
    }

    private companion object {
        const val KEY_ACTIVE = "active_model"
        const val FREE_MARGIN = 300L * 1024 * 1024
    }
}
