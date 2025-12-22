/**
 * W4RPBridge.kt
 *
 * W4RP BLE Bridge - Kotlin/Android Implementation
 * Official client library for connecting to W4RPBLE firmware modules.
 *
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 1.0.0
 *
 * Compatible with:
 * - Android API 21+ (Lollipop)
 *
 * Required permissions:
 * - API 31+: BLUETOOTH_SCAN, BLUETOOTH_CONNECT
 * - API 23-30: ACCESS_FINE_LOCATION (for scanning)
 * - API 21-22: BLUETOOTH, BLUETOOTH_ADMIN
 */

package com.w4rp.bridge

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.zip.CRC32

// =============================================================================
// UUID Configuration
// =============================================================================

/**
 * W4RP BLE Service and Characteristic UUIDs.
 *
 * The UUID structure `5734-5250` encodes "W4RP" in ASCII hex:
 * - 0x57 = 'W'
 * - 0x34 = '4'
 * - 0x52 = 'R'
 * - 0x50 = 'P'
 *
 * This provides a recognizable namespace while remaining valid per Bluetooth SIG spec.
 */
object W4RPUUIDs {
    /** Primary GATT Service UUID - filter for this during scanning */
    val SERVICE: UUID = UUID.fromString("0000fff0-5734-5250-5734-525000000000")
    
    /** RX Characteristic - Write commands to the module */
    val RX: UUID = UUID.fromString("0000fff1-5734-5250-5734-525000000000")
    
    /** TX Characteristic - Receive data from module (Notify) */
    val TX: UUID = UUID.fromString("0000fff2-5734-5250-5734-525000000000")
    
    /** Status Characteristic - Module status updates (Notify) */
    val STATUS: UUID = UUID.fromString("0000fff3-5734-5250-5734-525000000000")
    
    /** Client Characteristic Configuration Descriptor (for notifications) */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

// =============================================================================
// Error Handling
// =============================================================================

/**
 * Standardized error codes for W4RP operations.
 *
 * Error code ranges:
 * - 1xxx: BLE Infrastructure errors (connection, scanning, permissions)
 * - 2xxx: Protocol errors (write failures, invalid responses)
 * - 3xxx: Data validation errors (CRC mismatch, length mismatch)
 * - 4xxx: Timeout errors (profile, stream, connection)
 */
enum class W4RPErrorCode(val code: Int) {
    // -------------------------------------------------------------------------
    // 1xxx: BLE Infrastructure
    // -------------------------------------------------------------------------
    
    /** GATT connection failed */
    CONNECTION_FAILED(1000),
    
    /** Connection was lost unexpectedly */
    CONNECTION_LOST(1001),
    
    /** Operation requires an active connection */
    NOT_CONNECTED(1002),
    
    /** Already connected to a device */
    ALREADY_CONNECTED(1003),
    
    /** No device was found during scan */
    DEVICE_NOT_FOUND(1004),
    
    /** W4RP service not found on device */
    SERVICE_NOT_FOUND(1005),
    
    /** Required characteristic not found */
    CHARACTERISTIC_NOT_FOUND(1006),
    
    /** Bluetooth adapter is powered off */
    BLUETOOTH_OFF(1007),
    
    /** Bluetooth permission denied by user or system */
    BLUETOOTH_UNAUTHORIZED(1008),
    
    // -------------------------------------------------------------------------
    // 2xxx: Protocol Errors
    // -------------------------------------------------------------------------
    
    /** Module returned an invalid or unexpected response */
    INVALID_RESPONSE(2000),
    
    /** Failed to write data to the RX characteristic */
    WRITE_FAILED(2003),
    
    // -------------------------------------------------------------------------
    // 3xxx: Data Validation Errors
    // -------------------------------------------------------------------------
    
    /** Data format is invalid (e.g., not valid UTF-8) */
    INVALID_DATA(3000),
    
    /** CRC32 checksum does not match expected value */
    CRC_MISMATCH(3001),
    
    /** Received data length does not match expected length */
    LENGTH_MISMATCH(3002),
    
    // -------------------------------------------------------------------------
    // 4xxx: Timeout Errors
    // -------------------------------------------------------------------------
    
    /** Module profile request timed out */
    PROFILE_TIMEOUT(4000),
    
    /** Data stream timed out before completion */
    STREAM_TIMEOUT(4001),
    
    /** Device scan timed out with no results */
    SCAN_TIMEOUT(4002),
    
    /** Connection attempt timed out */
    CONNECTION_TIMEOUT(4003)
}

/**
 * Custom exception for W4RP operations.
 * Includes error code, message, and optional context for debugging.
 */
class W4RPException(
    val errorCode: W4RPErrorCode,
    override val message: String,
    val context: Map<String, Any>? = null
) : Exception(message)

// =============================================================================
// Data Models
// =============================================================================

/**
 * Discovered W4RP device during scanning.
 */
data class W4RPDevice(
    val device: BluetoothDevice,
    val name: String,
    var rssi: Int,
    val scanRecord: ScanRecord?
)

/**
 * Module profile returned by GET:PROFILE command.
 */
data class W4RPModuleProfile(
    /** Unique module identifier (e.g., "W4RP-A1B2C3") */
    val id: String,
    /** Hardware revision (e.g., "esp32c3-mini-1") */
    val hw: String?,
    /** Firmware version (e.g., "1.0.0") */
    val fw: String?,
    /** Human-readable device name */
    val deviceName: String?,
    /** Registered capabilities */
    val capabilities: Map<String, W4RPCapability>?
)

/**
 * A capability represents an action the module can perform.
 */
data class W4RPCapability(
    val label: String?,
    val category: String?,
    val params: List<W4RPCapabilityParam>?
)

/**
 * Parameter definition for a capability.
 */
data class W4RPCapabilityParam(
    val name: String,
    val type: String,
    val min: Int?,
    val max: Int?
)

/**
 * Debug data from module.
 */
sealed class W4RPDebugData {
    abstract val id: String
    
    data class Signal(override val id: String, val value: Float) : W4RPDebugData()
    data class Node(override val id: String, val active: Boolean) : W4RPDebugData()
}

// =============================================================================
// Bridge
// =============================================================================

/**
 * W4RPBridge - Android BLE client for W4RPBLE modules.
 *
 * Usage:
 * ```kotlin
 * val bridge = W4RPBridge(context)
 *
 * bridge.startScan { result ->
 *     result.fold(
 *         onSuccess = { devices ->
 *             bridge.connect(devices.first()) { ... }
 *         },
 *         onFailure = { error ->
 *             Log.e("W4RP", "Scan failed: ${error.message}")
 *         }
 *     )
 * }
 * ```
 *
 * @param context Android Context (Application or Activity)
 */
class W4RPBridge(private val context: Context) {
    
    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    
    private val _isConnected = MutableStateFlow(false)
    /** Whether the bridge is currently connected to a device */
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    /** Whether the bridge is currently scanning for devices */
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    private val _discoveredDevices = MutableStateFlow<List<W4RPDevice>>(emptyList())
    /** List of discovered devices during scanning */
    val discoveredDevices: StateFlow<List<W4RPDevice>> = _discoveredDevices.asStateFlow()
    
    // -------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------
    
    /** Called when module sends a status update */
    var onStatusUpdate: ((String) -> Unit)? = null
    
    /** Called when debug data is received */
    var onDebugData: ((W4RPDebugData) -> Unit)? = null
    
    /** Called when connection is lost */
    var onDisconnect: (() -> Unit)? = null
    
    // -------------------------------------------------------------------------
    // Private State
    // -------------------------------------------------------------------------
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private val deviceMap = mutableMapOf<String, W4RPDevice>()
    private var scanner: BluetoothLeScanner? = null
    
    // Stream State
    private var streamActive = false
    private var streamBuffer = ByteArray(0)
    private var streamExpectedLen = 0
    private var streamExpectedCRC: Long = 0
    private var streamCompletion: ((Result<ByteArray>) -> Unit)? = null
    
    // Completions
    private var connectCompletion: ((Result<Unit>) -> Unit)? = null
    private var disconnectCompletion: ((Result<Unit>) -> Unit)? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------
    
    /**
     * Start scanning for W4RP devices.
     *
     * @param timeoutMs Scan duration in milliseconds (default: 8000)
     * @param completion Called with discovered devices or error
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun startScan(timeoutMs: Long = 8000, completion: (Result<List<W4RPDevice>>) -> Unit) {
        val adapter = bluetoothAdapter ?: run {
            completion(Result.failure(W4RPException(W4RPErrorCode.BLUETOOTH_OFF, "Bluetooth not available")))
            return
        }
        
        if (!adapter.isEnabled) {
            completion(Result.failure(W4RPException(W4RPErrorCode.BLUETOOTH_OFF, "Bluetooth is off")))
            return
        }
        
        _isScanning.value = true
        deviceMap.clear()
        _discoveredDevices.value = emptyList()
        
        scanner = adapter.bluetoothLeScanner
        
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(W4RPUUIDs.SERVICE))
            .build()
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        
        handler.postDelayed({
            stopScan()
            val devices = _discoveredDevices.value
            if (devices.isEmpty()) {
                completion(Result.failure(W4RPException(W4RPErrorCode.DEVICE_NOT_FOUND, "No W4RP devices found")))
            } else {
                completion(Result.success(devices))
            }
        }, timeoutMs)
    }
    
    /**
     * Stop scanning for devices.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        _isScanning.value = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // Ignore - permissions may have been revoked
        }
    }
    
    /**
     * Connect to a discovered device.
     *
     * @param device Device to connect to
     * @param timeoutMs Connection timeout in milliseconds (default: 10000)
     * @param completion Called on success or failure
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(device: W4RPDevice, timeoutMs: Long = 10000, completion: (Result<Unit>) -> Unit) {
        if (_isConnected.value) {
            completion(Result.failure(W4RPException(W4RPErrorCode.ALREADY_CONNECTED, "Already connected")))
            return
        }
        
        connectCompletion = completion
        
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.device.connectGatt(context, false, gattCallback)
        }
        
        handler.postDelayed({
            if (!_isConnected.value) {
                bluetoothGatt?.close()
                connectCompletion?.invoke(Result.failure(W4RPException(W4RPErrorCode.CONNECTION_TIMEOUT, "Connection timed out")))
                connectCompletion = null
            }
        }, timeoutMs)
    }
    
    /**
     * Disconnect from current device.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect(completion: (Result<Unit>) -> Unit) {
        disconnectCompletion = completion
        bluetoothGatt?.disconnect()
    }
    
    /**
     * Fetch the module profile.
     *
     * @param completion Called with profile JSON or error
     */
    fun getProfile(completion: (Result<String>) -> Unit) {
        val gatt = bluetoothGatt
        val rx = rxCharacteristic
        
        if (!_isConnected.value || gatt == null || rx == null) {
            completion(Result.failure(W4RPException(W4RPErrorCode.NOT_CONNECTED, "Not connected")))
            return
        }
        
        startStream { result ->
            result.fold(
                onSuccess = { data ->
                    val json = String(data, Charsets.UTF_8)
                    completion(Result.success(json))
                },
                onFailure = { error ->
                    completion(Result.failure(error))
                }
            )
        }
        
        val cmd = "GET:PROFILE".toByteArray(Charsets.UTF_8)
        rx.value = cmd
        gatt.writeCharacteristic(rx)
    }
    
    /**
     * Send rules to the module.
     *
     * @param json JSON string containing the ruleset
     * @param persistent If true, rules are saved to NVS (survive reboot)
     * @param completion Called on success or failure
     */
    fun setRules(json: String, persistent: Boolean, completion: (Result<Unit>) -> Unit) {
        val gatt = bluetoothGatt
        val rx = rxCharacteristic
        
        if (!_isConnected.value || gatt == null || rx == null) {
            completion(Result.failure(W4RPException(W4RPErrorCode.NOT_CONNECTED, "Not connected")))
            return
        }
        
        val data = json.toByteArray(Charsets.UTF_8)
        val crc = calculateCRC32(data)
        val mode = if (persistent) "NVS" else "RAM"
        val header = "SET:RULES:$mode:${data.size}:$crc"
        
        scope.launch {
            try {
                writeWithResponse(gatt, rx, header.toByteArray(Charsets.UTF_8))
                sendChunked(gatt, rx, data)
                writeWithResponse(gatt, rx, "END".toByteArray(Charsets.UTF_8))
                completion(Result.success(Unit))
            } catch (e: Exception) {
                completion(Result.failure(W4RPException(W4RPErrorCode.WRITE_FAILED, "Write failed: ${e.message}")))
            }
        }
    }
    
    /**
     * Start a Delta OTA firmware update.
     *
     * @param patchData Binary patch data (Janpatch format)
     * @param completion Called on success or failure
     */
    fun startOTA(patchData: ByteArray, completion: (Result<Unit>) -> Unit) {
        val gatt = bluetoothGatt
        val rx = rxCharacteristic
        
        if (!_isConnected.value || gatt == null || rx == null) {
            completion(Result.failure(W4RPException(W4RPErrorCode.NOT_CONNECTED, "Not connected")))
            return
        }
        
        val crc = calculateCRC32(patchData)
        val cmd = "OTA:BEGIN:DELTA:${patchData.size}:${String.format("%X", crc)}"
        
        scope.launch {
            try {
                writeWithResponse(gatt, rx, cmd.toByteArray(Charsets.UTF_8))
                delay(200) // Allow ESP32 to prepare
                sendChunked(gatt, rx, patchData)
                writeWithResponse(gatt, rx, "END".toByteArray(Charsets.UTF_8))
                completion(Result.success(Unit))
            } catch (e: Exception) {
                completion(Result.failure(W4RPException(W4RPErrorCode.WRITE_FAILED, "OTA failed: ${e.message}")))
            }
        }
    }
    
    // -------------------------------------------------------------------------
    // Private Helpers
    // -------------------------------------------------------------------------
    
    private suspend fun writeWithResponse(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, data: ByteArray) {
        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(char)
        delay(10)
    }
    
    private suspend fun sendChunked(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, data: ByteArray, chunkSize: Int = 180) {
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            writeWithResponse(gatt, char, chunk)
            delay(3)
            offset = end
        }
    }
    
    private fun startStream(timeoutMs: Long = 10000, completion: (Result<ByteArray>) -> Unit) {
        streamActive = false
        streamBuffer = ByteArray(0)
        streamExpectedLen = 0
        streamExpectedCRC = 0
        streamCompletion = completion
        
        handler.postDelayed({
            if (streamActive) {
                finishStream(W4RPException(W4RPErrorCode.STREAM_TIMEOUT, "Stream timed out"))
            }
        }, timeoutMs)
    }
    
    private fun handleTX(data: ByteArray) {
        val text = String(data, Charsets.UTF_8)
        
        if (text == "BEGIN") {
            streamActive = true
            streamBuffer = ByteArray(0)
            return
        }
        
        if (text.startsWith("END:")) {
            val parts = text.split(":")
            if (parts.size >= 3) {
                streamExpectedLen = parts[1].toIntOrNull() ?: 0
                streamExpectedCRC = parts[2].toLongOrNull() ?: 0
                finishStream(null)
            }
            return
        }
        
        if (text.startsWith("D:")) {
            handleDebug(text)
            return
        }
        
        if (streamActive) {
            streamBuffer += data
        }
    }
    
    private fun handleDebug(text: String) {
        val parts = text.split(":")
        if (parts.size < 4) return
        
        val type = parts[1]
        val id = parts[2]
        val value = parts[3]
        
        when (type) {
            "S" -> onDebugData?.invoke(W4RPDebugData.Signal(id, value.toFloatOrNull() ?: 0f))
            "N" -> onDebugData?.invoke(W4RPDebugData.Node(id, value == "1"))
        }
    }
    
    private fun finishStream(error: W4RPException?) {
        val completion = streamCompletion ?: return
        streamCompletion = null
        streamActive = false
        
        if (error != null) {
            completion(Result.failure(error))
            return
        }
        
        if (streamBuffer.size != streamExpectedLen) {
            completion(Result.failure(W4RPException(
                W4RPErrorCode.LENGTH_MISMATCH,
                "Length mismatch: ${streamBuffer.size} != $streamExpectedLen"
            )))
            return
        }
        
        val crc = calculateCRC32(streamBuffer)
        if (crc != streamExpectedCRC) {
            completion(Result.failure(W4RPException(W4RPErrorCode.CRC_MISMATCH, "CRC mismatch")))
            return
        }
        
        completion(Result.success(streamBuffer))
    }
    
    /** Calculate CRC32 checksum (IEEE 802.3 polynomial) */
    private fun calculateCRC32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }
    
    // -------------------------------------------------------------------------
    // Scan Callback
    // -------------------------------------------------------------------------
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown"
            val address = device.address
            
            if (deviceMap.containsKey(address)) {
                deviceMap[address]?.rssi = result.rssi
            } else {
                deviceMap[address] = W4RPDevice(device, name, result.rssi, result.scanRecord)
            }
            
            _discoveredDevices.value = deviceMap.values.sortedByDescending { it.rssi }
        }
    }
    
    // -------------------------------------------------------------------------
    // GATT Callback
    // -------------------------------------------------------------------------
    
    private val gattCallback = object : BluetoothGattCallback() {
        
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        _isConnected.value = false
                        rxCharacteristic = null
                        txCharacteristic = null
                        statusCharacteristic = null
                        bluetoothGatt?.close()
                        bluetoothGatt = null
                        
                        disconnectCompletion?.invoke(Result.success(Unit))
                        disconnectCompletion = null
                        onDisconnect?.invoke()
                    }
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectCompletion?.invoke(Result.failure(W4RPException(W4RPErrorCode.SERVICE_NOT_FOUND, "Service discovery failed")))
                    connectCompletion = null
                    return@post
                }
                
                val service = gatt.getService(W4RPUUIDs.SERVICE)
                if (service == null) {
                    connectCompletion?.invoke(Result.failure(W4RPException(W4RPErrorCode.SERVICE_NOT_FOUND, "W4RP service not found")))
                    connectCompletion = null
                    return@post
                }
                
                rxCharacteristic = service.getCharacteristic(W4RPUUIDs.RX)
                txCharacteristic = service.getCharacteristic(W4RPUUIDs.TX)
                statusCharacteristic = service.getCharacteristic(W4RPUUIDs.STATUS)
                
                txCharacteristic?.let { enableNotifications(gatt, it) }
                statusCharacteristic?.let { enableNotifications(gatt, it) }
                
                if (rxCharacteristic != null && txCharacteristic != null && statusCharacteristic != null) {
                    _isConnected.value = true
                    connectCompletion?.invoke(Result.success(Unit))
                    connectCompletion = null
                } else {
                    connectCompletion?.invoke(Result.failure(W4RPException(W4RPErrorCode.CHARACTERISTIC_NOT_FOUND, "Characteristics not found")))
                    connectCompletion = null
                }
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handler.post {
                val value = characteristic.value ?: return@post
                
                when (characteristic.uuid) {
                    W4RPUUIDs.TX -> handleTX(value)
                    W4RPUUIDs.STATUS -> {
                        val text = String(value, Charsets.UTF_8)
                        onStatusUpdate?.invoke(text)
                    }
                }
            }
        }
        
        private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(W4RPUUIDs.CCCD)
            descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }
}
