package com.nathanhanapps.wayd.research

import android.app.Application
import androidx.compose.runtime.Composable

/** The standard flavor has no TLS interception: this twin of the research object does nothing (D-006). */
object ResearchFeatures {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    fun install(app: Application) = Unit

    @Composable
    fun Settings() = Unit
}
