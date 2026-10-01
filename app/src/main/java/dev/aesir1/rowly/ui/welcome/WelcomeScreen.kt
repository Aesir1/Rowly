package dev.aesir1.rowly.ui.welcome

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.aesir1.rowly.ProjectLinks
import dev.aesir1.rowly.R

private const val PREFS = "welcome"
private const val KEY_LAST_SHOWN = "lastShownMs"
private const val SIX_MONTHS_MS = 183L * 24 * 60 * 60 * 1000

/** True on first launch and again once six months have passed since the last showing. */
fun welcomeDue(context: Context): Boolean {
    val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong(KEY_LAST_SHOWN, 0L)
    return System.currentTimeMillis() - last >= SIX_MONTHS_MS
}

fun markWelcomeShown(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putLong(KEY_LAST_SHOWN, System.currentTimeMillis()).apply()
}

@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The splash drawable is pre-composited tall; cropping its centre band keeps just
            // the artwork instead of the extended margins.
            Image(
                painter = painterResource(R.drawable.splash_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(24.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.welcome_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.welcome_continue))
            }
            OutlinedButton(
                onClick = { uriHandler.openUri(ProjectLinks.PAYPAL) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Icon(Icons.Filled.Favorite, contentDescription = null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.welcome_support))
            }
        }
    }
}
