package com.acoder.gallery.presentation.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The app's slider. Drop-in replacement for Material's Slider:
 *
 *     Slider(value, onValueChange, valueRange = a..b)
 *  -> AppSlider(value, onValueChange, a..b)
 *
 * A thick rounded track, a white thumb with a soft ring that grows while held, a value bubble above the thumb while
 * dragging, and optional magnetic [snaps] (shown as small dots). Tap the track to jump, or drag. Vertical scrolling in
 * a parent still works, because only horizontal drags are claimed.
 *
 * [format] turns the value into the bubble text. Default: the position in the range as 0..100.
 *
 * [trackBrush] (optional) paints the whole track with a brush instead of the usual "filled up to the thumb" look.
 * Use it for color pickers, e.g. a hue rainbow or a saturation ramp. The brush should be a horizontal gradient
 * with default bounds, like `Brush.horizontalGradient(listOf(a, b))`.
 */
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    format: ((Float) -> String)? = null,
    snaps: List<Float> = emptyList(),
    onValueChangeFinished: (() -> Unit)? = null,
    trackBrush: Brush? = null
) {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)

    val latest by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    var dragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }
    val thumbScale by animateFloatAsState(
        if (dragging) 1.2f else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "appSliderThumb"
    )

    val span = valueRange.endInclusive - valueRange.start
    val frac = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val label = (format ?: { v: Float -> "${(((v - valueRange.start) / span) * 100f).roundToInt()}" })(value)

    fun toValue(x: Float, w: Float, pad: Float): Float {
        val f = ((x - pad) / (w - 2f * pad)).coerceIn(0f, 1f)
        var v = valueRange.start + f * span
        snaps.firstOrNull { abs(it - v) < 0.03f * span }?.let { v = it }   // magnetic snap
        return v
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(valueRange, snaps) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pad = 14.dp.toPx()
                    dragging = true
                    try {
                        latest(toValue(down.position.x, size.width.toFloat(), pad))
                        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        if (slop != null) {
                            followPointer(slop.id) { latest(toValue(it.position.x, size.width.toFloat(), pad)) }
                        }
                    } finally {
                        dragging = false
                        finished?.invoke()
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = 14.dp.toPx()
            val track = 6.dp.toPx()
            val cy = size.height - 17.dp.toPx()
            val w = size.width - 2f * pad
            val r = CornerRadius(track / 2f)
            val x = pad + w * frac

            if (trackBrush != null) {
                // translate() so the gradient starts at the track's left edge, not at the canvas edge
                translate(left = pad, top = cy - track / 2f) {
                    drawRoundRect(trackBrush, size = Size(w, track), cornerRadius = r)
                }
            } else {
                drawRoundRect(trackColor, Offset(pad, cy - track / 2f), Size(w, track), r)
                if (frac > 0f) drawRoundRect(accent, Offset(pad, cy - track / 2f), Size(w * frac, track), r)

                snaps.forEach { s ->
                    val sx = pad + w * ((s - valueRange.start) / span).coerceIn(0f, 1f)
                    drawCircle(if (sx <= x) onAccent.copy(alpha = 0.7f) else trackColor, 1.8.dp.toPx(), Offset(sx, cy))
                }
            }

            val tr = 11.dp.toPx() * thumbScale
            if (dragging) drawCircle(accent.copy(alpha = 0.22f), tr + 9.dp.toPx(), Offset(x, cy))
            drawCircle(Color(0x40000000), tr + 2.dp.toPx(), Offset(x, cy + 1.dp.toPx()))
            drawCircle(Color.White, tr, Offset(x, cy))
            drawCircle(accent, tr * 0.42f, Offset(x, cy))
        }

        if (dragging) {
            Box(
                Modifier
                    .offset {
                        val bw = 56.dp.toPx()
                        val pad = 14.dp.toPx()
                        val x = pad + (widthPx - 2f * pad) * frac - bw / 2f
                        IntOffset(x.coerceIn(0f, (widthPx - bw).coerceAtLeast(0f)).roundToInt(), 0)
                    }
                    .width(56.dp).height(22.dp)
                    .clip(RoundedCornerShape(11.dp)).background(accent),
                contentAlignment = Alignment.Center
            ) { Text(label, color = onAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

/** Follows pointer [id] until it is lifted or lost, reporting every move. Consumes the events. */
private suspend fun AwaitPointerEventScope.followPointer(id: PointerId, onMove: (PointerInputChange) -> Unit) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id } ?: return
        if (!change.pressed) { change.consume(); return }
        onMove(change)
        change.consume()
    }
}