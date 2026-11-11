package com.nathanhanapps.wayd.ai

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
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
    /** `owner/name` of the repository on Hugging Face and ModelScope, used to read the published SHA-256. */
    val repo: String,
)

/** Why a local model file does not match what is published online (D-052). */
enum class MismatchKind {
    /** The file is the version WAYD shipped with, but a different file is published now. */
    UPDATE_AVAILABLE,

    /** The file matches neither the published file nor the version WAYD knows: damaged, incomplete or edited. */
    CORRUPTED,
}

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : ModelState
    data object Verifying : ModelState

    /** Present but not checked since it last changed; checked before it is loaded. */
    data object Unchecked : ModelState
    data class Ready(val sizeBytes: Long, val ignored: Boolean = false) : ModelState
    data class Mismatch(val kind: MismatchKind) : ModelState
    data class Failed(val reason: String) : ModelState
}

/** Loading was refused because the file does not match the published one and the user has not chosen to ignore that. */
class ModelMismatchException(val kind: MismatchKind) : Exception("model_mismatch")

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
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
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
        repo = "litert-community/gemma-4-E4B-it-litert-lm",
    )
    val all = listOf(E2B, E4B)

    /** The biggest model the device's RAM suits; null if even the small one would be a squeeze. */
    fun recommended(totalRamGb: Double): ModelSpec? = all.lastOrNull { totalRamGb >= it.minRamGb }
}

/**
 * Downloads, verifies and deletes model files. They live in a folder the user picked once (normally Download/WAYD, through the
 * storage framework, so they survive reinstalls and can be copied in by hand), or in app storage when no folder is chosen.
 * Network use happens only when the user taps "Download" and, during a check, to read the published SHA-256 from the same
 * source (AGENTS.md rule 1, D-052).
 */
@Singleton
class ModelManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir = File(context.filesDir, "models")
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver
    private val checkLock = Mutex()
    private val _states = MutableStateFlow(ModelCatalog.all.associate { it.id to ModelState.NotInstalled as ModelState })
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    private val loadDir = File(context.cacheDir, "model-load")

    init { refresh() }

    fun totalRamGb(): Double {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    // ---- folder -------------------------------------------------------------------------------------------------------

    /** The folder tree the user granted, if the grant is still held. */
    fun folder(): Uri? {
        val s = prefs.getString(KEY_FOLDER, null) ?: return null
        val uri = Uri.parse(s)
        return uri.takeIf { u -> resolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission && it.isWritePermission } }
    }

    /** `Download/WAYD` style name of the granted folder, for display. */
    fun folderName(): String? = folder()?.let { DocumentsContract.getTreeDocumentId(it).substringAfter(':') }

    /** The picker's starting point: Download/WAYD on the primary volume. */
    fun suggestedFolder(): Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:" + Environment.DIRECTORY_DOWNLOADS + "/WAYD")

    fun setFolder(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.getString(KEY_FOLDER, null)?.let { old ->
            if (old != uri.toString()) runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
        refresh()
    }

    // ---- choice and sources -------------------------------------------------------------------------------------------

    /** The model the engine should load: the user's choice if present, else any present one. */
    fun activeModel(): ModelSpec? {
        val chosen = prefs.getString(KEY_ACTIVE, null)
        val present = ModelCatalog.all.filter { present(_states.value[it.id]) }
        return present.firstOrNull { it.id == chosen } ?: present.firstOrNull()
    }

    fun setActive(spec: ModelSpec) {
        prefs.edit().putString(KEY_ACTIVE, spec.id).apply()
    }

    private val _activeDownload = MutableStateFlow<String?>(null)

    /** Id of the model being downloaded right now (a partial file with no running download is "paused"). */
    val activeDownload: StateFlow<String?> = _activeDownload.asStateFlow()

    /** Which catalog source ([ModelSpec.sources] index) all downloads use; one choice for every model. */
    fun sourceIndex(): Int = prefs.getInt("source", 0).coerceAtLeast(0)

    fun setSourceIndex(index: Int) {
        prefs.edit().putInt("source", index).apply()
    }

    /** The address a download starts from: the model's own custom address if the user set one, else the chosen source. */
    fun downloadUrl(spec: ModelSpec): String = customUrl(spec) ?: spec.sources[sourceIndex().coerceAtMost(spec.sources.lastIndex)].url

    /** Custom address entered by the user for one model; replaces the catalog source. */
    fun customUrl(spec: ModelSpec): String? = prefs.getString("url_" + spec.id, null)

    fun setCustomUrl(spec: ModelSpec, url: String?) {
        prefs.edit().apply { if (url.isNullOrBlank()) remove("url_" + spec.id) else putString("url_" + spec.id, url.trim()) }.apply()
    }

    // ---- where the file is ---------------------------------------------------------------------------------------------

    /** A model file: in the user's folder (storage framework document) or in app storage. */
    private class Located(val uri: Uri?, val file: File?, val size: Long, val modified: Long) {
        /** Changes whenever the file is replaced or edited; a verified result is reused only while it stays the same. */
        val fingerprint: String get() = "${uri ?: file}|$size|$modified"
    }

    private class Doc(val uri: Uri, val name: String, val size: Long, val modified: Long)

    private fun children(tree: Uri): List<Doc> = runCatching {
        val parent = DocumentsContract.getTreeDocumentId(tree)
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        resolver.query(q, cols, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(Doc(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), c.getString(1) ?: "", c.getLong(2), c.getLong(3)))
            }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun inFolder(name: String): Doc? = folder()?.let { tree -> children(tree).firstOrNull { it.name == name } }

    /** The folder copy wins over an older app-storage copy. */
    private fun locate(spec: ModelSpec): Located? {
        inFolder(spec.fileName)?.let { return Located(it.uri, null, it.size, it.modified) }
        val f = File(dir, spec.fileName)
        return if (f.isFile) Located(null, f, f.length(), f.lastModified()) else null
    }

    private fun partSize(spec: ModelSpec): Long {
        if (folder() != null) return inFolder(spec.fileName + PART)?.size ?: 0
        return File(dir, spec.fileName + PART).let { if (it.exists()) it.length() else 0 }
    }

    /** Re-reads what is on disk without hashing anything; changed files become [ModelState.Unchecked]. */
    fun refresh() {
        _states.value = ModelCatalog.all.associate { spec -> spec.id to quickState(spec) }
    }

    private fun quickState(spec: ModelSpec): ModelState {
        val loc = locate(spec)
        if (loc == null) {
            val part = partSize(spec)
            return if (part > 0) ModelState.Downloading(part, spec.sizeBytes) else ModelState.NotInstalled
        }
        return when (loc.fingerprint) {
            prefs.getString(KEY_OK + spec.id, null) -> ModelState.Ready(loc.size)
            prefs.getString(KEY_IGNORED + spec.id, null)?.substringBeforeLast('#') -> ModelState.Ready(loc.size, ignored = true)
            else -> ModelState.Unchecked
        }
    }

    private fun present(s: ModelState?) = s is ModelState.Ready || s is ModelState.Unchecked

    // ---- checking ------------------------------------------------------------------------------------------------------

    /**
     * Hashes the file and compares it with the SHA-256 the chosen source publishes now (the pinned value if the source cannot
     * be reached or a custom address is used). Runs only when the file changed since its last check (D-052).
     */
    suspend fun check(spec: ModelSpec): ModelState = withContext(Dispatchers.IO) {
        checkLock.withLock {
            val loc = locate(spec) ?: return@withLock ModelState.NotInstalled.also { set(spec, it) }
            val quick = quickState(spec)
            if (quick is ModelState.Ready) return@withLock quick.also { set(spec, it) }
            set(spec, ModelState.Verifying)
            // A file that already failed and has not changed keeps its local hash; only the published value is read again.
            val seen = prefs.getString(KEY_SEEN + spec.id, null)?.takeIf { it.substringBeforeLast('#') == loc.fingerprint }?.substringAfterLast('#')
            val local = seen ?: try {
                openRead(loc).use { sha256(it) }
            } catch (t: Throwable) {
                return@withLock ModelState.Failed(t.message ?: t.javaClass.simpleName).also { set(spec, it) }
            }
            val published = onlineSha(spec) ?: spec.sha256
            val state = when {
                local.equals(published, ignoreCase = true) -> {
                    prefs.edit().putString(KEY_OK + spec.id, loc.fingerprint).putString(KEY_SEEN + spec.id, loc.fingerprint + "#" + local).remove(KEY_IGNORED + spec.id).apply()
                    ModelState.Ready(loc.size)
                }
                local.equals(spec.sha256, ignoreCase = true) -> ModelState.Mismatch(MismatchKind.UPDATE_AVAILABLE)
                else -> ModelState.Mismatch(MismatchKind.CORRUPTED)
            }
            if (state is ModelState.Mismatch) prefs.edit().putString(KEY_SEEN + spec.id, loc.fingerprint + "#" + local).remove(KEY_OK + spec.id).apply()
            set(spec, state)
            state
        }
    }

    /** Checks every present model that changed since its last check. */
    suspend fun checkChanged() {
        for (spec in ModelCatalog.all) if (_states.value[spec.id] is ModelState.Unchecked) check(spec)
    }

    /** The user chose to load this exact file despite the mismatch; a later change to the file asks again. */
    fun ignoreMismatch(spec: ModelSpec) {
        val seen = prefs.getString(KEY_SEEN + spec.id, null) ?: return
        val loc = locate(spec) ?: return
        if (seen.substringBeforeLast('#') != loc.fingerprint) return
        prefs.edit().putString(KEY_IGNORED + spec.id, seen).apply()
        set(spec, ModelState.Ready(loc.size, ignored = true))
    }

    /**
     * LiteRT-LM dispatches by filename extension and rejects `/proc/self/fd/N`. Stage folder models in private cache under a
     * `.litertlm` name, verifying the copied bytes against the accepted hash before publishing the path. Never copy an unresolved mismatch.
     */
    suspend fun loadPath(spec: ModelSpec): String = withContext(Dispatchers.IO) {
        // Versions before D-053 remembered the fingerprint but not the accepted hash of a successful check.
        val before = locate(spec)
        if (before?.uri != null && prefs.getString(KEY_SEEN + spec.id, null)?.substringBeforeLast('#') != before.fingerprint) {
            prefs.edit().remove(KEY_OK + spec.id).apply()
        }
        when (val s = check(spec)) {
            is ModelState.Ready -> Unit
            is ModelState.Mismatch -> throw ModelMismatchException(s.kind)
            else -> error("model not available")
        }
        val loc = locate(spec) ?: error("model not available")
        loc.file?.let { return@withContext it.absolutePath }
        val seen = prefs.getString(KEY_SEEN + spec.id, null) ?: error("model not available")
        check(seen.substringBeforeLast('#') == loc.fingerprint) { "model changed during check" }
        val job = coroutineContext
        ModelLoadCopy.prepare(loadDir, spec.fileName, loc.size, seen.substringAfterLast('#'), { openRead(loc) }) { job.ensureActive() }.absolutePath
    }

    private fun openRead(loc: Located): InputStream =
        loc.file?.inputStream() ?: ParcelFileDescriptor.AutoCloseInputStream(resolver.openFileDescriptor(loc.uri!!, "r") ?: error("cannot open model file"))

    /** SHA-256 the chosen source publishes for this file now, or null if it cannot be read (offline, custom address). */
    private fun onlineSha(spec: ModelSpec): String? {
        if (customUrl(spec) != null) return null
        val modelScope = spec.sources.getOrNull(sourceIndex())?.label == "ModelScope"
        val api = if (modelScope) "https://www.modelscope.cn/api/v1/models/${spec.repo}/repo/files?Revision=master&Recursive=true"
        else "https://huggingface.co/api/models/${spec.repo}/tree/main"
        return runCatching {
            val conn = (URL(api).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "WAYD-model-check")
            }
            try {
                if (conn.responseCode != 200) return null
                val body = conn.inputStream.use { it.readBytes().also { b -> require(b.size < MAX_LISTING) }.toString(Charsets.UTF_8) }
                if (modelScope) PublishedSha.modelScope(body, spec.fileName) else PublishedSha.huggingFace(body, spec.fileName)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    // ---- download and delete --------------------------------------------------------------------------------------------

    fun delete(spec: ModelSpec) {
        loadDir.listFiles()?.filter { it.name.endsWith(spec.fileName) || it.name.endsWith(spec.fileName + PART) }?.forEach { it.delete() }
        folder()?.let { _ ->
            listOf(spec.fileName, spec.fileName + PART).forEach { n -> inFolder(n)?.let { runCatching { DocumentsContract.deleteDocument(resolver, it.uri) } } }
        }
        File(dir, spec.fileName).delete()
        File(dir, spec.fileName + PART).delete()
        prefs.edit().remove(KEY_OK + spec.id).remove(KEY_IGNORED + spec.id).remove(KEY_SEEN + spec.id).apply()
        set(spec, ModelState.NotInstalled)
    }

    /**
     * Downloads [spec] from [url] into the chosen folder (or app storage), resuming a partial file, then verifies the SHA-256
     * against the pinned value. Cancel by cancelling the caller's coroutine; the partial file is kept so the next attempt resumes.
     */
    suspend fun download(spec: ModelSpec, url: String) = withContext(Dispatchers.IO) {
        // The caller releases the engine first.
        val tree = folder()
        if (tree == null) dir.mkdirs()
        val have = partSize(spec)
        val space = StatFs((if (tree != null) Environment.getExternalStorageDirectory() else dir).absolutePath).availableBytes
        if (space < spec.sizeBytes - have + FREE_MARGIN) {
            set(spec, ModelState.Failed("not enough free storage"))
            return@withContext
        }
        _activeDownload.value = spec.id
        try {
            // An older or damaged copy is replaced by this download.
            locate(spec)?.let { old -> old.uri?.let { DocumentsContract.deleteDocument(resolver, it) } ?: old.file?.delete() }
            val part = if (tree != null) PartDoc(tree, spec) else PartFile(File(dir, spec.fileName + PART))
            fetch(spec, url, part)
            set(spec, ModelState.Verifying)
            val sum = part.read().use { sha256(it) }
            if (!sum.equals(spec.sha256, ignoreCase = true)) {
                part.delete()
                set(spec, ModelState.Failed("checksum mismatch; the partial file was removed"))
                return@withContext
            }
            part.finish(spec.fileName)
            val loc = locate(spec) ?: error("could not move the file into place")
            prefs.edit().putString(KEY_OK + spec.id, loc.fingerprint).putString(KEY_SEEN + spec.id, loc.fingerprint + "#" + sum).remove(KEY_IGNORED + spec.id).apply()
            set(spec, ModelState.Ready(loc.size))
        } catch (e: kotlinx.coroutines.CancellationException) {
            set(spec, quickState(spec))
            throw e
        } catch (t: Throwable) {
            set(spec, ModelState.Failed(t.message ?: t.javaClass.simpleName))
        } finally {
            _activeDownload.value = null
        }
    }

    /** A partial download, in app storage or in the user's folder. */
    private interface Part {
        val size: Long
        fun append(): java.nio.channels.FileChannel
        fun read(): InputStream
        fun delete()
        fun finish(name: String)
    }

    private class PartFile(val file: File) : Part {
        override val size get() = if (file.exists()) file.length() else 0L
        override fun append() = FileOutputStream(file, true).channel
        override fun read() = file.inputStream()
        override fun delete() { file.delete() }
        override fun finish(name: String) { if (!file.renameTo(File(file.parentFile, name))) error("could not move the file into place") }
    }

    private inner class PartDoc(val tree: Uri, spec: ModelSpec) : Part {
        private var uri: Uri = inFolder(spec.fileName + PART)?.uri
            ?: DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), "application/octet-stream", spec.fileName + PART)
            ?: error("cannot create the file in the chosen folder")
        override val size get() = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L }.getOrDefault(0L)
        override fun append(): java.nio.channels.FileChannel {
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: error("cannot write to the chosen folder")
            return ParcelFileDescriptor.AutoCloseOutputStream(pfd).channel.also { it.position(it.size()) }
        }
        override fun read(): InputStream = ParcelFileDescriptor.AutoCloseInputStream(resolver.openFileDescriptor(uri, "r") ?: error("cannot open the file"))
        override fun delete() { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
        override fun finish(name: String) { uri = DocumentsContract.renameDocument(resolver, uri, name) ?: error("could not move the file into place") }
    }

    private suspend fun fetch(spec: ModelSpec, url: String, part: Part) {
        var have = part.size
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
                part.append().use { it.truncate(0) }
                have = 0
            } else if (code != 200 && code != 206) {
                error("server answered HTTP $code")
            }
            val total = spec.sizeBytes
            part.append().use { out ->
                out.position(have)
                val buf = java.nio.ByteBuffer.allocate(256 * 1024)
                var done = have
                var lastReport = 0L
                conn.inputStream.use { input ->
                    while (coroutineContext.isActive) {
                        val n = input.read(buf.array())
                        if (n < 0) break
                        (buf as java.nio.Buffer).clear()
                        (buf as java.nio.Buffer).limit(n)
                        while (buf.hasRemaining()) out.write(buf)
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

    private fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1024 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun set(spec: ModelSpec, state: ModelState) {
        _states.value = _states.value + (spec.id to state)
    }

    private companion object {
        const val KEY_ACTIVE = "active_model"
        const val KEY_FOLDER = "model_folder"
        const val KEY_OK = "model_ok_"
        const val KEY_SEEN = "model_seen_"
        const val KEY_IGNORED = "model_ignored_"
        const val PART = ".part"
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
        const val FREE_MARGIN = 300L * 1024 * 1024
        const val MAX_LISTING = 4 * 1024 * 1024
    }
}

/** Private, content-addressed runtime copies. A failed/interrupted copy is never offered to the engine. */
internal object ModelLoadCopy {
    fun prepare(dir: File, name: String, size: Long, sha: String, read: () -> InputStream, checkpoint: () -> Unit = {}): File {
        require(name.endsWith(".litertlm") && File(name).name == name)
        require(sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' })
        check(dir.isDirectory || dir.mkdirs()) { "cannot create model cache" }
        val target = File(dir, "$sha-$name")
        if (target.isFile && target.length() == size) return target
        // Discard stale copies of this model before allocating another multi-GB file.
        dir.listFiles()?.filter { it.name.endsWith(name) || it.name.endsWith("$name.part") }?.forEach { it.delete() }
        val part = File(dir, "$sha-$name.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            read().use { input ->
                FileOutputStream(part).use { output ->
                    val buf = ByteArray(1024 * 1024)
                    while (true) {
                        checkpoint()
                        val n = input.read(buf)
                        if (n < 0) break
                        copied += n
                        check(copied <= size) { "model changed during copy" }
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                    }
                    output.fd.sync()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(copied == size && actual == sha) { "model changed during copy" }
            checkpoint()
            check(part.renameTo(target)) { "cannot publish model cache" }
            return target
        } finally {
            part.delete()
        }
    }
}

/** Reads the published SHA-256 of one file from a repository listing (D-052). */
object PublishedSha {
    /** Hugging Face `GET /api/models/<repo>/tree/main`: `[{"path": ..., "lfs": {"oid": <sha256>}}]`. */
    fun huggingFace(json: String, fileName: String): String? {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("path") == fileName) return o.optJSONObject("lfs")?.optString("oid")?.takeIf(::isSha)
        }
        return null
    }

    /** ModelScope `GET /api/v1/models/<repo>/repo/files`: `{"Data": {"Files": [{"Name": ..., "Sha256": ...}]}}`. */
    fun modelScope(json: String, fileName: String): String? {
        val files = JSONObject(json).optJSONObject("Data")?.optJSONArray("Files") ?: return null
        for (i in 0 until files.length()) {
            val o = files.optJSONObject(i) ?: continue
            if (o.optString("Name") == fileName) return o.optString("Sha256").takeIf(::isSha)
        }
        return null
    }

    private fun isSha(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
