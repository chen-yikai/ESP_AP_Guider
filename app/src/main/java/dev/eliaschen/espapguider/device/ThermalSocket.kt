package dev.eliaschen.espapguider.device

import android.os.SystemClock
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

enum class SocketState { Idle, Connecting, Live, Reconnecting }

/** WebSocket link to ws://<ip>:81/ — receives thermal frames, sends haptics/LED commands. */
class ThermalSocket(private val port: Int = 81) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(SocketState.Idle)
    val state = _state.asStateFlow()
    private val _frame = MutableStateFlow<ThermalFrame?>(null)
    val frame = _frame.asStateFlow()
    private val _fps = MutableStateFlow(0f)
    val fps = _fps.asStateFlow()

    private var socket: WebSocket? = null
    private var host: String? = null
    private var reconnectJob: Job? = null
    private var frames = 0
    private var fpsStart = SystemClock.elapsedRealtime()

    fun connect(ip: String) {
        if (host == ip && socket != null) return
        disconnect()
        host = ip
        open(ip)
    }

    fun disconnect() {
        host = null
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
        _state.value = SocketState.Idle
        _frame.value = null
        _fps.value = 0f
    }

    private fun open(ip: String) {
        _state.value = if (_state.value == SocketState.Idle) SocketState.Connecting else SocketState.Reconnecting
        val request = Request.Builder().url("ws://$ip:$port/").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch { if (webSocket == socket) _state.value = SocketState.Live }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val f = ThermalFrame.parse(bytes) ?: return
                scope.launch {
                    if (webSocket != socket) return@launch
                    _frame.value = f
                    frames++
                    val now = SystemClock.elapsedRealtime()
                    if (now - fpsStart >= 1000) {
                        _fps.value = frames * 1000f / (now - fpsStart)
                        frames = 0
                        fpsStart = now
                    }
                }
            }

            // The ESP32 closed the link: finish the close handshake and reconnect right away
            // (onClosed only fires after both sides have closed).
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                retry(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = retry(webSocket)
        })
    }

    private fun retry(ws: WebSocket) {
        scope.launch {
            if (ws != socket) return@launch
            socket = null
            val ip = host ?: return@launch
            _state.value = SocketState.Reconnecting
            _fps.value = 0f
            reconnectJob = scope.launch {
                delay(1.seconds)
                if (host == ip) open(ip)
            }
        }
    }

    private fun send(cmd: String) = socket?.send(cmd) ?: false

    fun setTrigger(celsius: Float) = send("thr:%.1f".format(java.util.Locale.US, celsius))
    fun setMotorEnabled(side: Int, enabled: Boolean) = send("en:$side:${if (enabled) 1 else 0}")
    fun testMotor(side: Int) = send("test:$side")
    fun setLed(on: Boolean) = send(if (on) "on" else "off")
    fun setMode(mode: MotorMode) = send(if (mode == MotorMode.Manual) "mode:manual" else "mode:auto")
    /** Manual mode only: 0-255. */
    fun setVibration(side: Int, duty: Int) = send("vib:$side:${duty.coerceIn(0, 255)}")
}
