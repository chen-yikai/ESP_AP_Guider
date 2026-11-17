package dev.eliaschen.espapguider.ui.dashboard

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun LedCard(deviceLed: Boolean?, onLed: (Boolean) -> Unit) {
    var ledState by remember { mutableStateOf(false) }
    var editedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(deviceLed) {
        if (deviceLed != null && SystemClock.elapsedRealtime() - editedAt > EDIT_HOLD_MS) ledState = deviceLed
    }
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Column {
                Text("Onboard LED", style = MaterialTheme.typography.titleMedium)
                Text("GPIO 48 · RGB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(ledState, modifier = Modifier.testTag("led"), onCheckedChange = {
                ledState = it
                editedAt = SystemClock.elapsedRealtime()
                onLed(it)
            })
        }
    }
}
