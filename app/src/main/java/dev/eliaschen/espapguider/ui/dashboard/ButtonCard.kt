package dev.eliaschen.espapguider.ui.dashboard

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.Locale

internal data class ButtonEvent(val at: Long, val heldMs: Long?)

@Composable
internal fun ButtonCard(pressed: Boolean?, count: Int?) {
    val events = remember { mutableStateListOf<ButtonEvent>() }
    var lastCount by remember { mutableStateOf<Int?>(null) }
    var pressedAt by remember { mutableLongStateOf(0L) }
    var sessionPresses by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current

    // The device counts presses, so short taps between two frames are never lost.
    LaunchedEffect(count) {
        count ?: return@LaunchedEffect
        val prev = lastCount
        lastCount = count
        if (prev == null) return@LaunchedEffect
        val new = (count - prev) and 0xFFFF
        if (new in 1..50) {
            sessionPresses += new
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            repeat(new) { events.add(0, ButtonEvent(System.currentTimeMillis(), null)) }
            while (events.size > 5) events.removeAt(events.lastIndex)
        }
    }
    LaunchedEffect(pressed) {
        when (pressed) {
            true -> pressedAt = SystemClock.elapsedRealtime()
            false -> if (pressedAt != 0L && events.isNotEmpty() && events[0].heldMs == null) {
                events[0] = events[0].copy(heldMs = SystemClock.elapsedRealtime() - pressedAt)
                pressedAt = 0L
            }
            null -> Unit
        }
    }
    val dot by animateColorAsState(
        if (pressed == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "button"
    )
    val timeFmt = remember { java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    DashCard(
        title = "Push button",
        subtitle = "GPIO 6 on the ESP32",
        trailing = { Pill(if (count == null) "No data" else "$sessionPresses presses", modifier = Modifier.testTag("button-count")) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(dot),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (pressed == true) "ON" else "OFF",
                    modifier = Modifier.testTag("button-state"),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (pressed == true) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (events.isEmpty()) {
                    Text(
                        if (pressed == null) "Waiting for the ESP32…" else "Press the button on the ESP32",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                events.forEach { e ->
                    KeyValue(
                        timeFmt.format(java.util.Date(e.at)),
                        e.heldMs?.let { String.format(Locale.US, "held %.2f s", it / 1000f) } ?: "pressed"
                    )
                }
            }
        }
    }
}
