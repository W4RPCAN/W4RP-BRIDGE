/**
 * W4RP Bridge - Type Definitions
 *
 * Centralized types, enums, and error definitions for the W4RP BLE bridge.
 * All public types are pure data transfer objects (DTOs).
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
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 2.0.0
 */

package com.w4rp.bridge

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import java.util.UUID

// =============================================================================
// UUID Configuration
// =============================================================================

/**
 * W4RP BLE Service and Characteristic UUIDs.
 * Stores raw UUID strings - conversion happens on demand.
 */
data class W4RPUUIDs(
    val service: String,
    val rx: String,
    val tx: String,
    val status: String
) {
    companion object {
        val DEFAULT = W4RPUUIDs(
            service = "0000fff0-5734-5250-5734-525000000000",
            rx      = "0000fff1-5734-5250-5734-525000000000",
            tx      = "0000fff2-5734-5250-5734-525000000000",
            status  = "0000fff3-5734-5250-5734-525000000000"
        )
    }
}

// =============================================================================
// Error Handling
// =============================================================================

/**
 * Standardized error codes for W4RP operations.
 *
 * Code Ranges:
 * - 1000-1099: Connection errors
 * - 1100-1199: Discovery errors
 * - 1200-1299: Bluetooth state errors
 * - 2000-2099: Protocol errors
 * - 3000-3099: Data validation errors
 * - 4000-4099: Timeout errors
 */
enum class W4RPErrorCode(val code: Int) {
    // 1000-1099: Connection errors
    CONNECTION_FAILED(1000),
    CONNECTION_LOST(1001),
    NOT_CONNECTED(1002),
    ALREADY_CONNECTED(1003),
    
    // 1100-1199: Discovery errors
    DEVICE_NOT_FOUND(1100),
    SERVICE_NOT_FOUND(1101),
    CHARACTERISTIC_NOT_FOUND(1102),
    SCAN_CANCELLED(1103),
    
    // 1200-1299: Bluetooth state errors
    BLUETOOTH_OFF(1200),
    BLUETOOTH_UNAUTHORIZED(1201),
    BLUETOOTH_UNSUPPORTED(1202),
    
    // 2000-2099: Protocol errors
    INVALID_RESPONSE(2000),
    WRITE_FAILED(2001),
    READ_FAILED(2002),
    
    // 3000-3099: Data validation errors
    INVALID_DATA(3000),
    CRC_MISMATCH(3001),
    LENGTH_MISMATCH(3002),
    
    // 4000-4099: Timeout errors
    SCAN_TIMEOUT(4000),
    CONNECTION_TIMEOUT(4001),
    PROFILE_TIMEOUT(4002),
    STREAM_TIMEOUT(4003),
    
    // 5000-5099: OTA errors
    OTA_CANCELLED(5000)
}

/**
 * Standardized Bluetooth Availability Status.
 * Unified across iOS, Android, and Web.
 */
enum class W4RPBluetoothStatus {
    /** Bluetooth is on and ready to use */
    READY,
    /** Bluetooth is disabled (powered off) */
    POWERED_OFF,
    /** App lacks permission to use Bluetooth */
    UNAUTHORIZED,
    /** Device does not support Bluetooth */
    UNSUPPORTED,
    /** Bluetooth temporarily unavailable */
    UNAVAILABLE,
    /** State cannot be determined */
    UNKNOWN
}

/**
 * W4RP operation error.
 */
class W4RPError(
    val code: W4RPErrorCode,
    override val message: String,
    val context: Map<String, String>? = null
) : Exception(message)

// =============================================================================
// Data Models
// =============================================================================

/**
 * Discovered W4RP BLE device.
 */
data class W4RPDevice(
    val device: BluetoothDevice,
    val name: String,
    var rssi: Int,
    val scanRecord: ScanRecord?
) {
    /** Device ID for JS interop */
    val id: String get() = device.address
    
    /** Convert to JSON-safe map for JS bridge */
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to device.address,
        "name" to name,
        "rssi" to rssi
    )
}

/**
 * W4RP module profile containing hardware/firmware info and capabilities.
 */
data class W4RPModuleProfile(
    val module: W4RPModuleInfo,
    val capabilities: Map<String, W4RPCapability>?,
    val runtime: W4RPRuntimeInfo?,
    val rules: W4RPRulesInfo?,
    val ble: W4RPBLEInfo?,
    val limits: W4RPLimitsInfo?
)

data class W4RPModuleInfo(
    val id: String,
    val hw: String?,
    val fw: String?,
    val device_name: String?,
    val serial: String?
)

data class W4RPCapability(
    val label: String?,
    val description: String?,
    val category: String?,
    val params: List<W4RPCapabilityParam>?
)

data class W4RPCapabilityParam(
    val name: String,
    val type: String,
    val required: Boolean?,
    val min: Int?,
    val max: Int?,
    val description: String?
)

data class W4RPRuntimeInfo(
    val mode: String?,
    val uptime_ms: Int?,
    val boot_count: Int?
)

data class W4RPRulesInfo(
    val dialect: String?,
    val crc32: Long?,
    val last_update: String?,
    val data: Any?
)

data class W4RPBLEInfo(
    val connected: Boolean?,
    val rssi: Int?,
    val mtu: Int?
)

data class W4RPLimitsInfo(
    val max_signals: Int?,
    val max_nodes: Int?,
    val max_flows: Int?
)

/**
 * Debug stream data from module.
 * Mirrored exactly from Swift's struct pattern for parity.
 */
data class W4RPDebugData(
    val id: String,
    val type: DebugType,
    val value: Float? = null,
    val active: Boolean? = null
) {
    enum class DebugType(val value: String) {
        SIGNAL("signal"),
        NODE("node")
    }
}

// =============================================================================
// Connection State Machine
// =============================================================================

/**
 * BLE connection state machine.
 *
 * State Transitions:
 * - DISCONNECTED → SCANNING → DISCONNECTED
 * - DISCONNECTED → CONNECTING → DISCOVERING_SERVICES → READY
 * - READY → DISCONNECTING → DISCONNECTED
 * - ANY → ERROR → DISCONNECTED
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
    
    /** True if connection is fully established and ready. */
    val isReady: Boolean get() = this == READY
    
    /** True if any async operation is in progress. */
    val isBusy: Boolean get() = when (this) {
        SCANNING, CONNECTING, DISCOVERING_SERVICES, DISCONNECTING -> true
        else -> false
    }
}

// =============================================================================
// Configuration
// =============================================================================

/**
 * Exponential backoff retry configuration.
 */
data class W4RPRetryConfig(
    val maxRetries: Int = 3,
    val baseDelayMs: Long = 1000L,
    val maxDelayMs: Long = 16000L,
    val multiplier: Double = 2.0
) {
    companion object {
        val DEFAULT = W4RPRetryConfig()
    }
    
    /** Calculate delay for attempt (0-indexed). */
    fun delayForAttempt(attempt: Int): Long {
        val delay = (baseDelayMs * kotlin.math.pow(multiplier, attempt.toDouble())).toLong()
        return minOf(delay, maxDelayMs)
    }
}

/**
 * Automatic reconnection configuration.
 */
data class W4RPAutoReconnectConfig(
    val enabled: Boolean = false,
    val retryConfig: W4RPRetryConfig = W4RPRetryConfig.DEFAULT,
    val maxLifetimeReconnects: Int = 0
) {
    companion object {
        val DISABLED = W4RPAutoReconnectConfig(enabled = false)
        val DEFAULT = W4RPAutoReconnectConfig(
            enabled = true,
            retryConfig = W4RPRetryConfig(maxRetries = 5, baseDelayMs = 2000)
        )
    }
}
