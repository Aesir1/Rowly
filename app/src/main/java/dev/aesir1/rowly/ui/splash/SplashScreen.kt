package dev.aesir1.rowly.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.aesir1.rowly.R

/** How long the branded launch image stays on screen. */
const val SPLASH_MILLIS = 2000L

/**
 * The launch image.
 *
 * The drawable is pre-composited tall (1080x2609, ~21:9 with margin) by clamp-extending the
 * artwork's own top and bottom rows outward. A flat colour behind a vignetted image always shows
 * a seam where the two meet; continuing the field from the exact edge pixels leaves nothing to
 * see. [ContentScale.Crop] then fills any phone aspect and trims only that extended field.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(R.color.splash_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.splash_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}
