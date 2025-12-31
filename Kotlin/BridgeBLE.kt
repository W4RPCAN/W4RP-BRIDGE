/**
 * BridgeBLE.kt
 *
 * W4RP Bridge - Kotlin/Android Implementation
 * Official client library for connecting to W4RPBLE firmware modules.
 *
 * ## Architecture
 *
 * State is managed ONLY by the BLE layer. The UI cannot modify state directly.
 *
 * | Pattern | Description |
 * |---------|-------------|
 * | **Request** | UI calls methods (scan, connect, etc.) |
 * | **Callback** | State changes arrive via callbacks |
 * | **Passive** | UI can read connectionState, connectedDevice anytime |
 *
 * ```
 * REQUEST:  UI.connect() → Android BLE API → GATT Server
 * CALLBACK: onConnectionStateChange → updateConnectionState → onConnectionStateChanged
 * PASSIVE:  bridge.connectionState, bridge.connectedDevice (read anytime)
 * ```
 *
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 2.0.0
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

// Types are defined in BridgeTypes.kt:
// - W4RPUUIDs, W4RPErrorCode, W4RPBluetoothStatus, W4RPError
// - W4RPDevice, W4RPModuleProfile, W4RPDebugData
// - W4RPConnectionState, W4RPRetryConfig, W4RPAutoReconnectConfig
// =============================================================================
// Bridge
// =============================================================================

/**
 * W4RPBridge - Android BLE client for W4RPBLE modules.
 *
 * ### Usage
 * ```kotlin
 * val bridge = W4RPBridge(context)
 *
 * lifecycleScope.launch {
 *     try {
 *         val devices = bridge.scan()
 *         bridge.connect(devices.first())
 *         val profile = bridge.getProfile()
 *         Log.d("W4RP", "Connected to: ${profile}")
 *     } catch (e: W4RPError) {
 *         Log.e("W4RP", "Error ${e.errorCode.code}: ${e.message}")
 *     }
 * }
 * ```
 */
class W4RPBridge(private val context: Context) {
    
    companion object {}
    
    // -------------------------------------------------------------------------
    // Platform Identifier (standardized across iOS, Android, Web)
    // -------------------------------------------------------------------------
    
    /** Platform identifier - 'android' for Kotlin, 'ios' for Swift, 'web' for TypeScript */
    val platform: String = "android"
    
    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    
    // -------------------------------------------------------------------------
    // Published State
    // -------------------------------------------------------------------------
    
    private var _isScanning = false
    private var _isDiscoveringServices = false
    
    private val _discoveredDevices = MutableStateFlow<List<W4RPDevice>>(emptyList())
    /** List of discovered devices during scanning */
    val discoveredDevices: StateFlow<List<W4RPDevice>> = _discoveredDevices.asStateFlow()

    private val _lastError = MutableStateFlow<W4RPError?>(null)
    /** Last error that occurred, cleared on successful operations */
    val lastError: StateFlow<W4RPError?> = _lastError.asStateFlow()
    
    private val _bluetoothStatus = MutableStateFlow(W4RPBluetoothStatus.UNKNOWN)
    /** Current Bluetooth adapter status */
    val bluetoothStatus: StateFlow<W4RPBluetoothStatus> = _bluetoothStatus.asStateFlow()

    /** 
     * Connection state is now COMPUTED from actual BLE state - source of truth.
     * Mirrored from Swift's implementation logic.
     */
    val connectionState: W4RPConnectionState get() {
        if (_isScanning) return W4RPConnectionState.SCANNING
        
        val gatt = bluetoothGatt ?: return W4RPConnectionState.DISCONNECTED
        
        // Map Android BluetoothProfile state to W4RPConnectionState
        return when (bluetoothGattState) {
            BluetoothProfile.STATE_CONNECTING -> W4RPConnectionState.CONNECTING
            BluetoothProfile.STATE_CONNECTED -> {
                if (_isDiscoveringServices || rxCharacteristic == null) {
                    W4RPConnectionState.DISCOVERING_SERVICES
                } else {
                    W4RPConnectionState.READY
                }
            }
            BluetoothProfile.STATE_DISCONNECTING -> W4RPConnectionState.DISCONNECTING
            else -> W4RPConnectionState.DISCONNECTED
        }
    }
    
    var uuids: W4RPUUIDs = W4RPUUIDs.DEFAULT
    var autoReconnectConfig: W4RPAutoReconnectConfig = W4RPAutoReconnectConfig.DISABLED
    
    // -------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------
    
    var onConnectionStateChanged: ((W4RPConnectionState) -> Unit)? = null
    var onDeviceDiscovered: ((List<W4RPDevice>) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    var onDebugData: ((W4RPDebugData) -> Unit)? = null
    var onError: ((W4RPError) -> Unit)? = null
    var onReconnecting: ((attempt: Int) -> Unit)? = null
    var onReconnected: (() -> Unit)? = null
    var onReconnectFailed: ((error: W4RPError) -> Unit)? = null
    var onBluetoothStatusChanged: ((W4RPBluetoothStatus) -> Unit)? = null
    
    // -------------------------------------------------------------------------
    // Computed Properties
    // -------------------------------------------------------------------------
    
    val isConnected: Boolean get() = connectionState == W4RPConnectionState.READY
    val isScanning: Boolean get() = connectionState == W4RPConnectionState.SCANNING
    
    val connectedDevice: W4RPDevice? get() {
        if (connectionState != W4RPConnectionState.READY) return null
        return lastConnectedDevice
    }
    
    var connectedDeviceName: String? = null
        private set
    
    // -------------------------------------------------------------------------
    // Private State
    // -------------------------------------------------------------------------
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothGattState = BluetoothProfile.STATE_DISCONNECTED
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    
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
    
    // Auto-reconnect State
    private var lastConnectedDevice: W4RPDevice? = null
    private var isAutoReconnecting = false
    private var lifetimeReconnectCount = 0
    private var wasIntentionalDisconnect = false
    
    internal val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    init {
        // Attempt connection recovery if Bluetooth is on
        if (bluetoothAdapter?.isEnabled == true) {
            try {
                // Determine if we have permissions (best effort check)
                val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                     context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
                } else {
                     context.checkSelfPermission(Manifest.permission.BLUETOOTH) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }

                if (hasPermission) {
                    val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
                    connectedDevices.firstOrNull { device -> 
                        // Basic filtering by name or service would be better, but service UUIDs aren't always available here without connecting
                        // W4RP devices usually advertise the service UUIDs.
                        // For recovery, we trust the device is relevant if it's already connected to the system.
                        device.name?.contains("W4RP", ignoreCase = true) == true || 
                        device.name?.contains("Exhaust", ignoreCase = true) == true // W4RP Exhaust
                    }?.let { device ->
                        android.util.Log.d("W4RP", "Found existing system connection to ${device.name} - Recovering session...")
                        val w4rpDevice = W4RPDevice(
                            device = device,
                            rssi = 0,
                            scanRecord = null,
                            advertisementData = emptyMap()
                        )
                        _discoveredDevices.value = listOf(w4rpDevice)
                        
                        // Notify that we found it
                        onDeviceDiscovered?.invoke(listOf(w4rpDevice))
                        
                        // Re-establish session logic
                        scope.launch {
                            try {
                                connect(w4rpDevice)
                            } catch (e: Exception) {
                                android.util.Log.w("W4RP", "Failed to recover connection: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore permission errors during init
            }
        }

        // Initialize status monitoring
        startStatusMonitoring()
    }

    // -------------------------------------------------------------------------
    // Status Monitoring
    // -------------------------------------------------------------------------

    private fun startStatusMonitoring() {
        // Initial check
        checkBluetoothStatus()

        // Register receiver for state changes
        val filter = android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    checkBluetoothStatus()
                }
            }
        }, filter)
    }

    private fun checkBluetoothStatus() {
        var status = W4RPBluetoothStatus.UNKNOWN

        try {
            if (bluetoothAdapter == null) {
                status = W4RPBluetoothStatus.UNSUPPORTED
            } else if (!hasBluetoothPermission()) {
                status = W4RPBluetoothStatus.UNAUTHORIZED
            } else {
                status = if (bluetoothAdapter.isEnabled) W4RPBluetoothStatus.READY else W4RPBluetoothStatus.POWERED_OFF
            }
        } catch (e: Exception) {
            status = W4RPBluetoothStatus.UNKNOWN
        }

        if (_bluetoothStatus.value != status) {
            _bluetoothStatus.value = status
            onBluetoothStatusChanged?.invoke(status)
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true // Legacy permissions usually granted at install time or handled via location
        }
    }

    // -------------------------------------------------------------------------
    // Suspend Public API
    // -------------------------------------------------------------------------
    
    /**
     * Scan for W4RP devices.
     *
     * @param timeoutMs Scan duration in milliseconds (default: 8000)
     * @return List of discovered devices sorted by signal strength
     * @throws W4RPError if Bluetooth is unavailable or no devices found
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    suspend fun scan(timeoutMs: Long = 8000): List<W4RPDevice> {
        checkBluetoothState()
        
        return suspendCancellableCoroutine { continuation ->
            scanContinuation = continuation
            
            _isScanning = true
            notifyStateChanged()
            
            _lastError.value = null
            deviceMap.clear()
            
            // CRITICAL FIX: Include already connected devices in scan results
            // Android scan typically excludes connected peripherals, or signals come slower.
            // We manually retrieve connected devices to populate the list immediately.
            val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            val initialDevices = connectedDevices.mapNotNull { device ->
                if (device.name?.contains("W4RP", ignoreCase = true) == true || 
                    device.name?.contains("Exhaust", ignoreCase = true) == true) {
                    
                    android.util.Log.d("W4RP", "Scan found existing connection to ${device.name}")
                    val w4rpDevice = W4RPDevice(
                        device = device,
                        name = device.name ?: "Unknown",
                        rssi = 0, // RSSI unknown
                        scanRecord = null
                    )
                    deviceMap[device.address] = w4rpDevice
                    
                    // CRITICAL FIX: Auto-sync connection state
                    if (connectionState == W4RPConnectionState.DISCONNECTED || connectionState == W4RPConnectionState.ERROR) {
                        android.util.Log.d("W4RP", "Scan matched connected device - Triggering auto-recovery...")
                        scope.launch {
                            try {
                                connect(w4rpDevice)
                            } catch (e: Exception) {
                                android.util.Log.e("W4RP", "Auto-recovery failed", e)
                            }
                        }
                    }
                    
                    w4rpDevice
                } else {
                    null
                }
            }
            _discoveredDevices.value = initialDevices
            
            scanner = bluetoothAdapter?.bluetoothLeScanner
            
            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UUID.fromString(uuids.service)))
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
     * @throws W4RPError if connection fails or times out
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connect(device: W4RPDevice, timeoutMs: Long = 10000) {
        if (connectionState == W4RPConnectionState.READY) {
            throw W4RPError(W4RPErrorCode.ALREADY_CONNECTED, "Already connected")
        }
        
        stopScan()
        bluetoothGattState = BluetoothProfile.STATE_CONNECTING
        notifyStateChanged()
        
        _lastError.value = null
        wasIntentionalDisconnect = false
        lastConnectedDevice = device
        
        return suspendCancellableCoroutine { continuation ->
            connectContinuation = continuation
            
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.device.connectGatt(context, false, gattCallback)
            }
            
            handler.postDelayed({
                if (connectionState != W4RPConnectionState.READY) {
                    bluetoothGatt?.close()
                    bluetoothGattState = BluetoothProfile.STATE_DISCONNECTED
                    notifyStateChanged()
                    
                    val err = W4RPError(W4RPErrorCode.CONNECTION_TIMEOUT, "Connection timed out")
                    _lastError.value = err
                    connectContinuation?.resumeWithException(err)
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
     * @throws W4RPError if disconnection fails
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun disconnect() {
        if (bluetoothGatt == null) return
        
        wasIntentionalDisconnect = true
        bluetoothGattState = BluetoothProfile.STATE_DISCONNECTING
        notifyStateChanged()
        
        return suspendCancellableCoroutine { continuation ->
            disconnectContinuation = continuation
            bluetoothGatt?.disconnect()
        }
    }
    
    /**
     * Fetch raw WBP profile binary from the module.
     *
     * Returns raw bytes - UI is responsible for parsing.
     *
     * @return Raw WBP binary data
     * @throws W4RPError if not connected or request fails
     */
    suspend fun getProfile(): ByteArray {
        ensureConnected()
        return streamRequest("GET:PROFILE")
    }
    
    /**
     * Fetch raw WBP rules binary from the module.
     *
     * Returns raw bytes - UI is responsible for parsing.
     *
     * @return Raw WBP binary data (or throws if no rules loaded)
     * @throws W4RPError if not connected or request fails
     */
    suspend fun getRules(): ByteArray {
        ensureConnected()
        return streamRequest("GET:RULES")
    }
    
    /**
     * Send compiled WBP rules binary to the module.
     *
     * @param binary Compiled WBP binary ruleset (from compiler)
     * @param persistent If true, rules are saved to NVS (survive reboot)
     * @param onProgress Optional callback reporting upload progress (bytesWritten, totalBytes)
     * @throws W4RPError if not connected or write fails
     */
    suspend fun setRules(
        binary: ByteArray,
        persistent: Boolean,
        onProgress: ((bytesWritten: Int, totalBytes: Int) -> Unit)? = null
    ) {
        ensureConnected()
        val gatt = bluetoothGatt ?: throw W4RPError(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        val rx = rxCharacteristic ?: throw W4RPError(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        
        val crc = calculateCRC32(binary)
        val mode = if (persistent) "NVS" else "RAM"
        val header = "SET:RULES:$mode:${binary.size}:$crc"
        
        writeData(gatt, rx, header.toByteArray(Charsets.UTF_8))
        sendChunked(gatt, rx, binary, onProgress = onProgress)
        writeData(gatt, rx, "END".toByteArray(Charsets.UTF_8))
    }
    
    private var lastConnectionStateForCallback: W4RPConnectionState = W4RPConnectionState.DISCONNECTED
    
    private fun notifyStateChanged() {
        val newState = connectionState
        if (newState != lastConnectionStateForCallback) {
            lastConnectionStateForCallback = newState
            onConnectionStateChanged?.invoke(newState)
        }
    }
    
    /**
     * Sets internal flags and notifies observers.
     * Mirrors Swift's setInternalState() method.
     */
    private fun setInternalState(scanning: Boolean? = null, discoveringServices: Boolean? = null) {
        scanning?.let { _isScanning = it }
        discoveringServices?.let { _isDiscoveringServices = it }
        notifyStateChanged()
    }
    
    /**
     * Centralizes error handling.
     * Mirrors Swift's handleError() method.
     */
    private fun handleError(error: W4RPError) {
        _lastError.value = error
        // Clear internal flags on error
        _isScanning = false
        _isDiscoveringServices = false
        notifyStateChanged()
        onError?.invoke(error)
    }
    
    /**
     * Start a Delta OTA firmware update.
     *
     * @param patchData Binary patch data (Janpatch format)
     * @param onProgress Optional callback reporting upload progress (bytesWritten, totalBytes)
     * @throws W4RPError if not connected or OTA fails
     */
    suspend fun startOTA(
        patchData: ByteArray,
        onProgress: ((bytesWritten: Int, totalBytes: Int) -> Unit)? = null
    ) {
        ensureConnected()
        val gatt = bluetoothGatt ?: throw W4RPError(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        val rx = rxCharacteristic ?: throw W4RPError(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        
        val crc = calculateCRC32(patchData)
        val cmd = "OTA:DELTA:${patchData.size}:${String.format("%X", crc)}"
        
        writeData(gatt, rx, cmd.toByteArray(Charsets.UTF_8))
        delay(200)
        sendChunked(gatt, rx, patchData, onProgress = onProgress)
        writeData(gatt, rx, "END".toByteArray(Charsets.UTF_8))
    }
    
    /**
     * Enable or disable debug mode on the module.
     * When enabled, the module streams signal/node state changes.
     *
     * @param enabled Whether to enable debug streaming
     * @throws W4RPError if not connected or command fails
     */
    suspend fun setDebugMode(enabled: Boolean) {
        ensureConnected()
        val gatt = bluetoothGatt ?: throw W4RPError(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        val rx = rxCharacteristic ?: throw W4RPError(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        
        val cmd = if (enabled) "DEBUG:START" else "DEBUG:STOP"
        writeData(gatt, rx, cmd.toByteArray(Charsets.UTF_8))
    }
    
    /**
     * Set specific signals to watch in debug mode.
     * Sends comma-separated signal specs to firmware.
     *
     * @param signals List of signal objects with can_id, start, length/len, factor, offset, big_endian/be
     * @throws W4RPError if not connected or command fails
     */
    suspend fun watchDebugSignals(signals: List<Map<String, Any>>) {
        ensureConnected()
        val gatt = bluetoothGatt ?: throw W4RPError(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        val rx = rxCharacteristic ?: throw W4RPError(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        
        // Build comma-separated specs: canId:start:len:be:factor:offset
        val specs = signals.map { s ->
            val canId = (s["can_id"] as? Number)?.toInt() ?: 0
            val start = (s["start"] as? Number)?.toInt() ?: (s["start_bit"] as? Number)?.toInt() ?: 0
            val len = (s["length"] as? Number)?.toInt() ?: (s["len"] as? Number)?.toInt() ?: 8
            val be = if ((s["big_endian"] as? Boolean) ?: (s["be"] as? Boolean) ?: false) 1 else 0
            val factor = (s["factor"] as? Number)?.toDouble() ?: 1.0
            val offset = (s["offset"] as? Number)?.toDouble() ?: 0.0
            "$canId:$start:$len:$be:$factor:$offset"
        }
        
        val payload = specs.joinToString(",")
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val crc = calculateCRC32(payloadBytes)
        val header = "DEBUG:WATCH:${payloadBytes.size}:$crc"
        
        writeData(gatt, rx, header.toByteArray(Charsets.UTF_8))
        delay(50)
        sendChunked(gatt, rx, payloadBytes)
        writeData(gatt, rx, "END".toByteArray(Charsets.UTF_8))
    }
    
    /** Internal flag to cancel ongoing OTA */
    private var otaCancelled = false
    
    /**
     * Cancel an ongoing OTA update.
     * This sets an internal flag that will abort the OTA at the next chunk boundary.
     */
    fun stopOTA() {
        otaCancelled = true
    }
    
    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------
    
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        if (_isScanning) {
            _isScanning = false
            notifyStateChanged()
        }
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {}
    }
    
    // -------------------------------------------------------------------------
    // Private Helpers
    // -------------------------------------------------------------------------
    
    private fun checkBluetoothState() {
        val adapter = bluetoothAdapter
            ?: throw W4RPError(W4RPErrorCode.BLUETOOTH_OFF, "Bluetooth not available")
        
        if (!adapter.isEnabled) {
            throw W4RPError(W4RPErrorCode.BLUETOOTH_OFF, "Bluetooth is off")
        }
    }
    
    private fun ensureConnected() {
        if (connectionState != W4RPConnectionState.READY || rxCharacteristic == null) {
            throw W4RPError(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        }
    }
    
    private fun finishScan() {
        stopScan()
        val devices = _discoveredDevices.value
        
        // Always return devices (even empty list) - no error on empty results
        scanContinuation?.resume(devices)
        scanContinuation = null
    }
    
    private suspend fun writeData(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, data: ByteArray) {
        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(char)
        delay(10)
    }
    
    private suspend fun sendChunked(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        data: ByteArray,
        chunkSize: Int = 180,
        onProgress: ((bytesWritten: Int, totalBytes: Int) -> Unit)? = null
    ) {
        val totalBytes = data.size
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            writeData(gatt, char, chunk)
            delay(3)
            offset = end
            onProgress?.invoke(offset, totalBytes)
        }
    }
    
    private suspend fun streamRequest(command: String, timeoutMs: Long = 10000): ByteArray {
        val gatt = bluetoothGatt ?: throw W4RPError(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        val rx = rxCharacteristic ?: throw W4RPError(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        
        return suspendCancellableCoroutine { continuation ->
            streamContinuation = continuation
            streamActive = false
            streamBuffer = ByteArray(0)
            streamExpectedLen = 0
            streamExpectedCRC = 0
            
            handler.postDelayed({
                finishStream(W4RPError(W4RPErrorCode.STREAM_TIMEOUT, "Stream timed out"))
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
        
        // Error responses from firmware (ERR:LEN_MISMATCH, ERR:CRC_FAIL, etc.)
        if (text.startsWith("ERR:")) {
            val errorType = text.substring(4)
            val error = W4RPError(W4RPErrorCode.INVALID_RESPONSE, "Firmware error: $errorType")
            _lastError.value = error
            onError?.invoke(error)
            
            // If we have an active stream, fail it
            if (streamActive || streamContinuation != null) {
                finishStream(error)
            }
            return
        }
        
        // OTA responses (informational)
        if (text.startsWith("OTA:")) {
            // OTA:READY, OTA:SUCCESS, OTA:ERROR, OTA:CANCELLED
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
            "S" -> {
                // D:S:<can>:<start>:<len>:<be>:<factor>:<offset>:<value>
                // Signal key = parts[2] through parts[length-2]
                // Value = parts[length-1]
                val idString = parts.subList(2, parts.size - 1).joinToString(":")
                val value = parts.last().toFloatOrNull() ?: 0f
                onDebugData?.invoke(W4RPDebugData(idString, W4RPDebugData.DebugType.SIGNAL, value = value))
            }
            "N" -> {
                val id = parts[2]
                val active = parts[3] == "1"
                onDebugData?.invoke(W4RPDebugData(id, W4RPDebugData.DebugType.NODE, active = active))
            }
        }
    }
    
    private fun finishStream(error: W4RPError?) {
        val continuation = streamContinuation ?: return
        streamContinuation = null
        streamActive = false
        
        if (error != null) {
            continuation.resumeWithException(error)
            return
        }
        
        if (streamBuffer.size != streamExpectedLen) {
            continuation.resumeWithException(W4RPError(
                W4RPErrorCode.LENGTH_MISMATCH,
                "Length mismatch: ${streamBuffer.size} != $streamExpectedLen"
            ))
            return
        }
        
        val crc = calculateCRC32(streamBuffer)
        if (crc != streamExpectedCRC) {
            continuation.resumeWithException(W4RPError(W4RPErrorCode.CRC_MISMATCH, "CRC mismatch"))
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
            
            // Notify listener for real-time UI updates
            onDeviceDiscovered?.invoke(_discoveredDevices.value)
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
                        bluetoothGattState = BluetoothProfile.STATE_CONNECTED
                        _isDiscoveringServices = true
                        notifyStateChanged()
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val previousState = connectionState
                        bluetoothGattState = BluetoothProfile.STATE_DISCONNECTED
                        _isDiscoveringServices = false
                        
                        rxCharacteristic = null
                        txCharacteristic = null
                        statusCharacteristic = null
                        bluetoothGatt?.close()
                        bluetoothGatt = null
                        notifyStateChanged()
                        
                        // Resume any pending disconnect continuation
                        disconnectContinuation?.resume(Unit)
                        disconnectContinuation = null
                        
                        // Check for auto-reconnect
                        val device = lastConnectedDevice
                        if (!wasIntentionalDisconnect &&
                            autoReconnectConfig.enabled &&
                            (previousState == W4RPConnectionState.READY || previousState == W4RPConnectionState.DISCOVERING_SERVICES) &&
                            device != null &&
                            !isAutoReconnecting
                        ) {
                            // Check lifetime reconnect limit
                            if (autoReconnectConfig.maxLifetimeReconnects > 0 &&
                                lifetimeReconnectCount >= autoReconnectConfig.maxLifetimeReconnects) {
                                val err = W4RPError(W4RPErrorCode.CONNECTION_FAILED, "Max lifetime reconnects exceeded")
                                onReconnectFailed?.invoke(err)
                                wasIntentionalDisconnect = false
                                return@post
                            }
                            
                            // Start auto-reconnect
                            isAutoReconnecting = true
                            
                            scope.launch {
                                var lastReconnectError: W4RPError? = null
                                
                                for (attempt in 0..autoReconnectConfig.retryConfig.maxRetries) {
                                    lifetimeReconnectCount++
                                    onReconnecting?.invoke(attempt + 1)
                                    
                                    try {
                                        connect(device)
                                        isAutoReconnecting = false
                                        onReconnected?.invoke()
                                        return@launch
                                    } catch (error: W4RPError) {
                                        lastReconnectError = error
                                        
                                        if (attempt < autoReconnectConfig.retryConfig.maxRetries) {
                                            val delayMs = autoReconnectConfig.retryConfig.delayForAttempt(attempt)
                                            delay(delayMs)
                                        }
                                    }
                                }
                                
                                isAutoReconnecting = false
                                onReconnectFailed?.invoke(lastReconnectError ?: W4RPError(W4RPErrorCode.CONNECTION_FAILED, "Auto-reconnect failed"))
                            }
                        }
                        
                        wasIntentionalDisconnect = false
                    }
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    bluetoothGattState = BluetoothProfile.STATE_DISCONNECTED
                    _isDiscoveringServices = false
                    notifyStateChanged()
                    
                    val err = W4RPError(W4RPErrorCode.SERVICE_NOT_FOUND, "Service discovery failed")
                    _lastError.value = err
                    connectContinuation?.resumeWithException(err)
                    connectContinuation = null
                    return@post
                }
                
                val service = gatt.getService(UUID.fromString(uuids.service))
                if (service == null) {
                    bluetoothGattState = BluetoothProfile.STATE_DISCONNECTED
                    _isDiscoveringServices = false
                    notifyStateChanged()

                    val err = W4RPError(W4RPErrorCode.SERVICE_NOT_FOUND, "W4RP service not found")
                    _lastError.value = err
                    connectContinuation?.resumeWithException(err)
                    connectContinuation = null
                    return@post
                }
                
                _isDiscoveringServices = false
                rxCharacteristic = service.getCharacteristic(UUID.fromString(uuids.rx))
                txCharacteristic = service.getCharacteristic(UUID.fromString(uuids.tx))
                statusCharacteristic = service.getCharacteristic(UUID.fromString(uuids.status))
                
                txCharacteristic?.let { enableNotifications(gatt, it) }
                statusCharacteristic?.let { enableNotifications(gatt, it) }
                
                if (rxCharacteristic != null && txCharacteristic != null && statusCharacteristic != null) {
                    notifyStateChanged()
                    _lastError.value = null
                    connectContinuation?.resume(Unit)
                    connectContinuation = null
                } else {
                    bluetoothGattState = BluetoothProfile.STATE_DISCONNECTED
                    notifyStateChanged()
                    val err = W4RPError(W4RPErrorCode.CHARACTERISTIC_NOT_FOUND, "Characteristics not found")
                    _lastError.value = err
                    connectContinuation?.resumeWithException(err)
                    connectContinuation = null
                }
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handler.post {
                val value = characteristic.value ?: return@post
                
                when (characteristic.uuid) {
                    UUID.fromString(uuids.tx) -> handleTX(value)
                    UUID.fromString(uuids.status) -> onStatusUpdate?.invoke(String(value, Charsets.UTF_8))
                }
            }
        }
        
        private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(cccdUuid)
            descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }
}
