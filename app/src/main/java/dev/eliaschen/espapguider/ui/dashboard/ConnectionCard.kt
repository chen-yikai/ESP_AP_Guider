package dev.eliaschen.espapguider.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.eliaschen.espapguider.ble.WifiStatus
import dev.eliaschen.espapguider.device.SocketState
import java.util.Locale

@Composable
internal fun ConnectionCard(
    wifi: WifiStatus,
    socket: SocketState,
    fps: Float,
    onProvision: (String, String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val showForm = editing || wifi is WifiStatus.Disconnected || wifi is WifiStatus.Failed

    DashCard(
        title = "Connection",
        subtitle = when (wifi) {
            is WifiStatus.Connected -> "Wi-Fi ${wifi.ip} · ws :81"
            WifiStatus.Connecting -> "Joining Wi-Fi…"
            WifiStatus.Failed -> "Couldn't join Wi-Fi — check the name and password"
            WifiStatus.Disconnected -> "Not on Wi-Fi — the thermal stream needs it"
            WifiStatus.Unknown -> "Reading status over Bluetooth…"
        },
        trailing = {
            when (socket) {
                SocketState.Live -> Pill(String.format(Locale.US, "Live · %.1f FPS", fps), live = true, modifier = Modifier.testTag("link"))
                SocketState.Connecting, SocketState.Reconnecting -> Pill("Connecting…", modifier = Modifier.testTag("link"))
                SocketState.Idle -> Pill("Bluetooth only", modifier = Modifier.testTag("link"))
            }
        }
    ) {
        if (showForm) {
            WifiForm(
                onSubmit = { ssid, pass -> onProvision(ssid, pass); editing = false },
                onCancel = if (editing) ({ editing = false }) else null
            )
        } else if (wifi is WifiStatus.Connected) {
            TextButton(onClick = { editing = true }) { Text("Change Wi-Fi") }
        }
    }
}

@Composable
internal fun WifiForm(onSubmit: (String, String) -> Unit, onCancel: (() -> Unit)?) {
    var ssid by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = ssid, onValueChange = { ssid = it },
            label = { Text("Wi-Fi name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pass, onValueChange = { pass = it },
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSubmit(ssid.trim(), pass) }, enabled = ssid.isNotBlank()) {
                Text("Send to ESP32")
            }
            if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
