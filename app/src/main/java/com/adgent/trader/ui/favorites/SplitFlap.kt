package com.adgent.trader.ui.favorites

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

// Palette del tabellone aeroportuale (validato nella preview di 4.0.2).
internal val FlapBoard = Color(0xFF0C0E11)
internal val FlapCard = Color(0xFF121519)
internal val FlapBorder = Color(0xFF1D2127)
internal val FlapInk = Color(0xFFF4E9D2)   // digiti crema
internal val FlapAmber = Color(0xFFFFC46B) // header/live
internal val FlapDim = Color(0xFF8D94A0)   // simbolo (flight code)
internal val FlapUp = Color(0xFF7CF2A1)    // rialzo
internal val FlapDown = Color(0xFFFF7C7C)  // ribasso

// Modulo scura: gradiente grafite, se stesso con 1px nero e seam centrale.
private val FlapModuleGradient = Brush.verticalGradient(listOf(Color(0xFF262C34), Color(0xFF1A1F26)))

/**
 * Riga di moduli split-flap (tabellone aeroportuale): ogni carattere è una card
 * che "si capovolge" al cambio (prima la metà alta, poi la metà bassa). I moduli
 * in cui il carattere non cambia restano fermi. [fontSize] scala l'intero modulo
 * (larghezza per carattere + altezza 2.05em, come la preview).
 */
@Composable
fun SplitFlapRow(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
    color: Color = FlapInk,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        text.forEachIndexed { i, ch ->
            key(i) { SplitFlapDigit(char = ch, fontSize = fontSize, color = color) }
        }
    }
}

@Composable
internal fun SplitFlapDigit(
    char: Char,
    fontSize: TextUnit,
    color: Color,
) {
    val density = LocalDensity.current
    val moduleW = with(density) { (fontSize.value * flapCharWidth(char)).dp }
    val moduleH = with(density) { (fontSize.value * 2.05f).dp }
    val lineHeight = fontSize * 2.05f
    val cameraDist = with(density) { moduleH.toPx() * 3f }

    // Le due metà statiche si aggiornano in momenti diversi: l'alta quando la
    // card alta atterra, la bassa quando atterra la card bassa.
    var shownTop by remember { mutableStateOf(char) }
    var shownBottom by remember { mutableStateOf(char) }
    val topRot = remember { Animatable(-90f) }
    val botRot = remember { Animatable(90f) }

    LaunchedEffect(char) {
        if (char == shownTop) return@LaunchedEffect
        // Metà alta: card nuova ruota -90°→0° (cerniera in basso), poi si fissa.
        topRot.snapTo(-90f)
        topRot.animateTo(0f, tween(150, easing = FastOutSlowInEasing))
        shownTop = char
        topRot.snapTo(-90f)
        // Metà bassa: card nuova ruota 90°→0° (cerniera in alto), poi si fissa.
        botRot.snapTo(90f)
        botRot.animateTo(0f, tween(150, easing = FastOutSlowInEasing))
        shownBottom = char
        botRot.snapTo(90f)
    }

    Box(
        modifier = Modifier
            .width(moduleW)
            .height(moduleH)
            .background(FlapModuleGradient, RoundedCornerShape(4.dp))
            .border(1.dp, Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .clipToBounds(),
    ) {
        FlapLayer(text = shownTop.toString(), top = true, fontSize = fontSize, lineHeight = lineHeight, color = color)
        FlapLayer(text = shownBottom.toString(), top = false, fontSize = fontSize, lineHeight = lineHeight, color = color)
        FlapLayer(
            text = char.toString(), top = true, fontSize = fontSize, lineHeight = lineHeight, color = color,
            rotationX = topRot.value, transformOrigin = TransformOrigin(0.5f, 1f), cameraDistance = cameraDist,
        )
        FlapLayer(
            text = char.toString(), top = false, fontSize = fontSize, lineHeight = lineHeight, color = color,
            rotationX = botRot.value, transformOrigin = TransformOrigin(0.5f, 0f), cameraDistance = cameraDist,
        )
        // Seam orizzontale al centro del modulo (la fessura del tabellone).
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.Black.copy(alpha = 0.55f)),
        )
    }
}

/**
 * Metà (alta o bassa) di un modulo: testo che riempie l'intero modulo (line box
 * = altezza modulo, glifo centrato) e ritaglio alla metà voluta. Quando
 * [rotationX] è fornito la metà diventa una card rotante con cerniera al bordo
 * opposto (basso per l'alta, alto per la bassa).
 */
@Composable
private fun FlapLayer(
    text: String,
    top: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    rotationX: Float = 0f,
    transformOrigin: TransformOrigin = TransformOrigin(0.5f, 0.5f),
    cameraDistance: Float = 24f,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.rotationX = rotationX
                this.transformOrigin = transformOrigin
                this.cameraDistance = cameraDistance
                clip = true
                shape = if (top) TopHalfClip else BottomHalfClip
            },
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            lineHeight = lineHeight,
            textAlign = TextAlign.Center,
            color = color,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Larghezza del modulo per carattere (i digit sono più larghi dei segni). */
private fun flapCharWidth(char: Char): Float = when (char) {
    ' ' -> 0.30f
    '.', ',', '-', '+', '−', '%', '$', ':' -> 0.34f
    else -> 0.62f
}

private object TopHalfClip : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): androidx.compose.ui.graphics.Outline =
        androidx.compose.ui.graphics.Outline.Rectangle(Rect(0f, 0f, size.width, size.height / 2f))
}

private object BottomHalfClip : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): androidx.compose.ui.graphics.Outline =
        androidx.compose.ui.graphics.Outline.Rectangle(Rect(0f, size.height / 2f, size.width, size.height))
}
