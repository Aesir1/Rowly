package dev.aesir1.rowly.ui.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.compose.ui.unit.sp
import dev.aesir1.rowly.R
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
    onSelect: (SpeedSample?) -> Unit = {},
) {
    if (samples.size < 2) return

    val line = MaterialTheme.colorScheme.primary
    val crosshair = MaterialTheme.colorScheme.secondary
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = axis)

    var selected by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selected) { onSelect(selected?.let { samples[it] }) }

    val minDistance = samples.first().distanceKm
    val maxDistance = samples.last().distanceKm
    val distanceSpan = (maxDistance - minDistance).takeIf { it > 1e-9 } ?: 1.0
    val maxSpeed = samples.maxOf { it.speedKmh }
    val minSpeed = samples.minOf { it.speedKmh }
    val avgSpeed = samples.sumOf { it.speedKmh } / samples.size
    val avgLabel = referenceLabel(stringResource(R.string.avg_speed), avgSpeed)
    val maxLabel = referenceLabel(stringResource(R.string.max_speed), maxSpeed)
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
                    // One gesture loop for both the tap and the drag. Two detectors in two
                    // pointerInput nodes cannot share this: detectTapGestures consumes the down,
                    // and the drag detector then cancels itself at the touch slop, which froze
                    // the crosshair wherever the finger first landed.
                    //
                    // The crosshair stays put after the finger lifts: the point of dragging is to
                    // read a value off the chart, and clearing it on release hides the answer.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        selected = nearestIndex(down.position.x, size.width, samples)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (change.pressed) {
                                    selected =
                                        nearestIndex(change.position.x, size.width, samples)
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            val plotBottom = size.height - 18.dp.toPx()
            fun xOf(index: Int): Float =
                ((samples[index].distanceKm - minDistance) / distanceSpan).toFloat() * size.width

            fun yOfSpeed(speed: Double): Float =
                plotBottom - ((speed - bottom) / speedSpan).toFloat() * plotBottom

            fun yOf(index: Int): Float = yOfSpeed(samples[index].speedKmh)

            // Dashed so they read as references rather than as a second trace.
            val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
            fun reference(speed: Double, label: String) {
                val y = yOfSpeed(speed)
                drawLine(
                    color = axis.copy(alpha = 0.6f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
                val labelSize = measurer.measure(label, labelStyle).size
                drawText(
                    measurer,
                    label,
                    topLeft = Offset(
                        size.width - labelSize.width,
                        (y - labelSize.height - 2.dp.toPx()).coerceAtLeast(0f),
                    ),
                    style = labelStyle,
                )
            }

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
            reference(avgSpeed, avgLabel)
            reference(maxSpeed, maxLabel)
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

private fun referenceLabel(name: String, speed: Double): String =
    String.format(Locale.getDefault(), "%s %.1f km/h", name, speed)

/**
 * The sample nearest the touch, measured in distance - the same axis the chart is drawn on.
 *
 * Picking by index instead would only agree with the drawing when the samples are evenly spaced
 * in distance, and they never are: a session that starts with the boat sitting still stacks a
 * crowd of points on x = 0, and the crosshair would then sit nowhere near the finger.
 */
private fun nearestIndex(x: Float, width: Int, samples: List<SpeedSample>): Int {
    if (width <= 0 || samples.isEmpty()) return 0
    val start = samples.first().distanceKm
    val span = samples.last().distanceKm - start
    if (span <= 0.0) return 0
    val target = start + (x / width).coerceIn(0f, 1f) * span
    return samples.indices.minBy { abs(samples[it].distanceKm - target) }
}
