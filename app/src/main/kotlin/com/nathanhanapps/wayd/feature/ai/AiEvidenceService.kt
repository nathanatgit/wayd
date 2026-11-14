package com.nathanhanapps.wayd.feature.ai

import android.content.Context
import android.os.Build
import com.nathanhanapps.wayd.ai.AiRequest
import com.nathanhanapps.wayd.ai.EvidenceItem
import com.nathanhanapps.wayd.analysis.FindingText
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.FlowDao
import com.nathanhanapps.wayd.core.db.Message
import com.nathanhanapps.wayd.core.db.MessageDao
import com.nathanhanapps.wayd.core.db.PermEvent
import com.nathanhanapps.wayd.core.db.PermEventDao
import com.nathanhanapps.wayd.feature.analysis.AnalysisService
import com.nathanhanapps.wayd.feature.analysis.Windows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns one app's recorded activity in a window into a compact, citeable evidence set for the model: the connections behind the
 * rule findings, the busiest destinations, permission uses and (research build) decrypted messages. Rules keep deciding
 * severity; the model only gets their findings as context.
 */
@Singleton
class AiEvidenceService @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val analysis: AnalysisService,
    private val flows: FlowDao,
    private val perms: PermEventDao,
    private val messages: MessageDao,
) {
    /**
     * The language of the app's UI (per-app language if the user chose one, else the system's): `zh-CN` or `en`.
     * The AI answers in this language, so a Chinese UI never shows English analysis and vice versa.
     */
    fun uiLocale(): String {
        val appLocale = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { context.getSystemService(android.app.LocaleManager::class.java).applicationLocales }.getOrNull()?.takeIf { !it.isEmpty }?.get(0)
        } else null
        val locale = appLocale ?: context.resources.configuration.locales[0]
        return if (locale.language == "zh") "zh-CN" else "en"
    }

    suspend fun requestFor(pkg: String, label: String, windowKey: String, locale: String): AiRequest? = withContext(Dispatchers.IO) {
        val report = analysis.analyse(windowKey).apps[pkg] ?: return@withContext null
        val (start, end) = Windows.resolve(windowKey)

        val flowIds = (
            report.findings.flatMap { analysis.evidenceIds(it.evidenceJson) } +
                report.destinations.take(8).flatMap { it.flowIds.take(2) }
            ).distinct().take(MAX_FLOWS)
        val flowRows = if (flowIds.isEmpty()) emptyList() else flows.byIds(flowIds)
        val permRows = perms.forApp(pkg, start, end).sortedByDescending { it.ts }.take(MAX_PERMS)
        val messageRows = messages.forApp(pkg, start, end).take(MAX_MESSAGES)

        val evidence = flowRows.map { EvidenceItem("f:${it.id}", flowText(it)) } +
            permRows.map { EvidenceItem("p:${it.id}", permText(it)) } +
            messageRows.map { EvidenceItem("m:${it.id}", messageText(it)) }
        if (evidence.isEmpty()) return@withContext null

        AiRequest(
            subject = "$label ($pkg), window $windowKey",
            evidence = evidence,
            ruleFindings = report.findings.map { "${it.ruleId} [${it.severity}] ${FindingText.parse(it.title).args.joinToString(" ")}".trim() },
            locale = locale,
        )
    }

    /** Evidence made only of the messages the user picked in the inspector (plus their connections). */
    suspend fun requestForMessages(pkg: String, label: String, windowKey: String, locale: String, messageIds: Collection<Long>): AiRequest? =
        withContext(Dispatchers.IO) {
            val picked = messages.byIds(messageIds.toList().take(MAX_PICKED))
            if (picked.isEmpty()) return@withContext null
            val flowRows = flows.byIds(picked.map { it.flowId }.distinct().take(MAX_FLOWS))
            AiRequest(
                subject = "$label ($pkg), ${picked.size} selected decrypted message(s)",
                evidence = picked.map { EvidenceItem("m:${it.id}", messageText(it)) } + flowRows.map { EvidenceItem("f:${it.id}", flowText(it)) },
                locale = locale,
            )
        }

    private fun time(ms: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    private fun flowText(f: Flow) =
        "${f.proto} ${f.host ?: f.dstIp}:${f.dstPort} up=${f.bytesUp}B down=${f.bytesDown}B screen=${if (f.screenOn) "on" else "off"} " +
            "net=${f.netType} company=${f.company ?: "?"} category=${f.category ?: "?"} at=${time(f.startAt)}"

    private fun permText(p: PermEvent) =
        "permission-use op=${p.op} count=${p.count} context=${p.source} bucket=${time(p.ts)}..${time(p.bucketEnd)}; background=${p.background}; false does not establish foreground; current grant unknown"

    private fun messageText(m: Message): String {
        val body = m.body?.let { utf8OrNull(it) }?.take(BODY_CHARS)
        return "decrypted ${m.dir} ${m.method.orEmpty()} ${m.url.orEmpty()} status=${m.status ?: "-"} type=${m.contentType ?: "-"} " +
            "size=${m.bodySize}B" + (body?.let { " body: $it" } ?: "")
    }

    private fun utf8OrNull(b: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b.copyOf(minOf(b.size, BODY_CHARS * 3)))).toString()
    }.getOrNull()

    private companion object {
        const val MAX_FLOWS = 24
        const val MAX_PERMS = 10
        const val MAX_MESSAGES = 8
        const val MAX_PICKED = 16
        const val BODY_CHARS = 500
    }
}
