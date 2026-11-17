package dev.eliaschen.espapguider.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.eliaschen.espapguider.ble.LocalBLT
import dev.eliaschen.espapguider.ble.WifiStatus
import dev.eliaschen.espapguider.device.MotorMode
import dev.eliaschen.espapguider.device.SocketState
import dev.eliaschen.espapguider.device.ThermalSocket
import java.util.Locale

internal const val TRIGGER_MIN = 25f
internal const val TRIGGER_MAX = 150f
internal const val EDIT_HOLD_MS = 1500L   // don't let device echoes fight the user while editing

internal val Vibrating = Color(0xFFE5484D)
internal val HotBox = Color(0xFF00E5FF)

/** Display options that only affect this screen, not the device. */
internal data class ViewOptions(
    val mirror: Boolean = true,
    val interpolate: Boolean = true,
    val hotBoxes: Boolean = true,
    val fahrenheit: Boolean = false,
)

internal fun ViewOptions.fmt(c: Float): String =
    if (fahrenheit) String.format(Locale.US, "%.1f °F", c * 9 / 5 + 32)
    else String.format(Locale.US, "%.1f °C", c)

@Composable
fun Dashboard(modifier: Modifier = Modifier) {
    val bltService = LocalBLT.current
    val wifiStatus by bltService.wifiStatus.collectAsState()
    DashboardContent(
        wifiStatus = wifiStatus,
        onProvision = bltService::provisionWifi,
        onBleLed = bltService::setLED,
        modifier = modifier,
    )
}

/** The dashboard without Bluetooth: the Wi-Fi status comes in, the WebSocket does the rest. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardContent(
    wifiStatus: WifiStatus,
    onProvision: (String, String) -> Unit,
    onBleLed: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    socket: ThermalSocket = remember { ThermalSocket() },
) {
    val socketState by socket.state.collectAsState()
    val frame by socket.frame.collectAsState()
    val fps by socket.fps.collectAsState()
    var options by remember { mutableStateOf(ViewOptions()) }

    LaunchedEffect(wifiStatus) {
        when (val s = wifiStatus) {
            is WifiStatus.Connected -> socket.connect(s.ip)
            WifiStatus.Unknown, WifiStatus.Connecting -> Unit   // keep whatever we have
            else -> socket.disconnect()
        }
    }
    DisposableEffect(Unit) { onDispose { socket.disconnect() } }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { GradientTopBar("ESP AP Guider") }
    ) { innerPadding ->
        LazyColumn(
            contentPadding = innerPadding,
            modifier = Modifier.fillMaxSize().testTag("dashboard"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ConnectionCard(wifiStatus, socketState, fps, onProvision = onProvision)
            }
            item {
                ThermalCard(frame, socketState, options, onOptions = { options = it })
            }
            item {
                ButtonCard(pressed = frame?.buttonPressed, count = frame?.buttonCount)
            }
            item {
                MotorModeCard(
                    mode = frame?.mode,
                    onMode = socket::setMode,
                    onStopAll = { repeat(2) { socket.setVibration(it, 0) } }
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    listOf("Left", "Right").forEachIndexed { i, name ->
                        MotorCard(
                            name = name,
                            motor = frame?.motors?.get(i),
                            mode = frame?.mode ?: MotorMode.Auto,
                            options = options,
                            onEnabled = { socket.setMotorEnabled(i, it) },
                            onManualDuty = { socket.setVibration(i, it) },
                            onTest = { socket.testMotor(i) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            if (frame?.mode != MotorMode.Manual) item {
                TriggerCard(
                    deviceTrigger = frame?.triggerC,
                    options = options,
                    onTrigger = socket::setTrigger
                )
            }
            item {
                LedCard(
                    deviceLed = frame?.ledOn,
                    onLed = { on ->
                        // Prefer Wi-Fi when it's up; BLE works without it.
                        if (socketState != SocketState.Live || !socket.setLed(on)) onBleLed(on)
                    }
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}
