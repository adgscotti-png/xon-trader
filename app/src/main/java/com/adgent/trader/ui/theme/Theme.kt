package com.adgent.trader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = AdgentViolet,
    secondary = AdgentMagenta,
    background = SurfaceDark,
    surface = SurfaceDark,
    // Ramp delle superfici AMOLED esplicita: le card si staccano dallo sfondo
    // con gradini di luminosità controllati, coerenti col brand.
    surfaceContainerLowest = SurfaceDark,
    surfaceContainerLow = Color(0xFF14172A),
    surfaceContainer = Color(0xFF191D32),
    surfaceContainerHigh = Color(0xFF1F243A),
    surfaceContainerHighest = Color(0xFF262C45),
    onBackground = androidx.compose.ui.graphics.Color.White,
    onSurface = androidx.compose.ui.graphics.Color.White,
    onSurfaceVariant = OnSurfaceDim,
)

private val LightColors = lightColorScheme(
    primary = AdgentViolet,
    secondary = AdgentMagenta,
)

@Composable
fun AdgentTraderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color (Material You) disattivato di default: il brand ADGENT è identitario.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
