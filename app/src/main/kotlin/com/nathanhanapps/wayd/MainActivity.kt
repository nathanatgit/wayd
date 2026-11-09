package com.nathanhanapps.wayd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nathanhanapps.wayd.core.ui.WaydTheme
import com.nathanhanapps.wayd.navigation.WaydNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WaydTheme {
                WaydNavHost()
            }
        }
    }
}
