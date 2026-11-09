package com.nathanhanapps.wayd.feature.analysis

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.analysis.FindingText
import com.nathanhanapps.wayd.core.db.Finding

/** Localised one-line title of a finding, built from its rule id and stored arguments. Falls back to the English text. */
@Composable
fun findingTitle(f: Finding): String {
    val p = FindingText.parse(f.title)
    val a = p.args
    fun arg(i: Int) = a.getOrElse(i) { "" }
    fun num(i: Int) = a.getOrNull(i)?.toIntOrNull() ?: 0
    return when (f.ruleId) {
        "R-BG-UPLOAD" -> stringResource(R.string.ft_bg_upload, arg(0))
        "R-UP-RATIO" -> stringResource(R.string.ft_up_ratio, arg(0), arg(1))
        "R-BEACON" -> stringResource(R.string.ft_beacon, arg(0), arg(1), arg(2))
        "R-NEW-DOMAIN" -> stringResource(R.string.ft_new_domain, num(0))
        "R-DIRECT-IP" -> stringResource(R.string.ft_direct_ip, num(0), num(1))
        "R-OWN-DOH" -> stringResource(R.string.ft_own_doh, arg(0))
        "R-TRACKER-CONTACT" -> stringResource(
            when (arg(2)) {
                "ADS" -> R.string.ft_tracker_ads
                "ANALYTICS" -> R.string.ft_tracker_analytics
                else -> R.string.ft_tracker_crash
            },
            arg(0), arg(1),
        )
        "R-CLEARTEXT-HTTP" -> stringResource(R.string.ft_cleartext, arg(0), arg(1))
        "R-CLIPBOARD-BG" -> stringResource(R.string.ft_clipboard_bg, num(0))
        "R-SCREEN-CAPTURE" -> stringResource(R.string.ft_screen_capture, num(0))
        "R-ID-IN-REQUEST" -> stringResource(
            R.string.ft_id_in_request,
            com.nathanhanapps.wayd.core.model.DataType.entries.firstOrNull { it.name == arg(0) }
                ?.let { com.nathanhanapps.wayd.feature.ai.dataTypeLabel(it) } ?: arg(0),
            num(1), arg(2),
        )
        "R-LOC-THEN-UP" -> stringResource(R.string.ft_loc_then_up, arg(0), num(1))
        else -> p.fallback
    }
}
