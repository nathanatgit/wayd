package com.nathanhanapps.wayd.feature.extensions

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * The Clash screens exist only in the `clash` variant (app/src/clash). Shared code never links to them directly: it asks Android
 * whether anything answers [ACTION]. Features that depend on the extension (routing controls, the country panel, which needs
 * the GeoIP database the extension installs) are hidden when nothing does (always the case in `core`).
 */
object ClashExtension {
    const val ACTION = "com.nathanhanapps.wayd.action.CLASH_EXTENSION"

    fun intent(context: Context): Intent? {
        // Own package only (D-049): another WAYD build or an unrelated app declaring the action must not turn the Clash UI on.
        val probe = Intent(ACTION).addCategory(Intent.CATEGORY_DEFAULT).setPackage(context.packageName)
        val info = context.packageManager.resolveActivity(probe, 0)?.activityInfo ?: return null
        return Intent(ACTION).addCategory(Intent.CATEGORY_DEFAULT).setClassName(info.packageName, info.name)
    }

    fun isInstalled(context: Context): Boolean = intent(context) != null
}

/** Re-checked on every resume so a variant switch is reflected without restarting WAYD. */
@Composable
fun rememberClashInstalled(): Boolean {
    val context = LocalContext.current
    var installed by remember { mutableStateOf(ClashExtension.isInstalled(context)) }
    LifecycleResumeEffect(Unit) {
        installed = ClashExtension.isInstalled(context)
        onPauseOrDispose {}
    }
    return installed
}
