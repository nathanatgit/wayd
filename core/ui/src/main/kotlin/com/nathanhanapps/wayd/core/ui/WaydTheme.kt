package com.nathanhanapps.wayd.core.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Placeholder brand seed (teal). Replace once Nat picks a brand colour.
private val WaydLightScheme = lightColorScheme(
    primary = Color(0xFF00696B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F2),
    onPrimaryContainer = Color(0xFF002021),
    secondary = Color(0xFF4A6364),
    tertiary = Color(0xFF4E5F7D),
    background = Color(0xFFF4FBFA),
    surface = Color(0xFFF4FBFA),
)

private val WaydDarkScheme = darkColorScheme(
    primary = Color(0xFF80D5D6),
    onPrimary = Color(0xFF003738),
    primaryContainer = Color(0xFF004F51),
    onPrimaryContainer = Color(0xFF9CF1F2),
    secondary = Color(0xFFB0CCCC),
    tertiary = Color(0xFFB6C7EA),
    background = Color(0xFF0E1415),
    surface = Color(0xFF0E1415),
)

/** App theme: Material 3 Expressive, dynamic colour on Android 12+ (docs/09). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WaydTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> WaydDarkScheme
        else -> WaydLightScheme
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
