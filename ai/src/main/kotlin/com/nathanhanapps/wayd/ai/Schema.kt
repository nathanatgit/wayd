package com.nathanhanapps.wayd.ai

import com.nathanhanapps.wayd.core.model.DataType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Evidence the model may cite. [id] is a typed reference such as `f:812` or `m:33`. */
data class EvidenceItem(val id: String, val text: String)

enum class Verdict(val json: String) {
    BENIGN("benign"), EXPECTED_TELEMETRY("expected_telemetry"), EXCESSIVE("excessive"), LIKELY_LEAK("likely_leak"), UNKNOWN("unknown");

    companion object {
        fun parse(s: String) = entries.firstOrNull { it.json == s }
    }
}

enum class Certainty(val json: String) {
    SEEN("seen"), DECRYPTED("decrypted"), INFERRED("inferred");

    companion object {
        fun parse(s: String) = entries.firstOrNull { it.json == s }
    }
}

enum class Purpose(val json: String) {
    ANALYTICS("analytics"), ADS("ads"), CARRIER("carrier"), OS("os"), CRASH("crash"), PUSH("push"), FUNCTIONAL("functional"), UNKNOWN("unknown");

    companion object {
        fun parse(s: String) = entries.firstOrNull { it.json == s }
    }
}

enum class SuggestedAction(val json: String) {
    BLOCK_HOST("block_host"), BLOCK_APP("block_app"), REVOKE_PERMISSION("revoke_permission"), DISABLE_APP("disable_app"), IGNORE("ignore");

    companion object {
        fun parse(s: String) = entries.firstOrNull { it.json == s }
    }
}

data class AiDataType(val type: DataType, val evidence: List<String>, val certainty: Certainty, val note: String?)
data class AiDestination(val host: String, val company: String?, val purpose: Purpose, val evidence: List<String>)
data class AiTimelineNote(val ts: Long, val text: String, val evidence: List<String>)
data class AiAction(val action: SuggestedAction, val target: String, val reason: String)

/** A validated model answer. Every cited evidence id exists in the input; claims without evidence were removed. */
data class AiResult(
    val summary: String,
    val verdict: Verdict,
    val dataTypes: List<AiDataType>,
    val destinations: List<AiDestination>,
    val timeline: List<AiTimelineNote>,
    val actions: List<AiAction>,
    val openQuestions: List<String>,
    val confidence: Double,
)

sealed interface Validation {
    /** [stripped] counts claims dropped because they cited no evidence from the input. */
    data class Valid(val result: AiResult, val stripped: Int) : Validation

    data class Invalid(val errors: List<String>) : Validation
}

/**
 * Checks model output against the analysis schema v1 (docs/06): unknown enum values are rejected, cited evidence ids must be
 * part of the input, and claims that cite nothing valid are removed. The model's text never decides anything the UI trusts.
 */
object SchemaValidator {
    const val SCHEMA_VERSION = 1
    private const val MAX_SUMMARY = 400
    private const val MAX_TEXT = 300
    private const val MAX_ITEMS = 12

    /** Pulls the JSON object out of a reply that may be wrapped in prose or a Markdown code fence. */
    fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun validate(raw: String, allowedEvidence: Set<String>): Validation {
        val json = extractJson(raw) ?: return Validation.Invalid(listOf("no JSON object found"))
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return Validation.Invalid(listOf("invalid JSON: ${e.message?.take(80)}"))
        }
        val errors = ArrayList<String>()
        var stripped = 0

        if (root.optInt("schema_version", -1) != SCHEMA_VERSION) errors += "schema_version must be $SCHEMA_VERSION"
        val summary = root.optString("summary", "").trim()
        if (summary.isEmpty()) errors += "summary is required"
        val verdict = Verdict.parse(root.optString("verdict", ""))
        if (verdict == null) errors += "verdict must be one of " + Verdict.entries.joinToString("|") { it.json }
        val confidence = root.optDouble("confidence", Double.NaN)
        if (confidence.isNaN()) errors += "confidence must be a number between 0 and 1"

        fun evidenceOf(o: JSONObject, where: String): List<String>? {
            val arr = o.optJSONArray("evidence_ids") ?: run {
                errors += "$where: evidence_ids must be an array"
                return null
            }
            return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s in allowedEvidence } }.distinct()
        }

        val dataTypes = ArrayList<AiDataType>()
        forEachObject(root.optJSONArray("data_types")) { o ->
            val type = DataType.entries.firstOrNull { it.name == o.optString("type") }
            val certainty = Certainty.parse(o.optString("certainty"))
            if (type == null) errors += "data_types: unknown type '${o.optString("type").take(30)}'"
            if (certainty == null) errors += "data_types: certainty must be seen|decrypted|inferred"
            val ev = evidenceOf(o, "data_types")
            if (type != null && certainty != null && ev != null) {
                if (ev.isEmpty()) stripped++ else dataTypes += AiDataType(type, ev, certainty, o.optString("note").trim().take(MAX_TEXT).ifEmpty { null })
            }
        }

        val destinations = ArrayList<AiDestination>()
        forEachObject(root.optJSONArray("destinations")) { o ->
            val host = o.optString("host").trim()
            val purpose = Purpose.parse(o.optString("purpose_guess", "unknown"))
            if (host.isEmpty()) errors += "destinations: host is required"
            if (purpose == null) errors += "destinations: unknown purpose_guess"
            val ev = evidenceOf(o, "destinations")
            if (host.isNotEmpty() && purpose != null && ev != null) {
                if (ev.isEmpty()) stripped++ else destinations += AiDestination(host.take(120), o.optString("company").trim().take(60).ifEmpty { null }, purpose, ev)
            }
        }

        val timeline = ArrayList<AiTimelineNote>()
        forEachObject(root.optJSONArray("timeline_notes")) { o ->
            val text = o.optString("text").trim()
            val ev = evidenceOf(o, "timeline_notes")
            if (text.isEmpty()) errors += "timeline_notes: text is required"
            if (text.isNotEmpty() && ev != null) {
                if (ev.isEmpty()) stripped++ else timeline += AiTimelineNote(o.optLong("ts", 0L), text.take(MAX_TEXT), ev)
            }
        }

        val actions = ArrayList<AiAction>()
        forEachObject(root.optJSONArray("user_actions")) { o ->
            val action = SuggestedAction.parse(o.optString("action"))
            if (action == null) errors += "user_actions: unknown action '${o.optString("action").take(30)}'"
            else actions += AiAction(action, o.optString("target").trim().take(120), o.optString("reason").trim().take(MAX_TEXT))
        }

        val questions = root.optJSONArray("open_questions")?.let { a ->
            (0 until minOf(a.length(), 5)).map { a.optString(it).trim().take(MAX_TEXT) }.filter { it.isNotEmpty() }
        } ?: emptyList()

        if (errors.isNotEmpty()) return Validation.Invalid(errors.distinct().take(6))

        // A verdict nobody can check is not shown as a verdict.
        val backed = dataTypes.isNotEmpty() || destinations.isNotEmpty() || timeline.isNotEmpty()
        val finalVerdict = if (backed) verdict!! else Verdict.UNKNOWN
        return Validation.Valid(
            AiResult(
                summary = summary.take(MAX_SUMMARY),
                verdict = finalVerdict,
                dataTypes = dataTypes.take(MAX_ITEMS),
                destinations = destinations.take(MAX_ITEMS),
                timeline = timeline.take(MAX_ITEMS),
                actions = actions.take(MAX_ITEMS),
                openQuestions = questions,
                confidence = confidence.coerceIn(0.0, 1.0),
            ),
            stripped,
        )
    }

    private inline fun forEachObject(arr: JSONArray?, block: (JSONObject) -> Unit) {
        if (arr == null) return
        for (i in 0 until minOf(arr.length(), 40)) arr.optJSONObject(i)?.let(block)
    }
}
