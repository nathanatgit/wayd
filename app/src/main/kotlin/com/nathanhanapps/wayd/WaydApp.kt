package com.nathanhanapps.wayd

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WaydApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.nathanhanapps.wayd.research.ResearchFeatures.install(this)
    }
}
