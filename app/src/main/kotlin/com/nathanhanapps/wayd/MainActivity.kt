package com.nathanhanapps.wayd

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nathanhanapps.wayd.capture.CaptureController
import com.nathanhanapps.wayd.core.ui.WaydTheme
import com.nathanhanapps.wayd.navigation.WaydNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var capture: CaptureController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readDebugExtras(intent)
        enableEdgeToEdge()
        setContent {
            WaydTheme {
                WaydNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDebugExtras(intent)
    }

    /** Debug builds only: `--es exclude <cidr,...>` keeps the dev PC out of the VPN so wireless adb survives. */
    private fun readDebugExtras(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        intent?.getStringExtra("exclude")?.let { capture.debugExclude = it }
    }
}
