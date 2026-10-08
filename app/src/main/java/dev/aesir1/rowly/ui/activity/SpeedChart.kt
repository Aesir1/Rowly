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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.compose.ui.unit.sp
import dev.aesir1.rowly.R
import dev.aesir1.rowly.data.entity.StrokeRateSampleEntity
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
    /** The stored session average, so the reference line agrees with the statistics card. */
    avgSpeedKmh: Double,
    modifier: Modifier = Modifier,
    /** The session's stroke readings, so a range selection can report its average SPM. */
    strokeSamples: List<StrokeRateSampleEntity> = emptyList(),
    onSelect: (SpeedSample?) -> Unit = {},
) {
    if (samples.size < 2) return

    val line = MaterialTheme.colorScheme.primary
    val crosshair = MaterialTheme.colorScheme.secondary
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val referenceColor = MaterialTheme.colorScheme.tertiary
    val labelBackground = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = axis)
    val referenceStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = referenceColor)

    var selected by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selected) { onSelect(selected?.let { samples[it] }) }
    // Range selection, as sample indices with first <= second. Created by a two-second hold,
    // adjusted by dragging either handle, dismissed by a plain tap.
    var selection by remember(samples) { mutableStateOf<Pair<Int, Int>?>(null) }
    val haptics = LocalHapticFeedback.current

    val minDistance = samples.first().distanceKm
    val maxDistance = samples.last().distanceKm
    val distanceSpan = (maxDistance - minDistance).takeIf { it > 1e-9 } ?: 1.0
    // The trace's own peak, so the max reference line sits exactly on the drawn curve. The stored
    // session max can be a hair higher (downsampling averages buckets) and would float above it.
    val maxSpeed = samples.maxOf { it.speedKmh }
    val minSpeed = samples.minOf { it.speedKmh }
    val avgLabel = referenceLabel(stringResource(R.string.avg_speed), avgSpeedKmh)
    val maxLabel = referenceLabel(stringResource(R.string.max_speed), maxSpeed)
    // A little headroom either side so the trace never touches the frame.
    val top = maxSpeed + (maxSpeed - minSpeed).coerceAtLeast(1.0) * 0.1
    val bottom = (minSpeed - (maxSpeed - minSpeed).coerceAtLeast(1.0) * 0.1).coerceAtLeast(0.0)
    val speedSpan = (top - bottom).takeIf { it > 1e-9 } ?: 1.0

    Column(modifier) {
        val readout = selected?.let { samples[it] }
        val range = selection
        Text(
            text = if (range != null) {
                rangeReadout(samples, range, strokeSamples, stringResource(R.string.spm))
            } else if (readout != null) {
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
                    // One gesture loop for everything. Two detectors in two pointerInput nodes
                    // cannot share this: detectTapGestures consumes the down, and the drag
                    // detector then cancels itself at the touch slop, which froze the crosshair
                    // wherever the finger first landed.
                    //
                    // The crosshair and the selection both stay put after the finger lifts: the
                    // point of either is to read values off the chart, and clearing them on
                    // release hides the answer.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun indexAt(x: Float) = nearestIndex(x, size.width, samples)
                        fun xOfIndex(i: Int) =
                            ((samples[i].distanceKm - minDistance) / distanceSpan)
                                .toFloat() * size.width

                        // A down landing on a selection handle drags that handle; the other
                        // bound anchors, so dragging across it swaps them instead of jamming.
                        var anchor: Int? = selection?.let { (s, e) ->
                            val grab = 24.dp.toPx()
                            val toStart = abs(down.position.x - xOfIndex(s))
                            val toEnd = abs(down.position.x - xOfIndex(e))
                            when {
                                minOf(toStart, toEnd) > grab -> null
                                toStart <= toEnd -> e
                                else -> s
                            }
                        }

                        if (anchor == null) {
                            selected = indexAt(down.position.x)
                            // Three ways out of the hold: a two-second press starts a range
                            // selection (timeout -> null), movement past the slop scrubs the
                            // crosshair (false), and a plain lift is a tap (true).
                            val lifted = withTimeoutOrNull(2_000L) {
                                var result = true
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.first()
                                    change.consume()
                                    if (!change.pressed) break
                                    if ((change.position - down.position).getDistance() >
                                        viewConfiguration.touchSlop
                                    ) {
                                        result = false
                                        break
                                    }
                                }
                                result
                            }
                            when (lifted) {
                                null -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selected = null
                                    val start = indexAt(down.position.x)
                                    selection = start to start
                                    anchor = start
                                }
                                // Scrubbing leaves selection mode: one highlighted range with a
                                // crosshair wandering through it reads as two answers at once.
                                false -> do {
                                    selection = null
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { change ->
                                        if (change.pressed) {
                                            selected = indexAt(change.position.x)
                                            change.consume()
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                                true -> selection = null
                            }
                        }

                        anchor?.let { fixed ->
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change ->
                                    if (change.pressed) {
                                        val idx = indexAt(change.position.x)
                                        selection = minOf(fixed, idx) to maxOf(fixed, idx)
                                        change.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
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
            // Max sits above its line and avg below, so the two labels cannot collide when the
            // session was steady and the lines run close together.
            fun reference(speed: Double, label: String, labelBelow: Boolean) {
                val y = yOfSpeed(speed)
                drawLine(
                    color = referenceColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = dash,
                )
                val layout = measurer.measure(label, referenceStyle)
                val pad = 3.dp.toPx()
                val textTop = (if (labelBelow) y + pad else y - layout.size.height - pad)
                    .coerceIn(0f, plotBottom - layout.size.height)
                val textLeft = size.width - layout.size.width - pad
                // A backing chip: without it the label drowns wherever the trace crosses it.
                drawRoundRect(
                    color = labelBackground.copy(alpha = 0.85f),
                    topLeft = Offset(textLeft - pad, textTop - pad / 2),
                    size = Size(layout.size.width + pad * 2, layout.size.height + pad),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                drawText(measurer, label, topLeft = Offset(textLeft, textTop), style = referenceStyle)
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
            reference(avgSpeedKmh, avgLabel, labelBelow = true)
            reference(maxSpeed, maxLabel, labelBelow = false)
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

            selection?.let { (startIdx, endIdx) ->
                val x0 = xOf(startIdx)
                val x1 = xOf(endIdx)
                // Low alpha wash: the trace and the reference labels stay readable through it.
                drawRect(
                    color = crosshair.copy(alpha = 0.15f),
                    topLeft = Offset(x0, 0f),
                    size = Size(x1 - x0, plotBottom),
                )
                for (x in listOf(x0, x1)) {
                    drawLine(
                        color = crosshair,
                        start = Offset(x, 0f),
                        end = Offset(x, plotBottom),
                        strokeWidth = 2.dp.toPx(),
                    )
                    // The grip, sized to say "drag me" rather than to match the crosshair dot.
                    drawCircle(
                        color = labelBackground,
                        radius = 7.dp.toPx(),
                        center = Offset(x, plotBottom / 2),
                    )
                    drawCircle(
                        color = crosshair,
                        radius = 7.dp.toPx(),
                        center = Offset(x, plotBottom / 2),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

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

/**
 * Readout for a selected range: distance span, its true average speed (distance over elapsed
 * time, not a mean of the smoothed trace), and the average SPM of the stroke readings that
 * fall inside the range's time window.
 */
private fun rangeReadout(
    samples: List<SpeedSample>,
    range: Pair<Int, Int>,
    strokeSamples: List<StrokeRateSampleEntity>,
    spmUnit: String,
): String {
    val a = samples[range.first]
    val b = samples[range.second]
    val hours = (b.timestamp - a.timestamp) / 3_600_000.0
    val avgSpeed = if (hours > 0) {
        (b.distanceKm - a.distanceKm) / hours
    } else {
        a.speedKmh
    }
    val inRange = strokeSamples.filter { it.timestamp in a.timestamp..b.timestamp }
    val spm = if (inRange.isEmpty()) {
        "--"
    } else {
        String.format(Locale.getDefault(), "%.0f", inRange.sumOf { it.strokesPerMinute } / inRange.size)
    }
    return String.format(
        Locale.getDefault(),
        "%.2f - %.2f km  -  %.1f km/h  -  %s %s",
        a.distanceKm,
        b.distanceKm,
        avgSpeed,
        spm,
        spmUnit,
    )
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
