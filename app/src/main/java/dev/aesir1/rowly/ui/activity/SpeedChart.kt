package dev.aesir1.rowly.ui.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Speed against distance travelled.
 *
 * Hand-drawn rather than pulled from a charting library: the whole thing is one path, one gradient
 * and a crosshair, and a library would cost a dependency plus a theming API to fight.
 */
@Composable
fun SpeedChart(
    samples: List<SpeedSample>,
    modifier: Modifier = Modifier,
) {
    if (samples.size < 2) return

    val line = MaterialTheme.colorScheme.primary
    val crosshair = MaterialTheme.colorScheme.secondary
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = axis)

    var selected by remember { mutableStateOf<Int?>(null) }

    val minDistance = samples.first().distanceKm
    val maxDistance = samples.last().distanceKm
    val distanceSpan = (maxDistance - minDistance).takeIf { it > 1e-9 } ?: 1.0
    val maxSpeed = samples.maxOf { it.speedKmh }
    val minSpeed = samples.minOf { it.speedKmh }
    // A little headroom either side so the trace never touches the frame.
    val top = maxSpeed + (maxSpeed - minSpeed).coerceAtLeast(1.0) * 0.1
    val bottom = (minSpeed - (maxSpeed - minSpeed).coerceAtLeast(1.0) * 0.1).coerceAtLeast(0.0)
    val speedSpan = (top - bottom).takeIf { it > 1e-9 } ?: 1.0

    Column(modifier) {
        val readout = selected?.let { samples[it] }
        Text(
            text = if (readout != null) {
                String.format(
                    Locale.getDefault(),
                    "%.2f km  -  %.1f km/h",
                    readout.distanceKm,
                    readout.speedKmh,
                )
            } else {
                String.format(Locale.getDefault(), "%.1f - %.1f km/h", minSpeed, maxSpeed)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pointerInput(samples) {
                    // The crosshair stays put after the finger lifts: the point of dragging is to
                    // read a value off the chart, and clearing it on release hides the answer.
                    detectDragGestures { change, _ ->
                        selected = nearestIndex(change.position.x, size.width, samples.size)
                    }
                }
                .pointerInput(samples) {
                    detectTapGestures { offset ->
                        selected = nearestIndex(offset.x, size.width, samples.size)
                    }
                },
        ) {
            val plotBottom = size.height - 18.dp.toPx()
            fun xOf(index: Int): Float =
                ((samples[index].distanceKm - minDistance) / distanceSpan).toFloat() * size.width

            fun yOf(index: Int): Float =
                plotBottom - ((samples[index].speedKmh - bottom) / speedSpan).toFloat() * plotBottom

            val path = Path().apply {
                moveTo(xOf(0), yOf(0))
                for (i in 1 until samples.size) lineTo(xOf(i), yOf(i))
            }
            val filled = Path().apply {
                addPath(path)
                lineTo(xOf(samples.lastIndex), plotBottom)
                lineTo(xOf(0), plotBottom)
                close()
            }

            drawPath(
                path = filled,
                brush = Brush.verticalGradient(
                    listOf(line.copy(alpha = 0.35f), line.copy(alpha = 0f)),
                    endY = plotBottom,
                ),
            )
            drawPath(path, color = line, style = Stroke(width = 2.dp.toPx()))
            drawLine(
                color = axis.copy(alpha = 0.4f),
                start = Offset(0f, plotBottom),
                end = Offset(size.width, plotBottom),
                strokeWidth = 1.dp.toPx(),
            )

            selected?.let { index ->
                val x = xOf(index)
                drawLine(
                    color = crosshair,
                    start = Offset(x, 0f),
                    end = Offset(x, plotBottom),
                    strokeWidth = 1.dp.toPx(),
                )
                drawCircle(color = crosshair, radius = 4.dp.toPx(), center = Offset(x, yOf(index)))
            }

            drawText(
                measurer,
                String.format(Locale.getDefault(), "%.1f km/h", top),
                topLeft = Offset(0f, 0f),
                style = labelStyle,
            )
            val endLabel = String.format(Locale.getDefault(), "%.2f km", maxDistance)
            val endSize = measurer.measure(endLabel, labelStyle).size
            drawText(
                measurer,
                endLabel,
                topLeft = Offset(size.width - endSize.width, plotBottom + 2.dp.toPx()),
                style = labelStyle,
            )
            drawText(
                measurer,
                "0.00 km",
                topLeft = Offset(0f, plotBottom + 2.dp.toPx()),
                style = labelStyle,
            )
        }
    }
}

private fun nearestIndex(x: Float, width: Int, count: Int): Int {
    if (width <= 0) return 0
    val fraction = (x / width).coerceIn(0f, 1f)
    return (fraction * (count - 1)).toInt().coerceIn(0, count - 1)
}
