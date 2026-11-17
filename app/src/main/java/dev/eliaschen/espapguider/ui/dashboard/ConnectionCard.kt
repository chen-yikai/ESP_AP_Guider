@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.eliaschen.espapguider.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
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
    val offline = wifi is WifiStatus.Disconnected || wifi is WifiStatus.Failed
    var showDialog by rememberSaveable { mutableStateOf(false) }
    // Ask as soon as the ESP32 reports it isn't on Wi-Fi (or just failed to join). "Later" keeps it
    // closed until the status changes again; the card button reopens it any time.
    LaunchedEffect(wifi) { if (offline) showDialog = true }

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
        when {
            offline -> FilledTonalButton(
                onClick = { showDialog = true },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.testTag("open-wifi"),
            ) { Text("Connect to Wi-Fi") }
            wifi is WifiStatus.Connected -> TextButton(
                onClick = { showDialog = true },
                modifier = Modifier.testTag("open-wifi"),
            ) { Text("Change Wi-Fi") }
        }
    }

    if (showDialog) {
        WifiDialog(
            failed = wifi is WifiStatus.Failed,
            onConnect = { ssid, pass ->
                onProvision(ssid, pass)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
internal fun WifiDialog(
    failed: Boolean,
    onConnect: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var ssid by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    val canConnect = ssid.isNotBlank()
    val connect = { if (canConnect) onConnect(ssid.trim(), pass) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("wifi-dialog"),
        icon = {
            // Expressive shape badge; the glyph is drawn because material-icons-core has no Wi-Fi icon.
            Box(
                Modifier
                    .size(48.dp)
                    .clip(MaterialShapes.Cookie7Sided.toShape())
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                WifiGlyph(MaterialTheme.colorScheme.onPrimaryContainer, Modifier.size(24.dp))
            }
        },
        title = { Text("Connect the ESP32 to Wi-Fi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (failed) "It couldn't join that network. Check the name and password and try again."
                    else "The thermal camera streams over Wi-Fi. Your phone sends these details to the ESP32 over Bluetooth; it remembers them after a reboot.",
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = ssid, onValueChange = { ssid = it },
                    label = { Text("Wi-Fi name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it },
                    label = { Text("Password") }, singleLine = true,
                    supportingText = { Text("2.4 GHz networks only") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { connect() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = connect, enabled = canConnect, shapes = ButtonDefaults.shapes()) { Text("Connect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later") }
        },
    )
}

/** Three arcs and a dot. */
@Composable
private fun WifiGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val base = Offset(size.width / 2, size.height * 0.86f)
        val stroke = Stroke(width = size.width * 0.1f, cap = StrokeCap.Round)
        for (i in 1..3) {
            val r = size.height * 0.26f * i
            drawArc(
                color, startAngle = 225f, sweepAngle = 90f, useCenter = false,
                topLeft = base - Offset(r, r), size = Size(r * 2, r * 2), style = stroke,
            )
        }
        drawCircle(color, radius = size.width * 0.08f, center = base)
    }
}
