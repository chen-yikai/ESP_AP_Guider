@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.eliaschen.espapguider.ui.dashboard

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.eliaschen.espapguider.device.MotorMode
import dev.eliaschen.espapguider.device.MotorState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
internal fun MotorCard(
    name: String,
    motor: MotorState?,
    mode: MotorMode,
    options: ViewOptions,
    onEnabled: (Boolean) -> Unit,
    onManualDuty: (Int) -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val manual = mode == MotorMode.Manual
    // Optimistic switch: show the user's choice until the device catches up.
    var pending by remember { mutableStateOf<Boolean?>(null) }
    var pendingAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(motor?.enabled, pending) {
        if (pending != null && (motor?.enabled == pending || SystemClock.elapsedRealtime() - pendingAt > EDIT_HOLD_MS)) pending = null
        if (pending != null) { delay(EDIT_HOLD_MS); pending = null }
    }
    val enabled = pending ?: motor?.enabled ?: true
    val vibrating = motor?.vibrating == true
    val progress by animateFloatAsState((motor?.percent ?: 0) / 100f, label = "duty")

    val tag = name.lowercase()
    Card(
        modifier = modifier.testTag("motor-$tag"),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = if (vibrating) BorderStroke(2.dp, Vibrating) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
                Text("$name motor", style = MaterialTheme.typography.titleSmallEmphasized, modifier = Modifier.weight(1f))
                // The enable switch gates the camera trigger; in manual mode the phone is in charge.
                if (!manual) Switch(
                    checked = enabled,
                    enabled = motor != null,
                    modifier = Modifier.testTag("enable-$tag"),
                    onCheckedChange = {
                        pending = it
                        pendingAt = SystemClock.elapsedRealtime()
                        onEnabled(it)
                    }
                )
            }
            Pill(
                when {
                    motor == null -> "No data"
                    vibrating -> "Vibrating"
                    manual -> "Manual"
                    !enabled -> if (motor.hotPixels > 0) "Disabled · hot" else "Disabled"
                    else -> "Idle"
                },
                color = if (vibrating) Vibrating else null,
                live = vibrating
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${motor?.percent ?: 0}",
                    modifier = Modifier.testTag("duty-$tag"),
                    style = MaterialTheme.typography.displayMediumEmphasized,
                )
                Text("%", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
            }
            // Wavy while the motor runs — it reads as "buzzing" at a glance.
            if (vibrating) LinearWavyProgressIndicator(
                progress = { progress },
                color = Vibrating,
                trackColor = Vibrating.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth()
            ) else LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            if (manual) ManualDutySlider(motor?.duty, onManualDuty, Modifier.testTag("manual-$tag"))
            KeyValue("Peak", motor?.peakC?.takeIf { it > -200 && it != 0f }?.let(options::fmt) ?: "--")
            KeyValue("Hot pixels", motor?.hotPixels?.toString() ?: "--", Modifier.testTag("hot-$tag"))
            OutlinedButton(
                onClick = onTest,
                enabled = motor != null,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().testTag("test-$tag")
            ) {
                Text("Test buzz")
            }
        }
    }
}

@Composable
internal fun ManualDutySlider(deviceDuty: Int?, onDuty: (Int) -> Unit, modifier: Modifier = Modifier) {
    var percent by remember { mutableFloatStateOf(0f) }
    var editedAt by remember { mutableLongStateOf(0L) }
    var sent by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(deviceDuty) {
        if (deviceDuty != null && SystemClock.elapsedRealtime() - editedAt > EDIT_HOLD_MS) percent = deviceDuty / 2.55f
    }
    LaunchedEffect(sent) {
        val v = sent ?: return@LaunchedEffect
        delay(60)
        onDuty(v)
    }
    Slider(
        value = percent,
        modifier = modifier,
        enabled = deviceDuty != null,
        onValueChange = {
            percent = it.roundToInt().toFloat()
            editedAt = SystemClock.elapsedRealtime()
            sent = (percent * 2.55f).roundToInt()
        },
        valueRange = 0f..100f,
    )
}

@Composable
internal fun MotorModeCard(mode: MotorMode?, onMode: (MotorMode) -> Unit, onStopAll: () -> Unit) {
    DashCard(
        title = "Vibration motors",
        subtitle = when (mode) {
            MotorMode.Manual -> "Manual — you set each motor from the phone. Motors stop if the app disconnects."
            MotorMode.Auto -> "Auto — each motor buzzes when its half of the camera sees something hot."
            null -> "Needs the Wi-Fi link to the ESP32"
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Expressive connected button group: the selected item morphs to a pill.
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                MotorMode.entries.forEachIndexed { i, m ->
                    val checked = mode == m
                    ToggleButton(
                        checked = checked,
                        enabled = mode != null,
                        onCheckedChange = { onMode(m) },
                        shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes()
                        else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        // Unselected needs its own fill; the card is surfaceContainer too.
                        colors = ToggleButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { role = Role.RadioButton }
                            .testTag("mode-${m.name.lowercase()}"),
                    ) {
                        if (checked) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                            Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                        }
                        Text(if (m == MotorMode.Auto) "Auto" else "Manual")
                    }
                }
            }
            if (mode == MotorMode.Manual) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onStopAll, shapes = ButtonDefaults.shapes()) { Text("Stop") }
            }
        }
    }
}


@Composable
internal fun TriggerCard(deviceTrigger: Float?, options: ViewOptions, onTrigger: (Float) -> Unit) {
    var value by remember { mutableFloatStateOf(50f) }
    var editedAt by remember { mutableLongStateOf(0L) }
    var sent by remember { mutableStateOf<Float?>(null) }

    // Follow the device unless the user is dragging.
    LaunchedEffect(deviceTrigger) {
        if (deviceTrigger != null && SystemClock.elapsedRealtime() - editedAt > EDIT_HOLD_MS) value = deviceTrigger
    }
    // Debounced send.
    LaunchedEffect(sent) {
        val v = sent ?: return@LaunchedEffect
        delay(120)
        onTrigger(v)
    }
    val saved = deviceTrigger != null && kotlin.math.abs(deviceTrigger - value) < 0.05f

    DashCard(
        title = "Vibration trigger",
        subtitle = "A motor vibrates when its half sees anything hotter than this. Stops 2 °C below. Saved on the device.",
        trailing = {
            if (deviceTrigger != null) Pill(if (saved) "Saved" else "Saving…")
        }
    ) {
        Text(options.fmt(value), style = MaterialTheme.typography.displaySmallEmphasized)
        Slider(
            value = value,
            modifier = Modifier.testTag("trigger"),
            enabled = deviceTrigger != null,
            onValueChange = {
                value = (it * 2).roundToInt() / 2f
                editedAt = SystemClock.elapsedRealtime()
                sent = value
            },
            valueRange = TRIGGER_MIN..TRIGGER_MAX,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(options.fmt(TRIGGER_MIN), style = MaterialTheme.typography.labelSmall)
            Text(options.fmt(TRIGGER_MAX), style = MaterialTheme.typography.labelSmall)
        }
    }
}
