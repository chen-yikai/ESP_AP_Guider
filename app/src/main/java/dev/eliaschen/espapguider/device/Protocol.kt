package dev.eliaschen.espapguider.device

import java.nio.ByteOrder
import kotlin.math.roundToInt
import okio.ByteString

// Wire format of the ESP32 WebSocket stream — keep in sync with firmware/thermal_haptics/thermal_haptics.ino

const val IR_W = 32
const val IR_H = 24
const val IR_N = IR_W * IR_H

data class MotorState(
    val duty: Int,          // 0-255
    val hotPixels: Int,
    val peakC: Float,
    val enabled: Boolean,
) {
    val vibrating get() = duty > 0
    val percent get() = (duty / 2.55f).roundToInt()
}

enum class MotorMode { Auto, Manual }

/** One frame from the ESP32: 32x24 temperatures (raw sensor order) plus motor/button/LED state. */
class ThermalFrame(
    val temps: FloatArray,
    val motors: List<MotorState>,   // 0 = left, 1 = right
    val mirrorView: Boolean,
    val triggerC: Float,
    val ledOn: Boolean?,
    val mode: MotorMode,
    val buttonPressed: Boolean,
    val buttonCount: Int,
    val cameraOk: Boolean,
) {
    val min = temps.min()
    val max = temps.max()
    val center = (temps[11 * IR_W + 15] + temps[11 * IR_W + 16] + temps[12 * IR_W + 15] + temps[12 * IR_W + 16]) / 4f

    companion object {
        fun parse(bytes: ByteString): ThermalFrame? {
            val buf = bytes.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            if (buf.remaining() < IR_N + 9) return null
            val raw = ShortArray(buf.remaining()).also { buf.get(it) }
            val temps = FloatArray(IR_N) { raw[it] / 100f }
            val enabledBits = raw[IR_N + 8].toInt()
            val motors = List(2) { i ->
                MotorState(
                    duty = raw[IR_N + i].toInt(),
                    hotPixels = raw[IR_N + 2 + i].toInt(),
                    peakC = raw[IR_N + 4 + i] / 100f,
                    enabled = enabledBits and (1 shl i) != 0,
                )
            }
            return ThermalFrame(
                temps = temps,
                motors = motors,
                mirrorView = raw[IR_N + 6].toInt() != 0,
                triggerC = raw[IR_N + 7] / 100f,
                ledOn = raw.getOrNull(IR_N + 9)?.let { it.toInt() != 0 },
                mode = if (raw.getOrNull(IR_N + 10)?.toInt() == 1) MotorMode.Manual else MotorMode.Auto,
                buttonPressed = raw.getOrNull(IR_N + 11)?.toInt() == 1,
                buttonCount = (raw.getOrNull(IR_N + 12)?.toInt() ?: 0) and 0xFFFF,
                cameraOk = raw.getOrNull(IR_N + 13)?.toInt() != 0,
            )
        }
    }
}
