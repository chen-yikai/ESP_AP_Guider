package dev.eliaschen.espapguider.fake

import dev.eliaschen.espapguider.device.IR_H
import dev.eliaschen.espapguider.device.IR_N
import dev.eliaschen.espapguider.device.IR_W
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Stand-in for the ESP32 on the WebSocket (port 81) side.
 *
 * Speaks the wire format documented at the top of firmware/thermal_haptics/thermal_haptics.ino
 * and copies its motor/button logic (constants included), so the app can be tested end to end
 * without hardware. If you change the firmware protocol, change this too.
 */
class FakeEsp32 {
    // Same constants as the firmware.
    private val mirrorView = true
    private val hysteresis = 2f
    private val nearPixels = 96
    private val nearTempSpan = 70f
    private val minDuty = 90

    @Volatile var cameraOk = true
    @Volatile var trigger = 50f; private set
    @Volatile var manual = false; private set
    @Volatile var led = false; private set
    @Volatile var buttonPressed = false; private set
    @Volatile var buttonCount = 0; private set
    val enabled = booleanArrayOf(true, true)
    private val manualDuty = intArrayOf(0, 0)
    private val testUntil = longArrayOf(0, 0)
    private val active = booleanArrayOf(false, false)
    @Volatile var duty = intArrayOf(0, 0); private set

    /** Hot object in front of the camera, by motor side (0 = left, 1 = right); null = nothing hot. */
    @Volatile var hotSide: Int? = null
    @Volatile var hotTempC = 80f

    /** Every text command the app sent, in order. */
    val commands = CopyOnWriteArrayList<String>()

    private val server = MockWebServer()
    private val clients = CopyOnWriteArrayList<WebSocket>()
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private var tick: ScheduledFuture<*>? = null

    val port get() = server.port
    val host: String get() = server.hostName

    fun start() {
        // Each connection attempt (including reconnects) gets an upgrade response.
        repeat(20) { server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build()) }
        server.start()
        tick = ticker.scheduleAtFixedRate(::sendFrame, 0, 1000 / 16, TimeUnit.MILLISECONDS) // 16 Hz like the sensor
    }

    fun stop() {
        tick?.cancel(true)
        ticker.shutdownNow()
        dropClients()
        server.close()
    }

    /** Simulates the Wi-Fi link dropping: every client is cut off. */
    fun dropClients() {
        // Server-side sockets can't cancel() (no Call behind them); close() is what the ESP32 does on a drop too.
        clients.forEach { it.close(1001, "going away") }
        clients.clear()
    }

    fun pressButton() { buttonPressed = true; buttonCount = (buttonCount + 1) and 0xFFFF }
    fun releaseButton() { buttonPressed = false }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            clients += webSocket
            webSocket.send(if (led) "{\"on\":true}" else "{\"on\":false}")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            commands += text
            handle(text.trim())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { clients -= webSocket }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { clients -= webSocket }
    }

    @Synchronized
    private fun handle(cmd: String) {
        when {
            cmd == "on" -> led = true
            cmd == "off" -> led = false
            cmd == "toggle" -> led = !led
            cmd.startsWith("thr:") -> cmd.substring(4).toFloatOrNull()?.let { trigger = it.coerceIn(25f, 150f) }
            cmd.length == 6 && cmd.startsWith("en:") -> enabled[cmd[3] - '0'] = cmd[5] == '1'
            cmd.length == 6 && cmd.startsWith("test:") -> testUntil[cmd[5] - '0'] = System.currentTimeMillis() + 400
            cmd == "mode:manual" || cmd == "mode:auto" -> {
                manual = cmd == "mode:manual"
                manualDuty.fill(0)
            }
            cmd.startsWith("vib:") && manual -> manualDuty[cmd[4] - '0'] = cmd.substring(6).toInt().coerceIn(0, 255)
        }
    }

    private fun temps(): FloatArray {
        val t = FloatArray(IR_N) { 25f + (it % 7) * 0.1f }   // room temperature with a little texture
        val side = hotSide ?: return t
        // Firmware: raw left half -> motor (rawLeft != MIRROR_VIEW ? 0 : 1), i.e. raw right half = left motor.
        val x0 = if ((side == 0) == mirrorView) IR_W / 2 + 3 else 3
        for (y in 8 until 16) for (x in x0 until x0 + 8) t[y * IR_W + x] = hotTempC
        return t
    }

    @Synchronized
    private fun sendFrame() {
        if (clients.isEmpty()) {
            if (manual) manualDuty.fill(0)       // firmware fail-safe: nobody watching
            return
        }
        val t = if (cameraOk) temps() else FloatArray(IR_N)
        val hot = IntArray(2)
        val peak = floatArrayOf(-273f, -273f)
        for (y in 0 until IR_H) for (x in 0 until IR_W) {
            val v = t[y * IR_W + x]
            val side = if ((x < IR_W / 2) != mirrorView) 0 else 1
            if (v > peak[side]) peak[side] = v
            if (v > (if (active[side]) trigger - hysteresis else trigger)) hot[side]++
        }
        val now = System.currentTimeMillis()
        val d = IntArray(2) { s ->
            active[s] = hot[s] > 0
            when {
                now < testUntil[s] -> 255
                manual -> manualDuty[s]
                !cameraOk || !active[s] || !enabled[s] -> 0
                else -> {
                    val closeness = max(sqrt(hot[s].toFloat() / nearPixels), (peak[s] - trigger) / nearTempSpan).coerceIn(0f, 1f)
                    (minDuty + (255 - minDuty) * closeness).toInt()
                }
            }
        }
        duty = d

        val buf = ByteBuffer.allocate((IR_N + 14) * 2).order(ByteOrder.LITTLE_ENDIAN)
        t.forEach { buf.putShort((it * 100).toInt().toShort()) }
        listOf(
            d[0], d[1], hot[0], hot[1], (peak[0] * 100).toInt(), (peak[1] * 100).toInt(),
            if (mirrorView) 1 else 0, (trigger * 100).toInt(),
            (if (enabled[0]) 1 else 0) or (if (enabled[1]) 2 else 0),
            if (led) 1 else 0, if (manual) 1 else 0, if (buttonPressed) 1 else 0, buttonCount,
            if (cameraOk) 1 else 0,
        ).forEach { buf.putShort(it.toShort()) }
        val bytes = buf.array().toByteString()
        clients.forEach { it.send(bytes) }
    }
}
