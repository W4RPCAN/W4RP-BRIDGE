/**
 * BridgeTypes.ts
 *
 * Type Definitions, Enums, and Shared Configurations for W4RP Web Bluetooth.
 *
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 2.0.0
 */

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
export const W4RP_UUIDS = {
    /** Primary GATT Service UUID - filter for this during scanning */
    SERVICE: '0000fff0-5734-5250-5734-525000000000',

    /** RX Characteristic - Write commands to the module */
    RX: '0000fff1-5734-5250-5734-525000000000',

    /** TX Characteristic - Receive data from module (Notify) */
    TX: '0000fff2-5734-5250-5734-525000000000',

    /** Status Characteristic - Module status updates (Notify) */
    STATUS: '0000fff3-5734-5250-5734-525000000000',
} as const;

// =============================================================================
// Error Handling
// =============================================================================

/**
 * Standardized Bluetooth Availability Status.
 * Unified across iOS (CBManagerState), Android (BluetoothAdapter), and Web (Navigator.bluetooth).
 */
export enum W4RPBluetoothStatus {
    /** Bluetooth is on and ready to use */
    READY = 'READY',
    /** Bluetooth is disabled (powered off) */
    POWERED_OFF = 'POWERED_OFF',
    /** App lacks permission to use Bluetooth */
    UNAUTHORIZED = 'UNAUTHORIZED',
    /** Device does not support Bluetooth or Web Bluetooth API is missing */
    UNSUPPORTED = 'UNSUPPORTED',
    /** Bluetooth temporarily unavailable (resetting/turning on/off) */
    UNAVAILABLE = 'UNAVAILABLE',
    /** State cannot be determined */
    UNKNOWN = 'UNKNOWN',
}

/**
 * Standardized error codes for W4RP operations.
 * 
 * Error code ranges:
 * - 1xxx: BLE Infrastructure errors (connection, scanning, permissions)
 * - 2xxx: Protocol errors (write failures, invalid responses)
 * - 3xxx: Data validation errors (CRC mismatch, length mismatch)
 * - 4xxx: Timeout errors (profile, stream, connection)
 */
export enum W4RPErrorCode {
    // -------------------------------------------------------------------------
    // 1000-1099: Connection Errors
    // -------------------------------------------------------------------------

    /** GATT connection failed */
    CONNECTION_FAILED = 1000,

    /** Connection was lost unexpectedly */
    CONNECTION_LOST = 1001,

    /** Operation requires an active connection */
    NOT_CONNECTED = 1002,

    /** Already connected to a device */
    ALREADY_CONNECTED = 1003,

    // -------------------------------------------------------------------------
    // 1100-1199: Discovery Errors
    // -------------------------------------------------------------------------

    /** No device was found or selected */
    DEVICE_NOT_FOUND = 1100,

    /** W4RP service not found on device */
    SERVICE_NOT_FOUND = 1101,

    /** Required characteristic not found */
    CHARACTERISTIC_NOT_FOUND = 1102,

    /** Scan was cancelled */
    SCAN_CANCELLED = 1103,

    // -------------------------------------------------------------------------
    // 1200-1299: Bluetooth State Errors
    // -------------------------------------------------------------------------

    /** Bluetooth adapter is powered off */
    BLUETOOTH_OFF = 1200,

    /** Bluetooth permission denied by user or system */
    BLUETOOTH_UNAUTHORIZED = 1201,

    /** Web Bluetooth API not supported in this browser */
    BLUETOOTH_UNSUPPORTED = 1202,

    // -------------------------------------------------------------------------
    // 2000-2099: Protocol Errors
    // -------------------------------------------------------------------------

    /** Module returned an invalid or unexpected response */
    INVALID_RESPONSE = 2000,

    /** Failed to write data to the RX characteristic */
    WRITE_FAILED = 2001,

    /** Failed to read data */
    READ_FAILED = 2002,

    // -------------------------------------------------------------------------
    // 3000-3099: Data Validation Errors
    // -------------------------------------------------------------------------

    /** Data format is invalid (e.g., not valid UTF-8) */
    INVALID_DATA = 3000,

    /** CRC32 checksum does not match expected value */
    CRC_MISMATCH = 3001,

    /** Received data length does not match expected length */
    LENGTH_MISMATCH = 3002,

    // -------------------------------------------------------------------------
    // 4000-4099: Timeout Errors
    // -------------------------------------------------------------------------

    /** Device scan timed out with no results */
    SCAN_TIMEOUT = 4000,

    /** Connection attempt timed out */
    CONNECTION_TIMEOUT = 4001,

    /** Module profile request timed out */
    PROFILE_TIMEOUT = 4002,

    /** Data stream timed out before completion */
    STREAM_TIMEOUT = 4003,

    // -------------------------------------------------------------------------
    // 5000-5099: OTA Errors
    // -------------------------------------------------------------------------

    /** OTA update was cancelled */
    OTA_CANCELLED = 5000,
}

/**
 * Custom error class for W4RP operations.
 * Includes error code, message, and optional context for debugging.
 */
export class W4RPError extends Error {
    public readonly name = 'W4RPError';

    constructor(
        /** Standardized error code */
        public readonly code: W4RPErrorCode,
        /** Human-readable error message */
        message: string,
        /** Additional context for debugging */
        public readonly context?: Record<string, unknown>
    ) {
        super(message);
        Object.setPrototypeOf(this, W4RPError.prototype);
    }

    /** Convert to plain object for logging or transmission */
    toJSON(): Record<string, unknown> {
        return {
            name: this.name,
            code: this.code,
            message: this.message,
            context: this.context,
        };
    }
}

// =============================================================================
// Type Definitions
// =============================================================================

/**
 * Module profile returned by GET:PROFILE command.
 * Contains device identification and registered capabilities.
 */
export interface W4RPModuleProfile {
    module: {
        /** Unique module identifier (e.g., "W4RP-A1B2C3") */
        id: string;
        /** Hardware revision (e.g., "esp32c3-mini-1") */
        hw?: string;
        /** Firmware version (e.g., "1.0.0") */
        fw?: string;
        /** Human-readable device name */
        device_name?: string;
        /** Serial number */
        serial?: string;
    };
    /** Registered capabilities (actions the module can perform) */
    capabilities?: Record<string, W4RPCapability>;
    /** Runtime configuration */
    runtime?: {
        /** Current rules storage mode: "nvs" or "ram" */
        mode?: string;
        /** Uptime in milliseconds */
        uptime_ms?: number;
        /** Number of boots */
        boot_count?: number;
    };
    /** Rules engine state */
    rules?: {
        /** Rules dialect */
        dialect?: string;
        /** Rules CRC32 */
        crc32?: number;
        /** Last update timestamp */
        last_update?: string;
        /** Raw rules data */
        data?: any;
    };
    /** BLE status */
    ble?: {
        /** Connection status */
        connected?: boolean;
        /** Received Signal Strength Indicator */
        rssi?: number;
        /** Maximum Transmission Unit */
        mtu?: number;
    };
    /** System limits */
    limits?: {
        /** Maximum number of signals */
        max_signals?: number;
        /** Maximum number of nodes */
        max_nodes?: number;
        /** Maximum number of flows */
        max_flows?: number;
    };
}

/**
 * A capability represents an action the module can perform.
 * Used by apps to dynamically generate UI controls.
 */
export interface W4RPCapability {
    /** Human-readable label (e.g., "Exhaust Valve") */
    label?: string;
    /** Description of the capability */
    description?: string;
    /** Category for grouping (e.g., "output", "input", "debug") */
    category?: string;
    /** Parameters this capability accepts */
    params?: W4RPCapabilityParam[];
}

/**
 * Parameter definition for a capability.
 * Apps use this to render appropriate UI controls.
 */
export interface W4RPCapabilityParam {
    /** Parameter identifier */
    name: string;
    /** Data type: "int", "float", "bool", "string" */
    type: string;
    /** Is this parameter required? */
    required?: boolean;
    /** Minimum value (for numeric types) */
    min?: number;
    /** Maximum value (for numeric types) */
    max?: number;
    /** Human-readable description */
    description?: string;
}

/**
 * Debug data received from the module.
 * Used for real-time signal monitoring.
 */
export interface W4RPDebugData {
    /** Signal or node identifier */
    id: string;
    /** Data type: "signal" or "node" */
    type: 'signal' | 'node';
    /** Current value (for signals) */
    value?: number;
    /** Active state (for nodes) */
    active?: boolean;
}

/**
 * Callback handlers for asynchronous events.
 * Matches Swift and Android implementations.
 */
export interface W4RPBridgeCallbacks {
    /** Called when connection state changes (DISCONNECTED, SCANNING, CONNECTING, etc.) */
    onConnectionStateChanged?: (state: W4RPConnectionState) => void;
    /** Called when device(s) are discovered during scanning */
    onDeviceDiscovered?: (devices: W4RPDevice[]) => void;
    /** Called when module sends a status update JSON */
    onStatusUpdate?: (json: string) => void;
    /** Called when debug data is received */
    onDebugData?: (data: W4RPDebugData) => void;
    /** Called when an error occurs */
    onError?: (error: W4RPError) => void;
    /** Called when connection is lost (legacy - prefer onConnectionStateChanged) */
    onDisconnect?: () => void;
    /** Called when Bluetooth adapter availability status changes */
    onBluetoothStatusChanged?: (status: W4RPBluetoothStatus) => void;
}

/**
 * Standardized device representation.
 * Used across iOS, Android, and Web platforms.
 */
export interface W4RPDevice {
    /** Unique device identifier */
    id: string;
    /** Device name (from BLE advertisement) */
    name: string;
    /** Signal strength in dBm (0 if not available) */
    rssi: number;
    /** Advertisement data from BLE scan (optional, platform-dependent) */
    advertisementData?: Record<string, unknown>;
}

/**
 * Progress callback for upload operations.
 * @param bytesWritten - Bytes written so far
 * @param totalBytes - Total bytes to write
 */
export type W4RPProgressCallback = (bytesWritten: number, totalBytes: number) => void;

// =============================================================================
// Connection State Machine
// =============================================================================

/**
 * Represents the current state of the BLE connection.
 *
 * State transitions:
 * - DISCONNECTED -> CONNECTING (via connect())
 * - CONNECTING -> DISCOVERING_SERVICES (GATT connected)
 * - DISCOVERING_SERVICES -> READY (characteristics found)
 * - READY -> DISCONNECTING (via disconnect())
 * - DISCONNECTING -> DISCONNECTED (complete)
 * - ANY -> ERROR (on failure)
 */
export enum W4RPConnectionState {
    /** Not connected to any device */
    DISCONNECTED = 'DISCONNECTED',

    /** Scanning for nearby W4RP devices */
    SCANNING = 'SCANNING',

    /** Initiating GATT connection to a device */
    CONNECTING = 'CONNECTING',

    /** Connected, discovering services and characteristics */
    DISCOVERING_SERVICES = 'DISCOVERING_SERVICES',

    /** Fully connected and ready for operations */
    READY = 'READY',

    /** Disconnection in progress */
    DISCONNECTING = 'DISCONNECTING',

    /** An error occurred (check lastError for details) */
    ERROR = 'ERROR',
}

// =============================================================================
// Retry Configuration
// =============================================================================

/**
 * Configuration for exponential backoff retry logic.
 *
 * Example usage:
 * ```typescript
 * const config: W4RPRetryConfig = { maxRetries: 3, baseDelayMs: 1000 };
 * await bridge.connectWithRetry(config);
 * ```
 */
export interface W4RPRetryConfig {
    /** Maximum number of retry attempts (0 = no retries, just the initial attempt) */
    maxRetries: number;

    /** Base delay in milliseconds before first retry (default: 1000) */
    baseDelayMs: number;

    /** Maximum delay cap in milliseconds (default: 16000) */
    maxDelayMs: number;

    /** Multiplier for exponential growth (default: 2.0) */
    multiplier: number;
}

/** Default retry configuration: 3 retries, 1s base, 16s max, 2x multiplier */
export const W4RP_DEFAULT_RETRY_CONFIG: W4RPRetryConfig = {
    maxRetries: 3,
    baseDelayMs: 1000,
    maxDelayMs: 16000,
    multiplier: 2.0,
};

/**
 * Calculate delay for a given attempt using exponential backoff.
 * @param config Retry configuration
 * @param attempt Attempt number (0-indexed)
 * @returns Delay in milliseconds
 */
export function calculateRetryDelay(config: W4RPRetryConfig, attempt: number): number {
    const delay = config.baseDelayMs * Math.pow(config.multiplier, attempt);
    return Math.min(delay, config.maxDelayMs);
}

/** Callback type for retry events */
export type W4RPRetryCallback = (attempt: number, delayMs: number, error: W4RPError) => void;

// =============================================================================
// Auto-Reconnect Configuration
// =============================================================================

/**
 * Configuration for automatic reconnection when connection is lost unexpectedly.
 *
 * When enabled, the bridge will attempt to reconnect automatically if the connection
 * is lost (e.g., device goes out of range). The reconnection uses exponential backoff.
 *
 * IMPORTANT: Due to Web Bluetooth security requirements, auto-reconnect may not work
 * in all browsers as reconnection typically requires a new user gesture.
 *
 * @example
 * ```typescript
 * bridge.autoReconnectConfig = { enabled: true, retryConfig: W4RP_DEFAULT_RETRY_CONFIG };
 * bridge.onReconnecting = (attempt) => console.log(`Reconnecting attempt ${attempt}...`);
 * bridge.onReconnected = () => console.log('Reconnected!');
 * bridge.onReconnectFailed = (error) => console.error('Failed:', error);
 * ```
 */
export interface W4RPAutoReconnectConfig {
    /** Whether auto-reconnect is enabled (default: false) */
    enabled: boolean;

    /** Retry configuration for reconnection attempts */
    retryConfig: W4RPRetryConfig;

    /** Maximum total reconnection attempts across all disconnections (0 = unlimited) */
    maxLifetimeReconnects: number;
}

/** Disabled auto-reconnect configuration (default) */
export const W4RP_DISABLED_AUTO_RECONNECT: W4RPAutoReconnectConfig = {
    enabled: false,
    retryConfig: W4RP_DEFAULT_RETRY_CONFIG,
    maxLifetimeReconnects: 0,
};

/** Default enabled auto-reconnect configuration: up to 5 reconnect attempts */
export const W4RP_DEFAULT_AUTO_RECONNECT: W4RPAutoReconnectConfig = {
    enabled: true,
    retryConfig: { ...W4RP_DEFAULT_RETRY_CONFIG, maxRetries: 5, baseDelayMs: 2000 },
    maxLifetimeReconnects: 0,
};

/** Callback type for reconnect events */
export type W4RPReconnectCallback = (attempt: number) => void;
