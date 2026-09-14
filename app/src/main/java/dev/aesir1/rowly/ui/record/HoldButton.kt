package dev.aesir1.rowly.ui.record

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aesir1.rowly.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Milliseconds the button must be held. Deliberately long - see [HoldButton]. */
const val HOLD_MILLIS = 3000

/**
 * An action that requires a continuous three-second hold, with a ring that fills as it progresses.
 *
 * The friction is the point: a brief tap while rowing must never interrupt a session or a
 * calibration run, and the filling ring tells the user both that the press registered and how
 * much longer to hold.
 */
@Composable
fun HoldButton(
    onHoldComplete: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.hold_to_pause),
    onHoldStart: () -> Unit = {},
    onHoldCancel: () -> Unit = {},
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val start by rememberUpdatedState(onHoldStart)
    val cancel by rememberUpdatedState(onHoldCancel)
    val complete by rememberUpdatedState(onHoldComplete)

    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.secondary
    val ring = with(LocalDensity.current) { 10.dp.toPx() }

    Box(
        modifier = modifier
            .size(168.dp)
            .clip(CircleShape)
            .drawBehind {
                val inset = ring / 2f
                val arcSize = Size(size.width - ring, size.height - ring)
                drawArc(
                    color = track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = ring),
                )
                drawArc(
                    color = fill,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.value,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = ring),
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        start()
                        val animation = scope.launch {
                            progress.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(HOLD_MILLIS, easing = LinearEasing),
                            )
                        }
                        // A null result means the timeout won the race: the finger was still down
                        // for the full three seconds, so the hold completed.
                        val released = withTimeoutOrNull(HOLD_MILLIS.toLong()) {
                            tryAwaitRelease()
                        }
                        animation.cancel()
                        if (released == null) {
                            complete()
                        } else {
                            cancel()
                        }
                        scope.launch { progress.animateTo(0f, tween(200)) }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}
