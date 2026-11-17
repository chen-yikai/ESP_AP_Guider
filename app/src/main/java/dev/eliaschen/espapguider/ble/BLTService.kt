package dev.eliaschen.espapguider.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import androidx.compose.runtime.compositionLocalOf
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the ESP32 reports on the status characteristic. */
sealed interface WifiStatus {
    data object Unknown : WifiStatus
    data object Disconnected : WifiStatus
    data object Connecting : WifiStatus
    data object Failed : WifiStatus
    data class Connected(val ip: String) : WifiStatus

    companion object {
        fun parse(raw: String): WifiStatus = when {
            raw.startsWith("CONNECTED:") -> Connected(raw.removePrefix("CONNECTED:").trim())
            raw == "CONNECTING" -> Connecting
            raw == "FAILED" -> Failed
            raw == "DISCONNECTED" -> Disconnected
            else -> Unknown
        }
    }
}

@SuppressLint("MissingPermission")
class BLTService(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val scanner = manager.adapter.bluetoothLeScanner
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _devices = MutableStateFlow<List<ScanResult>>(emptyList())
    val devices = _devices.asStateFlow()
    private val _connectionState = MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()
    private val _wifiStatus = MutableStateFlow<WifiStatus>(WifiStatus.Unknown)
    val wifiStatus = _wifiStatus.asStateFlow()

    private var gatt: BluetoothGatt? = null

    // Android GATT allows one outstanding operation at a time, so every op goes through this queue.
    private val ops = Channel<suspend (BluetoothGatt) -> Unit>(Channel.UNLIMITED)
    private var pending: CompletableDeferred<Unit>? = null

    init {
        scope.launch {
            for (op in ops) {
                val g = gatt ?: continue
                runCatching { op(g) }.onFailure { Log.w(TAG, "GATT op failed", it) }
            }
        }
    }

    /** Starts [start]; suspends until its callback fires (or times out). */
    private suspend fun awaitOp(start: () -> Boolean) {
        val done = CompletableDeferred<Unit>()
        pending = done
        if (start()) withTimeoutOrNull(3.seconds) { done.await() }
        pending = null
    }

    private fun enqueue(op: suspend (BluetoothGatt) -> Unit) {
        ops.trySend(op)
    }

    private fun char(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        g.getService(SERVICE_UUID)?.getCharacteristic(uuid)

    private suspend fun write(g: BluetoothGatt, uuid: UUID, value: ByteArray) {
        val c = char(g, uuid) ?: run {
            Log.w(TAG, "write: $uuid not found (services not discovered yet?)")
            return
        }
        awaitOp {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            _connectionState.value = newState
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                this@BLTService.gatt = null
                pending?.complete(Unit)
                _wifiStatus.value = WifiStatus.Unknown
                startListening()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            // Subscribe to Wi-Fi status, then read its current value.
            enqueue { g ->
                val c = char(g, CHAR_STAT) ?: return@enqueue
                g.setCharacteristicNotification(c, true)
                val cccd = c.getDescriptor(CCCD) ?: return@enqueue
                awaitOp {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothStatusCodes.SUCCESS
                }
            }
            enqueue { g ->
                val c = char(g, CHAR_STAT) ?: return@enqueue
                awaitOp { g.readCharacteristic(c) }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (characteristic.uuid == CHAR_STAT && status == BluetoothGatt.GATT_SUCCESS) {
                _wifiStatus.value = WifiStatus.parse(value.decodeToString())
            }
            pending?.complete(Unit)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHAR_STAT) {
                _wifiStatus.value = WifiStatus.parse(value.decodeToString())
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite ${characteristic.uuid} status=$status")
            pending?.complete(Unit)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pending?.complete(Unit)
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            _devices.update { list ->
                list.filterNot { it.device.address == result.device.address } + result
            }
            if (name == TARGET_DEVICE_NAME && gatt == null) {
                stopListening()
                val device = result.device
                scope.launch {
                    delay(1000.milliseconds)
                    gatt = device.connectGatt(context, false, gattCallback)
                }
            }
        }
    }

    fun startListening() {
        _devices.value = emptyList()
        scanner?.startScan(callback)
    }

    fun stopListening() {
        scanner?.stopScan(callback)
    }

    fun setLED(isOn: Boolean) {
        enqueue { g -> write(g, CHAR_LED, if (isOn) "ON".toByteArray() else "OFF".toByteArray()) }
    }

    /** Sends Wi-Fi credentials; the ESP32 starts connecting once the password arrives. */
    fun provisionWifi(ssid: String, password: String) {
        _wifiStatus.value = WifiStatus.Connecting
        enqueue { g -> write(g, CHAR_SSID, ssid.toByteArray()) }
        enqueue { g -> write(g, CHAR_PASS, password.toByteArray()) }
    }

    companion object {
        private const val TAG = "BLTService"
        const val TARGET_DEVICE_NAME = "ESP32_Provisioner"
        const val DEVICE_ALIAS = "Touch Senser"

        val SERVICE_UUID: UUID = UUID.fromString("4fa8c2d0-1cc0-11e8-ba89-0ed5f89f718b")
        val CHAR_SSID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val CHAR_PASS: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CHAR_STAT: UUID = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e")
        val CHAR_LED: UUID = UUID.fromString("6e400005-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

val LocalBLT = compositionLocalOf<BLTService> { error("BLTService not provided") }
