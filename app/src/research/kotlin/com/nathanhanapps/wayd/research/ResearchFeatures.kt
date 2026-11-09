package com.nathanhanapps.wayd.research

import android.app.Application
import androidx.compose.runtime.Composable
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ResearchEntryPoint {
    fun mitm(): MitmController
}

/** Research-flavor features. The `standard` flavor has a no-op twin of this object (D-006). */
object ResearchFeatures {
    const val AVAILABLE = true

    fun install(app: Application) {
        EntryPointAccessors.fromApplication(app, ResearchEntryPoint::class.java).mitm().install()
    }

    @Composable
    fun Settings() = DangerZone()
}
