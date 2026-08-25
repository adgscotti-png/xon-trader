package com.adgent.trader.ui.theme

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.adgent.trader.data.AppStyle

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

// Stile Neon: navy scuro + anello/alone ciano (da dashboard style.jpeg). Il
// color scheme sostituisce automaticamente tutte le superfici (card, nav, chip).
private val NeonDarkColors = darkColorScheme(
    primary = NeonAccent,
    secondary = NeonAccent,
    background = NeonSurfaceDark,
    surface = NeonSurfaceDark,
    surfaceContainerLowest = NeonSurfaceDark,
    surfaceContainerLow = NeonSurfaceLow,
    surfaceContainer = NeonSurfaceMid,
    surfaceContainerHigh = NeonSurfaceHigh,
    surfaceContainerHighest = Color(0xFF162A52),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = NeonDim,
)

private val NeonLightColors = lightColorScheme(
    primary = NeonAccent,
    secondary = NeonAccent,
    background = NeonSurfaceLight,
    surface = NeonSurfaceLight,
    surfaceContainerLowest = NeonSurfaceLight,
    surfaceContainerLow = NeonCardLight,
    onBackground = NeonTextDark,
    onSurface = NeonTextDark,
    onSurfaceVariant = Color(0xFF51677F),
)

/** Stile visivo corrente, disponibile a tutta la composizione. */
val LocalAppStyle = staticCompositionLocalOf { AppStyle.CLASSIC }

/**
 * Contorno "neon": anello ciano 1dp + alone blu colorato (solo in stile NEON,
 * altrimenti no-op). Da applicare come primo modifier sulle card/box prezzi.
 */
@Composable
fun Modifier.neonCardFrame(shape: Shape): Modifier {
    if (LocalAppStyle.current != AppStyle.NEON) return this
    return this
        .shadow(8.dp, shape, clip = false, ambientColor = NeonGlow, spotColor = NeonGlow)
        .border(1.dp, NeonBorder, shape)
}

@Composable
fun AdgentTraderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appStyle: AppStyle = AppStyle.CLASSIC,
    // Dynamic color (Material You) disattivato di default: il brand ADGENT è identitario.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        appStyle == AppStyle.NEON && darkTheme -> NeonDarkColors
        appStyle == AppStyle.NEON -> NeonLightColors
        darkTheme -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(LocalAppStyle provides appStyle) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
