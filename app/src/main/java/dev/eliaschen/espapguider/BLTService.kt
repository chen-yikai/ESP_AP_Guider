package dev.eliaschen.espapguider

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("MissingPermission")
class BLTService(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val scanner = manager.adapter.bluetoothLeScanner
    private val _devices = MutableStateFlow<List<ScanResult>>(emptyList())
    val devices = _devices.asStateFlow()
    private val _connectionState = MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private var gatt: BluetoothGatt? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            _connectionState.value = newState
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                this@BLTService.gatt = null
                startListening()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status services=${gatt.services.map { it.uuid }}")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite ${characteristic.uuid} status=$status")
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            _devices.update { list ->
                list.filterNot { it.device.address == result.device.address } + result
            }
            if (name == TARGET_DEVICE_NAME && gatt == null) {
                stopListening()
                val device = result.device
                CoroutineScope(Dispatchers.Main).launch {
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
        val gatt = gatt ?: run {
            Log.w(TAG, "setLED: not connected")
            return
        }
        val char = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_LED) ?: run {
            Log.w(TAG, "setLED: LED characteristic not found (services not discovered yet?)")
            return
        }
        val result = gatt.writeCharacteristic(
            char,
            if (isOn) "ON".toByteArray() else "OFF".toByteArray(),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
        Log.d(TAG, "setLED($isOn) write result=$result")
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
    }
}
