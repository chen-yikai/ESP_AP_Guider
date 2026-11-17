package dev.eliaschen.espapguider.ui.dashboard

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.eliaschen.espapguider.device.IR_H
import dev.eliaschen.espapguider.device.IR_N
import dev.eliaschen.espapguider.device.IR_W
import dev.eliaschen.espapguider.device.MotorState
import dev.eliaschen.espapguider.device.SocketState
import dev.eliaschen.espapguider.device.ThermalFrame
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ThermalCard(
    frame: ThermalFrame?,
    socket: SocketState,
    options: ViewOptions,
    onOptions: (ViewOptions) -> Unit,
) {
    // Auto range, eased so the colours don't flicker frame to frame.
    var lo by remember { mutableFloatStateOf(20f) }
    var hi by remember { mutableFloatStateOf(40f) }
    LaunchedEffect(frame) {
        frame ?: return@LaunchedEffect
        lo += (frame.min - lo) * 0.3f
        hi += (frame.max - hi) * 0.3f
    }

    DashCard(title = "Thermal camera", subtitle = "MLX90640 · 32×24 · each half drives one motor") {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (frame == null || !frame.cameraOk) {
                Text(
                    when {
                        frame != null -> "MLX90640 not found — check SDA (GPIO 8), SCL (GPIO 9), 3V3 and GND, then reset the ESP32"
                        socket == SocketState.Idle -> "Connect the ESP32 to Wi-Fi to see the camera"
                        else -> "Waiting for frames…"
                    },
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            } else {
                ThermalImage(frame, lo, hi, options)
            }
        }

        // Colour scale
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(IronStops.map { Color(it) }))
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (frame != null) options.fmt(lo) else "--", style = MaterialTheme.typography.labelSmall)
            Text(if (frame != null) options.fmt(hi) else "--", style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Min", frame?.let { options.fmt(it.min) }, Modifier.weight(1f))
            Stat("Center", frame?.let { options.fmt(it.center) }, Modifier.weight(1f))
            Stat("Max", frame?.let { options.fmt(it.max) }, Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(options.mirror, { onOptions(options.copy(mirror = !options.mirror)) }, { Text("Mirror") })
            FilterChip(options.interpolate, { onOptions(options.copy(interpolate = !options.interpolate)) }, { Text("Smooth") })
            FilterChip(options.hotBoxes, { onOptions(options.copy(hotBoxes = !options.hotBoxes)) }, { Text("Hot boxes") })
            FilterChip(options.fahrenheit, { onOptions(options.copy(fahrenheit = !options.fahrenheit)) }, { Text("°F") })
        }
    }
}

@Composable
internal fun ThermalImage(frame: ThermalFrame, lo: Float, hi: Float, options: ViewOptions) {
    val image = remember(frame, lo, hi, options.mirror) { renderThermal(frame.temps, lo, hi, options.mirror) }
    val boxes = remember(frame, options.mirror) {
        if (options.hotBoxes) findHotBoxes(frame.temps, frame.triggerC, options.mirror) else emptyList()
    }
    // Which on-screen half belongs to motor 0 depends on the firmware's MIRROR_VIEW and our mirror toggle.
    val leftIsMotor0 = options.mirror == frame.mirrorView
    val leftMotor = frame.motors[if (leftIsMotor0) 0 else 1]
    val rightMotor = frame.motors[if (leftIsMotor0) 1 else 0]
    val leftGlow by animateFloatAsState(if (leftMotor.vibrating) 1f else 0f, label = "leftGlow")
    val rightGlow by animateFloatAsState(if (rightMotor.vibrating) 1f else 0f, label = "rightGlow")
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().testTag("thermal-image")) {
            drawImage(
                image,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = if (options.interpolate) FilterQuality.Low else FilterQuality.None
            )
            val half = size.width / 2
            // Vibrating halves glow red.
            // Vibrating halves get a red outline and a faint tint (a heavy tint would wash out the palette).
            val edge = 3.dp.toPx()
            listOf(0f to leftGlow, half to rightGlow).forEach { (x, glow) ->
                if (glow <= 0f) return@forEach
                drawRect(Vibrating.copy(alpha = 0.08f * glow), Offset(x, 0f), Size(half, size.height))
                drawRect(
                    Vibrating.copy(alpha = glow), Offset(x + edge / 2, edge / 2), Size(half - edge, size.height - edge),
                    style = Stroke(edge)
                )
            }
            drawLine(
                Color.White.copy(alpha = 0.4f), Offset(half, 0f), Offset(half, size.height),
                strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
            )

            val sx = size.width / IR_W
            val sy = size.height / IR_H
            boxes.take(5).forEachIndexed { k, b ->
                val tl = Offset(b.x0 * sx, b.y0 * sy)
                val sz = Size((b.x1 - b.x0 + 1) * sx, (b.y1 - b.y0 + 1) * sy)
                drawRect(Color.Black.copy(alpha = 0.5f), tl, sz, style = Stroke(5.dp.toPx() / 2 + 3))
                drawRect(HotBox.copy(alpha = if (k == 0) 1f else 0.75f), tl, sz, style = Stroke(if (k == 0) 3.dp.toPx() else 2.dp.toPx()))
                drawCircle(HotBox, 4.dp.toPx(), Offset((b.px + 0.5f) * sx, (b.py + 0.5f) * sy))
                val label = measurer.measure(options.fmt(b.peak), labelStyle)
                val lw = label.size.width + 12f
                val lh = label.size.height + 4f
                val ly = if (tl.y >= lh + 2) tl.y - lh - 2 else min(tl.y + sz.height + 2, size.height - lh)
                val lx = tl.x.coerceIn(0f, max(0f, size.width - lw))
                drawRect(HotBox, Offset(lx, ly), Size(lw, lh))
                drawText(label, topLeft = Offset(lx + 6, ly + 2))
            }
        }
        ZoneTag("Left", leftMotor, Modifier.align(Alignment.TopStart).padding(8.dp))
        ZoneTag("Right", rightMotor, Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

@Composable
internal fun ZoneTag(name: String, motor: MotorState, modifier: Modifier) {
    val bg by animateColorAsState(if (motor.vibrating) Vibrating else Color.Black.copy(alpha = 0.6f), label = "tag")
    val text = when {
        motor.vibrating -> "$name · ${motor.percent}%"
        !motor.enabled -> "$name · off"
        else -> name
    }
    Surface(color = bg, contentColor = Color.White, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

// Iron palette, same as the web dashboard.
internal val IronStops = intArrayOf(
    0xFF000000.toInt(), 0xFF200060.toInt(), 0xFF800080.toInt(), 0xFFDC2828.toInt(),
    0xFFFF9600.toInt(), 0xFFFFE63C.toInt(), 0xFFFFFFFF.toInt(),
)
internal val IronLut = IntArray(256) { i ->
    val t = i / 255f * (IronStops.size - 1)
    val k = min(t.toInt(), IronStops.size - 2)
    val f = t - k
    val a = IronStops[k]
    val b = IronStops[k + 1]
    fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * f).roundToInt()
    (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

internal fun renderThermal(temps: FloatArray, lo: Float, hi: Float, mirror: Boolean): ImageBitmap {
    val span = max(hi - lo, 0.5f)
    val px = IntArray(IR_N)
    for (y in 0 until IR_H) for (x in 0 until IR_W) {
        val t = temps[y * IR_W + (if (mirror) IR_W - 1 - x else x)]
        px[y * IR_W + x] = IronLut[((t - lo) / span * 255).toInt().coerceIn(0, 255)]
    }
    return Bitmap.createBitmap(px, IR_W, IR_H, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** A blob of touching pixels above the trigger, in display (possibly mirrored) coordinates. */
internal data class HotBlob(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val peak: Float, val px: Int, val py: Int)

internal fun findHotBoxes(temps: FloatArray, trigger: Float, mirror: Boolean): List<HotBlob> {
    fun t(x: Int, y: Int) = temps[y * IR_W + (if (mirror) IR_W - 1 - x else x)]
    val seen = BooleanArray(IR_N)
    val out = mutableListOf<HotBlob>()
    val stack = ArrayDeque<Int>()
    for (s in 0 until IR_N) {
        if (seen[s] || t(s % IR_W, s / IR_W) <= trigger) continue
        var x0 = IR_W; var y0 = IR_H; var x1 = -1; var y1 = -1
        var peak = -1e9f; var bx = 0; var by = 0
        seen[s] = true; stack.addLast(s)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % IR_W; val y = i / IR_W
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
            val v = t(x, y)
            if (v > peak) { peak = v; bx = x; by = y }
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until IR_W || ny !in 0 until IR_H) continue
                val j = ny * IR_W + nx
                if (seen[j] || t(nx, ny) <= trigger) continue
                seen[j] = true; stack.addLast(j)
            }
        }
        out += HotBlob(x0, y0, x1, y1, peak, bx, by)
    }
    return out.sortedByDescending { it.peak }
}
