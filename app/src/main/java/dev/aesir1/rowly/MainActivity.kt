package dev.aesir1.rowly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.aesir1.rowly.ui.navigation.RowlyApp
import dev.aesir1.rowly.ui.splash.SPLASH_MILLIS
import dev.aesir1.rowly.ui.splash.SplashScreen
import dev.aesir1.rowly.ui.theme.RowlyTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RowlyTheme {
                // Saveable, so rotating the phone does not replay the launch image - and so a
                // rotation mid-session never tears down the Record screen to show a splash.
                var splashDone by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (!splashDone) {
                        // Start counting from the frame the splash is actually drawn on, not from
                        // composition. On a slow first frame those are most of a second apart, and
                        // the requirement is 2 s of visibility, not 2 s of clock.
                        withFrameNanos { }
                        delay(SPLASH_MILLIS)
                        splashDone = true
                    }
                }
                Crossfade(targetState = splashDone, label = "splash") { done ->
                    if (done) RowlyApp() else SplashScreen()
                }
            }
        }
    }
}
