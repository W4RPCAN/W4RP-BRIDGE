/**
 * W4RPBridge.kt
 *
 * W4RP Bridge - Kotlin/Android Implementation
 * Official client library for connecting to W4RPBLE firmware modules.
 *
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 1.1.0
 *
 * Compatible with:
 * - Android API 21+ (Lollipop)
 *
 * Required permissions:
 * - API 31+: BLUETOOTH_SCAN, BLUETOOTH_CONNECT
 * - API 23-30: ACCESS_FINE_LOCATION (for scanning)
 * - API 21-22: BLUETOOTH, BLUETOOTH_ADMIN
 *
 * @see https://github.com/W4RPCAN/W4RPBLE for firmware source
 */

package com.w4rp.bridge

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.zip.CRC32
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
 */
object W4RPUUIDs {
    val SERVICE: UUID = UUID.fromString("0000fff0-5734-5250-5734-525000000000")
    val RX: UUID = UUID.fromString("0000fff1-5734-5250-5734-525000000000")
    val TX: UUID = UUID.fromString("0000fff2-5734-5250-5734-525000000000")
    val STATUS: UUID = UUID.fromString("0000fff3-5734-5250-5734-525000000000")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

// =============================================================================
// Error Handling
// =============================================================================

/**
 * Standardized error codes for W4RP operations.
 *
 * Error code ranges:
 * - 1xxx: BLE Infrastructure errors
 * - 2xxx: Protocol errors
 * - 3xxx: Data validation errors
 * - 4xxx: Timeout errors
 */
enum class W4RPErrorCode(val code: Int) {
    CONNECTION_FAILED(1000),
    CONNECTION_LOST(1001),
    NOT_CONNECTED(1002),
    ALREADY_CONNECTED(1003),
    DEVICE_NOT_FOUND(1004),
    SERVICE_NOT_FOUND(1005),
    CHARACTERISTIC_NOT_FOUND(1006),
    BLUETOOTH_OFF(1007),
    BLUETOOTH_UNAUTHORIZED(1008),
    
    INVALID_RESPONSE(2000),
    WRITE_FAILED(2003),
    
    INVALID_DATA(3000),
    CRC_MISMATCH(3001),
    LENGTH_MISMATCH(3002),
    
    PROFILE_TIMEOUT(4000),
    STREAM_TIMEOUT(4001),
    SCAN_TIMEOUT(4002),
    CONNECTION_TIMEOUT(4003)
}

class W4RPException(
    val errorCode: W4RPErrorCode,
    override val message: String,
    val context: Map<String, Any>? = null
) : Exception(message)

// =============================================================================
// Data Models
// =============================================================================

data class W4RPDevice(
    val device: BluetoothDevice,
    val name: String,
    var rssi: Int,
    val scanRecord: ScanRecord?
)

data class W4RPModuleProfile(
    val id: String,
    val hw: String?,
    val fw: String?,
    val deviceName: String?,
    val capabilities: Map<String, W4RPCapability>?
)

data class W4RPCapability(
    val label: String?,
    val category: String?,
    val params: List<W4RPCapabilityParam>?
)

data class W4RPCapabilityParam(
    val name: String,
    val type: String,
    val min: Int?,
    val max: Int?
)

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
 * This class provides both suspend and callback-based APIs.
 *
 * ### Suspend Usage (Recommended)
 * ```kotlin
 * val bridge = W4RPBridge(context)
 *
 * lifecycleScope.launch {
 *     try {
 *         val devices = bridge.scan()
 *         bridge.connect(devices.first())
 *         val profile = bridge.getProfile()
 *         Log.d("W4RP", "Connected to: ${profile}")
 *     } catch (e: W4RPException) {
 *         Log.e("W4RP", "Error ${e.errorCode.code}: ${e.message}")
 *     }
 * }
 * ```
 *
 * ### Callback Usage (Legacy)
 * ```kotlin
 * bridge.scanWithCallback { result -> ... }
 * ```
 */
class W4RPBridge(private val context: Context) {
    
    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    private val _discoveredDevices = MutableStateFlow<List<W4RPDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<W4RPDevice>> = _discoveredDevices.asStateFlow()
    
    // -------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------
    
    var onStatusUpdate: ((String) -> Unit)? = null
    var onDebugData: ((W4RPDebugData) -> Unit)? = null
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
    
    // Continuations for suspend functions
    private var scanContinuation: CancellableContinuation<List<W4RPDevice>>? = null
    private var connectContinuation: CancellableContinuation<Unit>? = null
    private var disconnectContinuation: CancellableContinuation<Unit>? = null
    private var streamContinuation: CancellableContinuation<ByteArray>? = null
    
    // Stream State
    private var streamActive = false
    private var streamBuffer = ByteArray(0)
    private var streamExpectedLen = 0
    private var streamExpectedCRC: Long = 0
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // -------------------------------------------------------------------------
    // Suspend Public API
    // -------------------------------------------------------------------------
    
    /**
     * Scan for W4RP devices.
     *
     * @param timeoutMs Scan duration in milliseconds (default: 8000)
     * @return List of discovered devices sorted by signal strength
     * @throws W4RPException if Bluetooth is unavailable or no devices found
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    suspend fun scan(timeoutMs: Long = 8000): List<W4RPDevice> {
        checkBluetoothState()
        
        return suspendCancellableCoroutine { continuation ->
            scanContinuation = continuation
            
            _isScanning.value = true
            deviceMap.clear()
            _discoveredDevices.value = emptyList()
            
            scanner = bluetoothAdapter?.bluetoothLeScanner
            
            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(W4RPUUIDs.SERVICE))
                .build()
            
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            
            scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            
            handler.postDelayed({
                finishScan()
            }, timeoutMs)
            
            continuation.invokeOnCancellation {
                stopScan()
            }
        }
    }
    
    /**
     * Connect to a discovered device.
     *
     * @param device Device to connect to
     * @param timeoutMs Connection timeout in milliseconds (default: 10000)
     * @throws W4RPException if connection fails or times out
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connect(device: W4RPDevice, timeoutMs: Long = 10000) {
        if (_isConnected.value) {
            throw W4RPException(W4RPErrorCode.ALREADY_CONNECTED, "Already connected")
        }
        
        return suspendCancellableCoroutine { continuation ->
            connectContinuation = continuation
            
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.device.connectGatt(context, false, gattCallback)
            }
            
            handler.postDelayed({
                if (!_isConnected.value) {
                    bluetoothGatt?.close()
                    connectContinuation?.resumeWithException(
                        W4RPException(W4RPErrorCode.CONNECTION_TIMEOUT, "Connection timed out")
                    )
                    connectContinuation = null
                }
            }, timeoutMs)
            
            continuation.invokeOnCancellation {
                bluetoothGatt?.close()
            }
        }
    }
    
    /**
     * Disconnect from the current device.
     *
     * @throws W4RPException if disconnection fails
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun disconnect() {
        if (bluetoothGatt == null) return
        
        return suspendCancellableCoroutine { continuation ->
            disconnectContinuation = continuation
            bluetoothGatt?.disconnect()
        }
    }
    
    /**
     * Fetch the module profile.
     *
     * @return Profile JSON string
     * @throws W4RPException if not connected or request fails
     */
    suspend fun getProfile(): String {
        ensureConnected()
        val data = streamRequest("GET:PROFILE")
        return String(data, Charsets.UTF_8)
    }
    
    /**
     * Send rules to the module.
     *
     * @param json JSON string containing the ruleset
     * @param persistent If true, rules are saved to NVS (survive reboot)
     * @throws W4RPException if not connected or write fails
     */
    suspend fun setRules(json: String, persistent: Boolean) {
        ensureConnected()
        val gatt = bluetoothGatt ?: throw W4RPException(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        val rx = rxCharacteristic ?: throw W4RPException(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        
        val data = json.toByteArray(Charsets.UTF_8)
        val crc = calculateCRC32(data)
        val mode = if (persistent) "NVS" else "RAM"
        val header = "SET:RULES:$mode:${data.size}:$crc"
        
        writeData(gatt, rx, header.toByteArray(Charsets.UTF_8))
        sendChunked(gatt, rx, data)
        writeData(gatt, rx, "END".toByteArray(Charsets.UTF_8))
    }
    
    /**
     * Start a Delta OTA firmware update.
     *
     * @param patchData Binary patch data (Janpatch format)
     * @throws W4RPException if not connected or OTA fails
     */
    suspend fun startOTA(patchData: ByteArray) {
        ensureConnected()
        val gatt = bluetoothGatt ?: throw W4RPException(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        val rx = rxCharacteristic ?: throw W4RPException(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        
        val crc = calculateCRC32(patchData)
        val cmd = "OTA:BEGIN:DELTA:${patchData.size}:${String.format("%X", crc)}"
        
        writeData(gatt, rx, cmd.toByteArray(Charsets.UTF_8))
        delay(200)
        sendChunked(gatt, rx, patchData)
        writeData(gatt, rx, "END".toByteArray(Charsets.UTF_8))
    }
    
    // -------------------------------------------------------------------------
    // Legacy Callback API (Backward Compatibility)
    // -------------------------------------------------------------------------
    
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun scanWithCallback(timeoutMs: Long = 8000, completion: (Result<List<W4RPDevice>>) -> Unit) {
        scope.launch {
            try {
                val devices = scan(timeoutMs)
                completion(Result.success(devices))
            } catch (e: W4RPException) {
                completion(Result.failure(e))
            }
        }
    }
    
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectWithCallback(device: W4RPDevice, timeoutMs: Long = 10000, completion: (Result<Unit>) -> Unit) {
        scope.launch {
            try {
                connect(device, timeoutMs)
                completion(Result.success(Unit))
            } catch (e: W4RPException) {
                completion(Result.failure(e))
            }
        }
    }
    
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectWithCallback(completion: (Result<Unit>) -> Unit) {
        scope.launch {
            try {
                disconnect()
                completion(Result.success(Unit))
            } catch (e: W4RPException) {
                completion(Result.failure(e))
            }
        }
    }
    
    fun getProfileWithCallback(completion: (Result<String>) -> Unit) {
        scope.launch {
            try {
                val profile = getProfile()
                completion(Result.success(profile))
            } catch (e: W4RPException) {
                completion(Result.failure(e))
            }
        }
    }
    
    fun setRulesWithCallback(json: String, persistent: Boolean, completion: (Result<Unit>) -> Unit) {
        scope.launch {
            try {
                setRules(json, persistent)
                completion(Result.success(Unit))
            } catch (e: W4RPException) {
                completion(Result.failure(e))
            }
        }
    }
    
    fun startOTAWithCallback(patchData: ByteArray, completion: (Result<Unit>) -> Unit) {
        scope.launch {
            try {
                startOTA(patchData)
                completion(Result.success(Unit))
            } catch (e: W4RPException) {
                completion(Result.failure(e))
            }
        }
    }
    
    // -------------------------------------------------------------------------
    // Public Utilities
    // -------------------------------------------------------------------------
    
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        _isScanning.value = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {}
    }
    
    // -------------------------------------------------------------------------
    // Private Helpers
    // -------------------------------------------------------------------------
    
    private fun checkBluetoothState() {
        val adapter = bluetoothAdapter
            ?: throw W4RPException(W4RPErrorCode.BLUETOOTH_OFF, "Bluetooth not available")
        
        if (!adapter.isEnabled) {
            throw W4RPException(W4RPErrorCode.BLUETOOTH_OFF, "Bluetooth is off")
        }
    }
    
    private fun ensureConnected() {
        if (!_isConnected.value || rxCharacteristic == null) {
            throw W4RPException(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        }
    }
    
    private fun finishScan() {
        stopScan()
        val devices = _discoveredDevices.value
        
        if (devices.isEmpty()) {
            scanContinuation?.resumeWithException(
                W4RPException(W4RPErrorCode.DEVICE_NOT_FOUND, "No W4RP devices found")
            )
        } else {
            scanContinuation?.resume(devices)
        }
        scanContinuation = null
    }
    
    private suspend fun writeData(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, data: ByteArray) {
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
            writeData(gatt, char, chunk)
            delay(3)
            offset = end
        }
    }
    
    private suspend fun streamRequest(command: String, timeoutMs: Long = 10000): ByteArray {
        val gatt = bluetoothGatt ?: throw W4RPException(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        val rx = rxCharacteristic ?: throw W4RPException(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        
        return suspendCancellableCoroutine { continuation ->
            streamContinuation = continuation
            streamActive = false
            streamBuffer = ByteArray(0)
            streamExpectedLen = 0
            streamExpectedCRC = 0
            
            handler.postDelayed({
                finishStream(W4RPException(W4RPErrorCode.STREAM_TIMEOUT, "Stream timed out"))
            }, timeoutMs)
            
            rx.value = command.toByteArray(Charsets.UTF_8)
            gatt.writeCharacteristic(rx)
        }
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
        
        when (parts[1]) {
            "S" -> onDebugData?.invoke(W4RPDebugData.Signal(parts[2], parts[3].toFloatOrNull() ?: 0f))
            "N" -> onDebugData?.invoke(W4RPDebugData.Node(parts[2], parts[3] == "1"))
        }
    }
    
    private fun finishStream(error: W4RPException?) {
        val continuation = streamContinuation ?: return
        streamContinuation = null
        streamActive = false
        
        if (error != null) {
            continuation.resumeWithException(error)
            return
        }
        
        if (streamBuffer.size != streamExpectedLen) {
            continuation.resumeWithException(W4RPException(
                W4RPErrorCode.LENGTH_MISMATCH,
                "Length mismatch: ${streamBuffer.size} != $streamExpectedLen"
            ))
            return
        }
        
        val crc = calculateCRC32(streamBuffer)
        if (crc != streamExpectedCRC) {
            continuation.resumeWithException(W4RPException(W4RPErrorCode.CRC_MISMATCH, "CRC mismatch"))
            return
        }
        
        continuation.resume(streamBuffer)
    }
    
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
                        
                        disconnectContinuation?.resume(Unit)
                        disconnectContinuation = null
                        onDisconnect?.invoke()
                    }
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectContinuation?.resumeWithException(
                        W4RPException(W4RPErrorCode.SERVICE_NOT_FOUND, "Service discovery failed")
                    )
                    connectContinuation = null
                    return@post
                }
                
                val service = gatt.getService(W4RPUUIDs.SERVICE)
                if (service == null) {
                    connectContinuation?.resumeWithException(
                        W4RPException(W4RPErrorCode.SERVICE_NOT_FOUND, "W4RP service not found")
                    )
                    connectContinuation = null
                    return@post
                }
                
                rxCharacteristic = service.getCharacteristic(W4RPUUIDs.RX)
                txCharacteristic = service.getCharacteristic(W4RPUUIDs.TX)
                statusCharacteristic = service.getCharacteristic(W4RPUUIDs.STATUS)
                
                txCharacteristic?.let { enableNotifications(gatt, it) }
                statusCharacteristic?.let { enableNotifications(gatt, it) }
                
                if (rxCharacteristic != null && txCharacteristic != null && statusCharacteristic != null) {
                    _isConnected.value = true
                    connectContinuation?.resume(Unit)
                    connectContinuation = null
                } else {
                    connectContinuation?.resumeWithException(
                        W4RPException(W4RPErrorCode.CHARACTERISTIC_NOT_FOUND, "Characteristics not found")
                    )
                    connectContinuation = null
                }
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handler.post {
                val value = characteristic.value ?: return@post
                
                when (characteristic.uuid) {
                    W4RPUUIDs.TX -> handleTX(value)
                    W4RPUUIDs.STATUS -> onStatusUpdate?.invoke(String(value, Charsets.UTF_8))
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
