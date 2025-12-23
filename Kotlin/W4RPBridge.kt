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
// Connection State Machine
// =============================================================================

/**
 * Represents the current state of the BLE connection.
 *
 * State transitions:
 * - DISCONNECTED -> SCANNING (via scan())
 * - SCANNING -> DISCONNECTED (scan complete/timeout)
 * - DISCONNECTED -> CONNECTING (via connect())
 * - CONNECTING -> DISCOVERING_SERVICES (GATT connected)
 * - DISCOVERING_SERVICES -> READY (characteristics found)
 * - READY -> DISCONNECTING (via disconnect())
 * - DISCONNECTING -> DISCONNECTED (complete)
 * - ANY -> ERROR (on failure)
 */
enum class W4RPConnectionState(val value: String) {
    /** Not connected to any device */
    DISCONNECTED("DISCONNECTED"),
    
    /** Scanning for nearby W4RP devices */
    SCANNING("SCANNING"),
    
    /** Initiating GATT connection to a device */
    CONNECTING("CONNECTING"),
    
    /** Connected, discovering services and characteristics */
    DISCOVERING_SERVICES("DISCOVERING_SERVICES"),
    
    /** Fully connected and ready for operations */
    READY("READY"),
    
    /** Disconnection in progress */
    DISCONNECTING("DISCONNECTING"),
    
    /** An error occurred (check lastError for details) */
    ERROR("ERROR");
    
    /** Convenience property for checking if ready for operations */
    val isReady: Boolean get() = this == READY
    
    /** Convenience property for checking if any connection activity is in progress */
    val isBusy: Boolean get() = when (this) {
        SCANNING, CONNECTING, DISCOVERING_SERVICES, DISCONNECTING -> true
        else -> false
    }
}

// =============================================================================
// Retry Configuration
// =============================================================================

/**
 * Configuration for exponential backoff retry logic.
 *
 * Example usage:
 * ```kotlin
 * val config = W4RPRetryConfig(maxRetries = 3, baseDelayMs = 1000)
 * bridge.connectWithRetry(device, retryConfig = config)
 * ```
 *
 * @property maxRetries Maximum number of retry attempts (0 = no retries, just the initial attempt)
 * @property baseDelayMs Base delay in milliseconds before first retry (default: 1000)
 * @property maxDelayMs Maximum delay cap in milliseconds (default: 16000)
 * @property multiplier Multiplier for exponential growth (default: 2.0)
 */
data class W4RPRetryConfig(
    val maxRetries: Int = 3,
    val baseDelayMs: Long = 1000L,
    val maxDelayMs: Long = 16000L,
    val multiplier: Double = 2.0
) {
    companion object {
        /** Default configuration: 3 retries, 1s base, 16s max, 2x multiplier */
        val DEFAULT = W4RPRetryConfig()
    }
    
    /**
     * Calculate delay for a given attempt (0-indexed).
     * @param attempt The attempt number (0-indexed)
     * @return Delay in milliseconds
     */
    fun delayForAttempt(attempt: Int): Long {
        val delay = (baseDelayMs * kotlin.math.pow(multiplier, attempt.toDouble())).toLong()
        return minOf(delay, maxDelayMs)
    }
}

// =============================================================================
// Auto-Reconnect Configuration
// =============================================================================

/**
 * Configuration for automatic reconnection when connection is lost unexpectedly.
 *
 * When enabled, the bridge will attempt to reconnect automatically if the connection
 * is lost (e.g., device goes out of range, power loss). The reconnection uses
 * exponential backoff as configured in `retryConfig`.
 *
 * Example usage:
 * ```kotlin
 * bridge.autoReconnectConfig = W4RPAutoReconnectConfig(enabled = true)
 * bridge.onReconnecting = { attempt -> Log.d("W4RP", "Reconnecting attempt $attempt...") }
 * bridge.onReconnected = { Log.d("W4RP", "Reconnected!") }
 * bridge.onReconnectFailed = { error -> Log.e("W4RP", "Failed: ${error.message}") }
 * ```
 *
 * @property enabled Whether auto-reconnect is enabled (default: false)
 * @property retryConfig Retry configuration for reconnection attempts
 * @property maxLifetimeReconnects Maximum total reconnection attempts across all disconnections (0 = unlimited)
 */
data class W4RPAutoReconnectConfig(
    val enabled: Boolean = false,
    val retryConfig: W4RPRetryConfig = W4RPRetryConfig.DEFAULT,
    val maxLifetimeReconnects: Int = 0
) {
    companion object {
        /** Disabled configuration (default) */
        val DISABLED = W4RPAutoReconnectConfig(enabled = false)
        
        /** Default enabled configuration: up to 5 reconnect attempts */
        val DEFAULT = W4RPAutoReconnectConfig(
            enabled = true,
            retryConfig = W4RPRetryConfig(maxRetries = 5, baseDelayMs = 2000)
        )
    }
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
    // Platform Identifier (standardized across iOS, Android, Web)
    // -------------------------------------------------------------------------
    
    /** Platform identifier - 'android' for Kotlin, 'ios' for Swift, 'web' for TypeScript */
    val platform: String = "android"
    
    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    
    private val _connectionState = MutableStateFlow(W4RPConnectionState.DISCONNECTED)
    /** Current connection state */
    val connectionState: StateFlow<W4RPConnectionState> = _connectionState.asStateFlow()
    
    private val _lastError = MutableStateFlow<W4RPException?>(null)
    /** Last error that occurred, cleared on successful operations */
    val lastError: StateFlow<W4RPException?> = _lastError.asStateFlow()
    
    private val _discoveredDevices = MutableStateFlow<List<W4RPDevice>>(emptyList())
    /** List of discovered devices during scanning */
    val discoveredDevices: StateFlow<List<W4RPDevice>> = _discoveredDevices.asStateFlow()
    
    /** Convenience property for backward compatibility */
    val isConnected: Boolean get() = _connectionState.value == W4RPConnectionState.READY
    
    /** Convenience property for backward compatibility */
    val isScanning: Boolean get() = _connectionState.value == W4RPConnectionState.SCANNING
    
    // -------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------
    
    var onStatusUpdate: ((String) -> Unit)? = null
    var onDebugData: ((W4RPDebugData) -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null
    
    // Auto-reconnect Callbacks
    /** Called when auto-reconnect starts (provides attempt number) */
    var onReconnecting: ((attempt: Int) -> Unit)? = null
    /** Called when auto-reconnect succeeds */
    var onReconnected: (() -> Unit)? = null
    /** Called when auto-reconnect fails after all attempts */
    var onReconnectFailed: ((error: W4RPException) -> Unit)? = null
    
    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------
    
    /** Auto-reconnect configuration (default: disabled) */
    var autoReconnectConfig: W4RPAutoReconnectConfig = W4RPAutoReconnectConfig.DISABLED
    
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
    
    // Auto-reconnect State
    private var lastConnectedDevice: W4RPDevice? = null
    private var isAutoReconnecting = false
    private var lifetimeReconnectCount = 0
    private var wasIntentionalDisconnect = false
    
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
            
            _connectionState.value = W4RPConnectionState.SCANNING
            _lastError.value = null
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
        if (_connectionState.value == W4RPConnectionState.READY) {
            throw W4RPException(W4RPErrorCode.ALREADY_CONNECTED, "Already connected")
        }
        
        stopScan()
        _connectionState.value = W4RPConnectionState.CONNECTING
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
                if (_connectionState.value != W4RPConnectionState.READY) {
                    bluetoothGatt?.close()
                    _connectionState.value = W4RPConnectionState.ERROR
                    val err = W4RPException(W4RPErrorCode.CONNECTION_TIMEOUT, "Connection timed out")
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
     * Connect to a device with automatic retry using exponential backoff.
     *
     * This method wraps `connect()` and automatically retries on failure using
     * exponential backoff delays (e.g., 1s → 2s → 4s → 8s).
     *
     * @param device Device to connect to
     * @param timeoutMs Connection timeout in milliseconds per attempt (default: 10000)
     * @param retryConfig Retry configuration (default: 3 retries, 1s base delay)
     * @param onRetry Optional callback invoked before each retry attempt with (attempt, delayMs, error)
     * @throws W4RPException if all attempts fail
     *
     * ```kotlin
     * bridge.connectWithRetry(device) { attempt, delayMs, error ->
     *     Log.d("W4RP", "Retry $attempt after ${delayMs}ms: ${error.message}")
     * }
     * ```
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connectWithRetry(
        device: W4RPDevice,
        timeoutMs: Long = 10000,
        retryConfig: W4RPRetryConfig = W4RPRetryConfig.DEFAULT,
        onRetry: ((attempt: Int, delayMs: Long, error: W4RPException) -> Unit)? = null
    ) {
        var lastError: W4RPException? = null
        
        for (attempt in 0..retryConfig.maxRetries) {
            try {
                connect(device, timeoutMs)
                return // Success
            } catch (error: W4RPException) {
                lastError = error
                
                // Don't retry for certain errors
                if (error.errorCode == W4RPErrorCode.ALREADY_CONNECTED) {
                    throw error
                }
                
                // If we've exhausted retries, throw
                if (attempt >= retryConfig.maxRetries) {
                    throw error
                }
                
                // Calculate delay and wait
                val delayMs = retryConfig.delayForAttempt(attempt)
                onRetry?.invoke(attempt + 1, delayMs, error)
                
                // Ensure we're in a clean state before retrying
                _connectionState.value = W4RPConnectionState.DISCONNECTED
                
                delay(delayMs)
            }
        }
        
        // Should not reach here, but safety fallback
        lastError?.let { throw it }
    }
    
    /**
     * Disconnect from the current device.
     *
     * @throws W4RPException if disconnection fails
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun disconnect() {
        if (bluetoothGatt == null) return
        
        wasIntentionalDisconnect = true
        _connectionState.value = W4RPConnectionState.DISCONNECTING
        
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
     * @param onProgress Optional callback reporting upload progress (bytesWritten, totalBytes)
     * @throws W4RPException if not connected or write fails
     */
    suspend fun setRules(
        json: String,
        persistent: Boolean,
        onProgress: ((bytesWritten: Int, totalBytes: Int) -> Unit)? = null
    ) {
        ensureConnected()
        val gatt = bluetoothGatt ?: throw W4RPException(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        val rx = rxCharacteristic ?: throw W4RPException(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        
        val data = json.toByteArray(Charsets.UTF_8)
        val crc = calculateCRC32(data)
        val mode = if (persistent) "NVS" else "RAM"
        val header = "SET:RULES:$mode:${data.size}:$crc"
        
        writeData(gatt, rx, header.toByteArray(Charsets.UTF_8))
        sendChunked(gatt, rx, data, onProgress = onProgress)
        writeData(gatt, rx, "END".toByteArray(Charsets.UTF_8))
    }
    
    /**
     * Start a Delta OTA firmware update.
     *
     * @param patchData Binary patch data (Janpatch format)
     * @param onProgress Optional callback reporting upload progress (bytesWritten, totalBytes)
     * @throws W4RPException if not connected or OTA fails
     */
    suspend fun startOTA(
        patchData: ByteArray,
        onProgress: ((bytesWritten: Int, totalBytes: Int) -> Unit)? = null
    ) {
        ensureConnected()
        val gatt = bluetoothGatt ?: throw W4RPException(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        val rx = rxCharacteristic ?: throw W4RPException(W4RPErrorCode.NOT_CONNECTED, "Not connected")
        
        val crc = calculateCRC32(patchData)
        val cmd = "OTA:BEGIN:DELTA:${patchData.size}:${String.format("%X", crc)}"
        
        writeData(gatt, rx, cmd.toByteArray(Charsets.UTF_8))
        delay(200)
        sendChunked(gatt, rx, patchData, onProgress = onProgress)
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
    
    fun setRulesWithCallback(
        json: String,
        persistent: Boolean,
        onProgress: ((bytesWritten: Int, totalBytes: Int) -> Unit)? = null,
        completion: (Result<Unit>) -> Unit
    ) {
        scope.launch {
            try {
                setRules(json, persistent, onProgress)
                completion(Result.success(Unit))
            } catch (e: W4RPException) {
                completion(Result.failure(e))
            }
        }
    }
    
    fun startOTAWithCallback(
        patchData: ByteArray,
        onProgress: ((bytesWritten: Int, totalBytes: Int) -> Unit)? = null,
        completion: (Result<Unit>) -> Unit
    ) {
        scope.launch {
            try {
                startOTA(patchData, onProgress)
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
        if (_connectionState.value == W4RPConnectionState.SCANNING) {
            _connectionState.value = W4RPConnectionState.DISCONNECTED
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
                        _connectionState.value = W4RPConnectionState.DISCOVERING_SERVICES
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val previousState = _connectionState.value
                        _connectionState.value = W4RPConnectionState.DISCONNECTED
                        rxCharacteristic = null
                        txCharacteristic = null
                        statusCharacteristic = null
                        bluetoothGatt?.close()
                        bluetoothGatt = null
                        
                        // Resume any pending disconnect continuation
                        disconnectContinuation?.resume(Unit)
                        disconnectContinuation = null
                        onDisconnect?.invoke()
                        
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
                                val err = W4RPException(W4RPErrorCode.CONNECTION_FAILED, "Max lifetime reconnects exceeded")
                                onReconnectFailed?.invoke(err)
                                wasIntentionalDisconnect = false
                                return@post
                            }
                            
                            // Start auto-reconnect
                            isAutoReconnecting = true
                            
                            scope.launch {
                                var lastReconnectError: W4RPException? = null
                                
                                for (attempt in 0..autoReconnectConfig.retryConfig.maxRetries) {
                                    lifetimeReconnectCount++
                                    onReconnecting?.invoke(attempt + 1)
                                    
                                    try {
                                        connect(device)
                                        isAutoReconnecting = false
                                        onReconnected?.invoke()
                                        return@launch
                                    } catch (error: W4RPException) {
                                        lastReconnectError = error
                                        
                                        if (attempt < autoReconnectConfig.retryConfig.maxRetries) {
                                            val delayMs = autoReconnectConfig.retryConfig.delayForAttempt(attempt)
                                            delay(delayMs)
                                        }
                                    }
                                }
                                
                                isAutoReconnecting = false
                                onReconnectFailed?.invoke(lastReconnectError ?: W4RPException(W4RPErrorCode.CONNECTION_FAILED, "Auto-reconnect failed"))
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
                    _connectionState.value = W4RPConnectionState.ERROR
                    val err = W4RPException(W4RPErrorCode.SERVICE_NOT_FOUND, "Service discovery failed")
                    _lastError.value = err
                    connectContinuation?.resumeWithException(err)
                    connectContinuation = null
                    return@post
                }
                
                val service = gatt.getService(W4RPUUIDs.SERVICE)
                if (service == null) {
                    _connectionState.value = W4RPConnectionState.ERROR
                    val err = W4RPException(W4RPErrorCode.SERVICE_NOT_FOUND, "W4RP service not found")
                    _lastError.value = err
                    connectContinuation?.resumeWithException(err)
                    connectContinuation = null
                    return@post
                }
                
                rxCharacteristic = service.getCharacteristic(W4RPUUIDs.RX)
                txCharacteristic = service.getCharacteristic(W4RPUUIDs.TX)
                statusCharacteristic = service.getCharacteristic(W4RPUUIDs.STATUS)
                
                txCharacteristic?.let { enableNotifications(gatt, it) }
                statusCharacteristic?.let { enableNotifications(gatt, it) }
                
                if (rxCharacteristic != null && txCharacteristic != null && statusCharacteristic != null) {
                    _connectionState.value = W4RPConnectionState.READY
                    _lastError.value = null
                    connectContinuation?.resume(Unit)
                    connectContinuation = null
                } else {
                    _connectionState.value = W4RPConnectionState.ERROR
                    val err = W4RPException(W4RPErrorCode.CHARACTERISTIC_NOT_FOUND, "Characteristics not found")
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
