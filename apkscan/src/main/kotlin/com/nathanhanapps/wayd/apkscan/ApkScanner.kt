package com.nathanhanapps.wayd.apkscan

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.nathanhanapps.wayd.core.db.ApkFact
import com.nathanhanapps.wayd.core.db.ApkScanDao
import com.nathanhanapps.wayd.core.db.AppInfo
import com.nathanhanapps.wayd.core.db.FlowDao
import com.nathanhanapps.wayd.core.model.ApkFactKind
import com.nathanhanapps.wayd.privilege.ShizukuClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

@Singleton
class ApkScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ApkScanDao,
    private val flows: FlowDao,
) {
    private val pm = context.packageManager
    private val shizuku by lazy { ShizukuClient(context) }

    /** ROMs may hide installed packages even with QUERY_ALL_PACKAGES. Never call this complete coverage. */
    suspend fun candidates(): List<String> = withContext(Dispatchers.IO) {
        (pm.getInstalledApplications(0).map { it.packageName } + flows.distinctPackages() + shizuku.installedPackages())
            .filter { it != "?" && !it.startsWith("uid:") }.distinct().sorted().take(5000)
    }

    /** Returns false for a cache hit. A transaction keeps cancellation from exposing half-written facts. */
    @Suppress("DEPRECATION")
    suspend fun scan(pkg: String, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        require(pkg.length in 3..255 && pkg.all { it.isLetterOrDigit() || it == '_' || it == '.' })
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS or
            PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_SIGNING_CERTIFICATES
        val info = pm.getPackageInfo(pkg, flags)
        val app = requireNotNull(info.applicationInfo)
        val paths = (listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()).distinct()
        val signature = "m5-v1:${info.longVersionCode}:${info.lastUpdateTime}:" + paths.joinToString(";") {
            val file = File(it)
            "${file.length()}:${file.lastModified()}"
        }
        if (!force && dao.facts(pkg).any { it.kind == ApkFactKind.COVERAGE && it.key == "cache" && it.value == signature }) return@withContext false
        val facts = ArrayList<ApkFact>()
        val limits = linkedSetOf<String>()
        fun add(kind: ApkFactKind, key: String, value: String? = null, source: String? = null) {
            if (facts.size >= 4090) { limits += "fact_cap"; return }
            facts += ApkFact(pkg = pkg, versionCode = info.longVersionCode, kind = kind,
                key = key.take(512), value = value?.take(4096), evidence = source?.take(512))
        }
        info.requestedPermissions.orEmpty().take(512).forEach { add(ApkFactKind.PERMISSION, it, source = "PackageManager") }
        info.receivers.orEmpty().take(512).forEach { add(ApkFactKind.RECEIVER, it.name, "exported=${it.exported}; permission=${it.permission.orEmpty()}", "PackageManager") }
        info.services.orEmpty().take(512).forEach { add(ApkFactKind.SERVICE, it.name, "exported=${it.exported}; permission=${it.permission.orEmpty()}", "PackageManager") }
        info.providers.orEmpty().take(512).forEach { add(ApkFactKind.PROVIDER, it.name, "exported=${it.exported}; authority=${it.authority.orEmpty()}", "PackageManager") }
        if (listOf(info.requestedPermissions?.size ?: 0, info.receivers?.size ?: 0, info.services?.size ?: 0, info.providers?.size ?: 0).any { it > 512 }) limits += "component_cap"
        add(ApkFactKind.MANIFEST, "usesCleartextTraffic.effectiveFlag", (app.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0).toString(), "PackageManager; NSC can override")
        info.sharedUserId?.let { add(ApkFactKind.MANIFEST, "sharedUserId", it, "PackageManager") }
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        val signerHashes = ArrayList<String>()
        val signerSubjects = ArrayList<String>()
        signers.take(16).forEach { signer ->
            val bytes = signer.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
            val subject = cert.subjectX500Principal.name.take(4096)
            signerHashes += hash
            signerSubjects += subject
            add(ApkFactKind.SIGNER, hash, subject, "PackageManager current APK signer; subject is self-asserted")
        }
        if (signers.isEmpty()) limits += "signer_unavailable"
        if (signers.size > 16) limits += "signer_cap"
        var remaining = 96L * 1024 * 1024
        var dexCount = 0
        var nscResource: Int? = null
        if (paths.size > 64) limits += "split_cap"
        paths.take(64).forEachIndexed { index, path ->
            coroutineContext.ensureActive()
            val source = if (index == 0) "base" else "split:$index"
            try {
                ZipFile(path).use { zip ->
                    zip.getEntry("AndroidManifest.xml")?.let { entry ->
                        try {
                            val bytes = zip.getInputStream(entry).use { it.readBounded(4 * 1024 * 1024) }
                            val elements = BinaryXml.read(bytes)
                            var receiver: String? = null
                            elements.forEach { element ->
                                if (!element.start) {
                                    if (element.name == "receiver") receiver = null
                                } else {
                                    if (element.name == "receiver") receiver = element.android("name")?.value
                                    if (element.name == "action" && receiver != null) {
                                        element.android("name")?.value?.let { add(ApkFactKind.RECEIVER, "$receiver:action", it, source) }
                                    }
                                    if (element.name == "application") {
                                        element.android("networkSecurityConfig")?.takeIf { it.type == 1 }?.let { nscResource = it.data }
                                        element.android("usesCleartextTraffic")?.let { add(ApkFactKind.MANIFEST, it.name, it.value, source) }
                                    }
                                }
                            }
                        } catch (_: Exception) { limits += "manifest_unreadable:$source" }
                    } ?: run { limits += "manifest_missing:$source" }
                    val entries = zip.entries()
                    var seen = 0
                    while (entries.hasMoreElements()) {
                        coroutineContext.ensureActive()
                        if (++seen > 50_000) { limits += "zip_entry_cap:$source"; break }
                        val entry = entries.nextElement()
                        val name = entry.name
                        if (name.length > 512) { limits += "entry_name_cap"; continue }
                        if (name.startsWith("lib/") && name.endsWith(".so")) {
                            add(ApkFactKind.NATIVE_LIB, name, source = source)
                            DexEvidence.protectors.forEach { (prefix, protector) ->
                                if (name.substringAfterLast('/').startsWith(prefix)) add(ApkFactKind.PACKER, protector, name, source)
                            }
                        }
                        if (!name.matches(Regex("classes([0-9]+)?\\.dex"))) continue
                        if (dexCount++ >= 64 || remaining <= 0) { limits += "dex_budget"; continue }
                        val cap = minOf(32L * 1024 * 1024, remaining).toInt()
                        if (entry.size > cap) { limits += "dex_size:$source/$name"; continue }
                        try {
                            val bytes = zip.getInputStream(entry).use { it.readBounded(cap) }
                            remaining -= bytes.size
                            if (bytes.size > cap) { limits += "dex_size:$source/$name"; continue }
                            val result = DexEvidence.read(bytes)
                            result.sdks.forEach { (prefix, sdk) -> add(ApkFactKind.SDK, sdk, prefix, "$source/$name:class_defs") }
                            result.endpoints.forEach { host -> add(ApkFactKind.ENDPOINT, host, source = "$source/$name:string_ids") }
                            if (result.reflection) add(ApkFactKind.MANIFEST, "reflection_types_present", "true", "$source/$name; no call-site proof")
                            if (result.limited) limits += "dex_string_or_endpoint_cap:$source/$name"
                        } catch (_: Exception) { limits += "dex_unreadable:$source/$name" }
                    }
                }
            } catch (_: java.io.IOException) { limits += "apk_unreadable:$source" }
        }
        if (dexCount == 0 && app.flags and ApplicationInfo.FLAG_HAS_CODE != 0) limits += "no_dex_odex_or_packed"
        nscResource?.let { id ->
            try {
                pm.getResourcesForApplication(app).getXml(id).use { xml ->
                    var nodes = 0
                    var event = xml.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (++nodes > 10_000) { limits += "nsc_node_cap"; break }
                        if (event == XmlPullParser.START_TAG) {
                            val attributes = (0 until minOf(xml.attributeCount, 32)).joinToString("; ") {
                                "${xml.getAttributeName(it).take(128)}=${xml.getAttributeValue(it).take(512)}"
                            }
                            add(ApkFactKind.NSC, "${xml.depth}:${xml.name}", attributes, "networkSecurityConfig:$nodes")
                        } else if (event == XmlPullParser.TEXT && !xml.isWhitespace) {
                            add(ApkFactKind.NSC, "${xml.depth}:text", xml.text.take(512), "networkSecurityConfig:$nodes")
                        }
                        event = xml.next()
                    }
                }
            } catch (_: Exception) { limits += "nsc_unreadable" }
        }
        // Detect an APK replacement while scanning; never cache a mixture of versions.
        check(pm.getPackageInfo(pkg, 0).lastUpdateTime == info.lastUpdateTime)
        val now = System.currentTimeMillis()
        val old = dao.app(pkg)
        val row = AppInfo(pkg, pm.getApplicationLabel(app).toString().take(256),
            app.flags and ApplicationInfo.FLAG_SYSTEM != 0, info.versionName?.take(256),
            signerHashes.joinToString("\n").ifEmpty { null }, signerSubjects.joinToString("\n").ifEmpty { null },
            runCatching {
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
                else pm.getInstallerPackageName(pkg)
            }.getOrNull(), old?.firstSeen ?: now, now)
        // Coverage and cache metadata must survive the evidence cap too.
        val metadata = listOf("cache" to signature, "dex_files_seen" to dexCount.toString()) + limits.take(100).map { "limit" to it }
        val coverage = metadata.map { (key, value) -> ApkFact(pkg = pkg, versionCode = info.longVersionCode,
            kind = ApkFactKind.COVERAGE, key = key, value = value, evidence = null) }
        dao.replace(row, facts.distinctBy { listOf(it.kind, it.key, it.value, it.evidence) } + coverage)
        true
    }
}
