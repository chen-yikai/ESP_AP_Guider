package dev.eliaschen.espapguider.device

import dev.eliaschen.espapguider.ble.WifiStatus
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Byte-level checks of the firmware wire format (see firmware/thermal_haptics/thermal_haptics.ino). */
class ProtocolTest {
    private fun frame(extra: List<Int>, pixel: (Int) -> Int = { 2500 }) =
        ByteBuffer.allocate((IR_N + extra.size) * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(IR_N) { putShort(pixel(it).toShort()) }
            extra.forEach { putShort(it.toShort()) }
        }.array().toByteString()

    @Test fun parsesFullFrame() {
        // dutyL dutyR hotL hotR peakL peakR mirror trigger enabled led mode btn count cam
        val f = ThermalFrame.parse(frame(listOf(255, 0, 12, 0, 8050, 2600, 1, 5500, 0b01, 1, 1, 1, 65535, 1)) {
            if (it == 0) -1000 else 2500
        })!!
        assertEquals(-10f, f.min, 0.001f)
        assertEquals(25f, f.max, 0.001f)
        assertEquals(MotorState(255, 12, 80.5f, true), f.motors[0])
        assertEquals(MotorState(0, 0, 26f, false), f.motors[1])
        assertEquals(100, f.motors[0].percent)
        assertTrue(f.mirrorView)
        assertEquals(55f, f.triggerC, 0.001f)
        assertEquals(true, f.ledOn)
        assertEquals(MotorMode.Manual, f.mode)
        assertTrue(f.buttonPressed)
        assertEquals("count is unsigned on the wire", 65535, f.buttonCount)
        assertTrue(f.cameraOk)
    }

    @Test fun olderFirmwareWithoutNewFieldsStillParses() {
        val f = ThermalFrame.parse(frame(listOf(0, 0, 0, 0, 0, 0, 1, 5000, 3)))!!
        assertNull(f.ledOn)
        assertEquals(MotorMode.Auto, f.mode)
        assertFalse(f.buttonPressed)
        assertTrue(f.cameraOk)
    }

    @Test fun rejectsShortFrames() {
        assertNull(ThermalFrame.parse(frame(listOf(0, 0))))
    }

    @Test fun percentRoundsTheWayTheSliderSends() {
        // App slider 75 % -> duty round(75 * 2.55) = 191 -> shown as 75 %
        assertEquals(75, MotorState(191, 0, 0f, true).percent)
    }

    @Test fun parsesBleWifiStatus() {
        assertEquals(WifiStatus.Connected("192.168.1.42"), WifiStatus.parse("CONNECTED:192.168.1.42"))
        assertEquals(WifiStatus.Connecting, WifiStatus.parse("CONNECTING"))
        assertEquals(WifiStatus.Failed, WifiStatus.parse("FAILED"))
        assertEquals(WifiStatus.Disconnected, WifiStatus.parse("DISCONNECTED"))
        assertEquals(WifiStatus.Unknown, WifiStatus.parse("???"))
    }
}
