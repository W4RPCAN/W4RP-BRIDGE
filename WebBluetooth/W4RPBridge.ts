/**
 * W4RPBridge.ts
 * 
 * W4RP Bridge - Web Bluetooth API Implementation
 * Official client library for connecting to W4RPBLE firmware modules.
 * 
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 1.1.0
 * 
 * Compatible with:
 * - Chrome 56+ (Desktop & Android)
 * - Edge 79+
 * - Opera 43+
 * 
 * NOT compatible with:
 * - Firefox (Web Bluetooth not implemented)
 * - Safari / iOS (Web Bluetooth not implemented)
 * 
 * @see https://github.com/W4RPCAN/W4RPBLE for firmware source
 * @see https://developer.mozilla.org/en-US/docs/Web/API/Web_Bluetooth_API
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
    // 1xxx: BLE Infrastructure
    // -------------------------------------------------------------------------

    /** GATT connection failed */
    CONNECTION_FAILED = 1000,

    /** Connection was lost unexpectedly */
    CONNECTION_LOST = 1001,

    /** Operation requires an active connection */
    NOT_CONNECTED = 1002,

    /** Already connected to a device */
    ALREADY_CONNECTED = 1003,

    /** No device was found or selected */
    DEVICE_NOT_FOUND = 1004,

    /** W4RP service not found on device */
    SERVICE_NOT_FOUND = 1005,

    /** Required characteristic not found */
    CHARACTERISTIC_NOT_FOUND = 1006,

    /** Bluetooth adapter is powered off */
    BLUETOOTH_OFF = 1007,

    /** Bluetooth permission denied by user or system */
    BLUETOOTH_UNAUTHORIZED = 1008,

    /** Web Bluetooth API not supported in this browser */
    BLUETOOTH_UNSUPPORTED = 1009,

    // -------------------------------------------------------------------------
    // 2xxx: Protocol Errors
    // -------------------------------------------------------------------------

    /** Module returned an invalid or unexpected response */
    INVALID_RESPONSE = 2000,

    /** Failed to write data to the RX characteristic */
    WRITE_FAILED = 2003,

    // -------------------------------------------------------------------------
    // 3xxx: Data Validation Errors
    // -------------------------------------------------------------------------

    /** Data format is invalid (e.g., not valid UTF-8) */
    INVALID_DATA = 3000,

    /** CRC32 checksum does not match expected value */
    CRC_MISMATCH = 3001,

    /** Received data length does not match expected length */
    LENGTH_MISMATCH = 3002,

    // -------------------------------------------------------------------------
    // 4xxx: Timeout Errors
    // -------------------------------------------------------------------------

    /** Module profile request timed out */
    PROFILE_TIMEOUT = 4000,

    /** Data stream timed out before completion */
    STREAM_TIMEOUT = 4001,

    /** Device scan timed out with no results */
    SCAN_TIMEOUT = 4002,

    /** Connection attempt timed out */
    CONNECTION_TIMEOUT = 4003,
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
    };
    /** Registered capabilities (actions the module can perform) */
    capabilities?: Record<string, W4RPCapability>;
    /** Runtime configuration */
    runtime?: {
        /** Current rules storage mode: "nvs" or "ram" */
        mode?: string;
    };
}

/**
 * A capability represents an action the module can perform.
 * Used by apps to dynamically generate UI controls.
 */
export interface W4RPCapability {
    /** Human-readable label (e.g., "Exhaust Valve") */
    label?: string;
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
 */
export interface W4RPBridgeCallbacks {
    /** Called when module sends a status update JSON */
    onStatusUpdate?: (json: string) => void;
    /** Called when debug data is received */
    onDebugData?: (data: W4RPDebugData) => void;
    /** Called when connection is lost */
    onDisconnect?: () => void;
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

// =============================================================================
// Main Bridge Class
// =============================================================================

/**
 * W4RPBridge - Web Bluetooth client for W4RPBLE modules.
 * 
 * @example
 * ```typescript
 * const bridge = new W4RPBridge();
 * 
 * // Must be triggered by user gesture (button click)
 * await bridge.connect();
 * 
 * const profile = await bridge.getProfile();
 * console.log('Connected to:', profile.module.id);
 * 
 * await bridge.setRules(rulesJson, true); // Persistent
 * ```
 */
export class W4RPBridge {
    // BLE State
    private device: BluetoothDevice | null = null;
    private server: BluetoothRemoteGATTServer | null = null;
    private rxCharacteristic: BluetoothRemoteGATTCharacteristic | null = null;
    private txCharacteristic: BluetoothRemoteGATTCharacteristic | null = null;
    private statusCharacteristic: BluetoothRemoteGATTCharacteristic | null = null;

    // Connection State
    private _connectionState: W4RPConnectionState = W4RPConnectionState.DISCONNECTED;
    private _lastError: W4RPError | null = null;

    // Stream State (for receiving large payloads)
    private streamActive = false;
    private streamBuffer: Uint8Array = new Uint8Array(0);
    private streamExpectedLen = 0;
    private streamExpectedCRC = 0;
    private streamResolve: ((data: Uint8Array) => void) | null = null;
    private streamReject: ((error: W4RPError) => void) | null = null;
    private streamTimeoutId: ReturnType<typeof setTimeout> | null = null;

    // Auto-reconnect State
    private wasIntentionalDisconnect = false;
    private isAutoReconnecting = false;
    private lifetimeReconnectCount = 0;

    // Event Callbacks
    private callbacks: W4RPBridgeCallbacks = {};

    // ---------------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------------

    /** Auto-reconnect configuration (default: disabled) */
    autoReconnectConfig: W4RPAutoReconnectConfig = W4RP_DISABLED_AUTO_RECONNECT;

    // ---------------------------------------------------------------------------
    // Auto-Reconnect Callbacks
    // ---------------------------------------------------------------------------

    /** Called when auto-reconnect starts (provides attempt number) */
    onReconnecting: W4RPReconnectCallback | null = null;
    /** Called when auto-reconnect succeeds */
    onReconnected: (() => void) | null = null;
    /** Called when auto-reconnect fails after all attempts */
    onReconnectFailed: ((error: W4RPError) => void) | null = null;

    // ---------------------------------------------------------------------------
    // Public Getters
    // ---------------------------------------------------------------------------

    /** Current connection state */
    get connectionState(): W4RPConnectionState {
        return this._connectionState;
    }

    /** Last error that occurred, cleared on successful operations */
    get lastError(): W4RPError | null {
        return this._lastError;
    }

    /** Whether the bridge is currently connected (convenience property) */
    get isConnected(): boolean {
        return this._connectionState === W4RPConnectionState.READY;
    }

    /** Name of the connected device, or null if not connected */
    get connectedDeviceName(): string | null {
        return this.device?.name ?? null;
    }

    // ---------------------------------------------------------------------------
    // Static Methods
    // ---------------------------------------------------------------------------

    /**
     * Check if Web Bluetooth is supported in the current browser.
     * Always call this before attempting to connect.
     */
    static isSupported(): boolean {
        return typeof navigator !== 'undefined' && 'bluetooth' in navigator;
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Set callback handlers for asynchronous events.
     * 
     * @param callbacks - Object containing event handlers
     */
    setCallbacks(callbacks: W4RPBridgeCallbacks): void {
        this.callbacks = callbacks;
    }

    /**
     * Request and connect to a W4RP device.
     * 
     * IMPORTANT: This method MUST be called from a user gesture (e.g., button click)
     * due to Web Bluetooth security requirements.
     * 
     * @throws {W4RPError} If connection fails or is not supported
     */
    async connect(): Promise<void> {
        if (!W4RPBridge.isSupported()) {
            throw new W4RPError(
                W4RPErrorCode.BLUETOOTH_UNSUPPORTED,
                'Web Bluetooth is not supported in this browser. Use Chrome, Edge, or Opera.'
            );
        }

        if (this._connectionState === W4RPConnectionState.READY) {
            throw new W4RPError(W4RPErrorCode.ALREADY_CONNECTED, 'Already connected to a device');
        }

        this._connectionState = W4RPConnectionState.CONNECTING;
        this._lastError = null;

        try {
            // Request device - shows browser's device picker
            this.device = await navigator.bluetooth.requestDevice({
                filters: [{ services: [W4RP_UUIDS.SERVICE] }],
            });

            if (!this.device) {
                throw new W4RPError(W4RPErrorCode.DEVICE_NOT_FOUND, 'No device was selected');
            }

            // Listen for unexpected disconnection
            this.device.addEventListener('gattserverdisconnected', () => {
                this.handleDisconnect();
            });

            // Connect to GATT server
            this.server = await this.device.gatt?.connect();
            if (!this.server) {
                throw new W4RPError(W4RPErrorCode.CONNECTION_FAILED, 'Failed to connect to GATT server');
            }

            this._connectionState = W4RPConnectionState.DISCOVERING_SERVICES;

            // Discover service
            const service = await this.server.getPrimaryService(W4RP_UUIDS.SERVICE);

            // Discover characteristics
            this.rxCharacteristic = await service.getCharacteristic(W4RP_UUIDS.RX);
            this.txCharacteristic = await service.getCharacteristic(W4RP_UUIDS.TX);
            this.statusCharacteristic = await service.getCharacteristic(W4RP_UUIDS.STATUS);

            // Subscribe to TX notifications (data from module)
            await this.txCharacteristic.startNotifications();
            this.txCharacteristic.addEventListener('characteristicvaluechanged', (event) => {
                const value = (event.target as BluetoothRemoteGATTCharacteristic).value;
                if (value) {
                    this.handleTX(new Uint8Array(value.buffer));
                }
            });

            // Subscribe to Status notifications
            await this.statusCharacteristic.startNotifications();
            this.statusCharacteristic.addEventListener('characteristicvaluechanged', (event) => {
                const value = (event.target as BluetoothRemoteGATTCharacteristic).value;
                if (value) {
                    const text = new TextDecoder().decode(value);
                    this.callbacks.onStatusUpdate?.(text);
                }
            });

            this._connectionState = W4RPConnectionState.READY;
            this._lastError = null;

        } catch (error) {
            this._connectionState = W4RPConnectionState.ERROR;

            if (error instanceof W4RPError) {
                this._lastError = error;
                throw error;
            }

            // Handle browser-specific errors
            const message = error instanceof Error ? error.message : String(error);
            if (message.includes('User cancelled')) {
                const err = new W4RPError(W4RPErrorCode.DEVICE_NOT_FOUND, 'Device selection was cancelled');
                this._lastError = err;
                throw err;
            }
            const err = new W4RPError(W4RPErrorCode.CONNECTION_FAILED, `Connection failed: ${message}`);
            this._lastError = err;
            throw err;
        }
    }

    /**
     * Connect to a device with automatic retry using exponential backoff.
     *
     * This method wraps `connect()` and automatically retries on failure using
     * exponential backoff delays (e.g., 1s → 2s → 4s → 8s).
     *
     * IMPORTANT: Due to Web Bluetooth security requirements, the initial connection
     * MUST be triggered by a user gesture. Retry attempts may fail if the browser
     * requires a new user gesture for each connection attempt.
     *
     * @param retryConfig - Retry configuration (default: 3 retries, 1s base delay)
     * @param onRetry - Optional callback invoked before each retry attempt
     * @throws {W4RPError} If all attempts fail
     *
     * @example
     * ```typescript
     * await bridge.connectWithRetry(W4RP_DEFAULT_RETRY_CONFIG, (attempt, delay, error) => {
     *     console.log(`Retry ${attempt} after ${delay}ms: ${error.message}`);
     * });
     * ```
     */
    async connectWithRetry(
        retryConfig: W4RPRetryConfig = W4RP_DEFAULT_RETRY_CONFIG,
        onRetry?: W4RPRetryCallback
    ): Promise<void> {
        let lastError: W4RPError | null = null;

        for (let attempt = 0; attempt <= retryConfig.maxRetries; attempt++) {
            try {
                await this.connect();
                return; // Success
            } catch (error) {
                if (error instanceof W4RPError) {
                    lastError = error;

                    // Don't retry for certain errors
                    if (error.code === W4RPErrorCode.ALREADY_CONNECTED) {
                        throw error;
                    }

                    // User cancelled - don't retry
                    if (error.code === W4RPErrorCode.DEVICE_NOT_FOUND) {
                        throw error;
                    }

                    // If we've exhausted retries, throw
                    if (attempt >= retryConfig.maxRetries) {
                        throw error;
                    }

                    // Calculate delay and wait
                    const delayMs = calculateRetryDelay(retryConfig, attempt);
                    onRetry?.(attempt + 1, delayMs, error);

                    // Ensure we're in a clean state before retrying
                    this._connectionState = W4RPConnectionState.DISCONNECTED;

                    await this.delay(delayMs);
                } else {
                    // Unknown error, wrap and throw
                    throw new W4RPError(
                        W4RPErrorCode.CONNECTION_FAILED,
                        `Connection failed: ${error instanceof Error ? error.message : String(error)}`
                    );
                }
            }
        }

        // Should not reach here, but safety fallback
        if (lastError) {
            throw lastError;
        }
    }

    /**
     * Disconnect from the currently connected device.
     */
    disconnect(): void {
        this.wasIntentionalDisconnect = true;
        this._connectionState = W4RPConnectionState.DISCONNECTING;
        this.server?.disconnect();
        this.handleDisconnect();
    }

    /**
     * Fetch the module profile.
     * Returns device identification and registered capabilities.
     * 
     * @returns Module profile object
     * @throws {W4RPError} If not connected or request times out
     */
    async getProfile(): Promise<W4RPModuleProfile> {
        this.ensureConnected();

        const data = await this.streamRequest('GET:PROFILE');
        const json = new TextDecoder().decode(data);

        try {
            return JSON.parse(json) as W4RPModuleProfile;
        } catch {
            throw new W4RPError(W4RPErrorCode.INVALID_DATA, 'Failed to parse profile JSON');
        }
    }

    /**
     * Send rules to the module.
     * 
     * @param json - JSON string containing the ruleset
     * @param persistent - If true, rules are saved to NVS (survive reboot)
     * @param onProgress - Optional callback reporting upload progress
     * @throws {W4RPError} If not connected or write fails
     */
    async setRules(
        json: string,
        persistent: boolean,
        onProgress?: W4RPProgressCallback
    ): Promise<void> {
        this.ensureConnected();

        const data = new TextEncoder().encode(json);
        const crc = this.crc32(data);
        const mode = persistent ? 'NVS' : 'RAM';
        const header = `SET:RULES:${mode}:${data.length}:${crc}`;

        await this.write(new TextEncoder().encode(header));
        await this.sendChunked(data, 180, onProgress);
        await this.write(new TextEncoder().encode('END'));
    }

    /**
     * Start a Delta OTA firmware update.
     * 
     * @param patchData - Binary patch data (Janpatch format)
     * @param onProgress - Optional callback reporting upload progress
     * @throws {W4RPError} If not connected or OTA fails
     */
    async startOTA(
        patchData: Uint8Array,
        onProgress?: W4RPProgressCallback
    ): Promise<void> {
        this.ensureConnected();

        const crc = this.crc32(patchData);
        const cmd = `OTA:BEGIN:DELTA:${patchData.length}:${crc.toString(16).toUpperCase()}`;

        await this.write(new TextEncoder().encode(cmd));
        await this.delay(200); // Allow ESP32 to prepare
        await this.sendChunked(patchData, 180, onProgress);
        await this.write(new TextEncoder().encode('END'));
    }

    /**
     * Enable or disable debug mode on the module.
     * When enabled, the module streams signal/node state changes.
     * 
     * @param enabled - Whether to enable debug streaming
     */
    async setDebugMode(enabled: boolean): Promise<void> {
        this.ensureConnected();
        const cmd = enabled ? 'DEBUG:START' : 'DEBUG:STOP';
        await this.write(new TextEncoder().encode(cmd));
    }

    // ---------------------------------------------------------------------------
    // Private Methods
    // ---------------------------------------------------------------------------

    /** Throw if not connected */
    private ensureConnected(): void {
        if (!this.isConnected || !this.rxCharacteristic) {
            throw new W4RPError(W4RPErrorCode.NOT_CONNECTED, 'Not connected to a device');
        }
    }

    /** Write data to RX characteristic */
    private async write(data: Uint8Array): Promise<void> {
        if (!this.rxCharacteristic) {
            throw new W4RPError(W4RPErrorCode.NOT_CONNECTED, 'RX characteristic not available');
        }
        await this.rxCharacteristic.writeValueWithResponse(data);
    }

    /** Send data in 180-byte chunks with throttling */
    private async sendChunked(
        data: Uint8Array,
        chunkSize: number = 180,
        onProgress?: W4RPProgressCallback
    ): Promise<void> {
        const totalBytes = data.length;
        let offset = 0;
        while (offset < data.length) {
            const end = Math.min(offset + chunkSize, data.length);
            const chunk = data.slice(offset, end);
            await this.write(chunk);
            await this.delay(3); // Prevent BLE congestion
            offset = end;
            onProgress?.(offset, totalBytes);
        }
    }

    /** Send command and wait for streamed response */
    private streamRequest(command: string, timeoutMs: number = 10000): Promise<Uint8Array> {
        return new Promise((resolve, reject) => {
            this.streamActive = false;
            this.streamBuffer = new Uint8Array(0);
            this.streamExpectedLen = 0;
            this.streamExpectedCRC = 0;
            this.streamResolve = resolve;
            this.streamReject = reject;

            this.streamTimeoutId = setTimeout(() => {
                this.finishStream(new W4RPError(W4RPErrorCode.STREAM_TIMEOUT, 'Stream request timed out'));
            }, timeoutMs);

            this.write(new TextEncoder().encode(command)).catch((error) => {
                this.finishStream(
                    error instanceof W4RPError
                        ? error
                        : new W4RPError(W4RPErrorCode.WRITE_FAILED, `Write failed: ${error}`)
                );
            });
        });
    }

    /** Handle incoming TX data */
    private handleTX(data: Uint8Array): void {
        const text = new TextDecoder().decode(data);

        // Stream BEGIN marker
        if (text === 'BEGIN') {
            this.streamActive = true;
            this.streamBuffer = new Uint8Array(0);
            return;
        }

        // Stream END marker with validation data
        if (text.startsWith('END:')) {
            const parts = text.split(':');
            if (parts.length >= 3) {
                this.streamExpectedLen = parseInt(parts[1], 10);
                this.streamExpectedCRC = parseInt(parts[2], 10);
                this.finishStream();
            }
            return;
        }

        // Debug data (D:S:id:value or D:N:id:active)
        if (text.startsWith('D:')) {
            this.handleDebug(text);
            return;
        }

        // Accumulate stream data
        if (this.streamActive) {
            const newBuffer = new Uint8Array(this.streamBuffer.length + data.length);
            newBuffer.set(this.streamBuffer);
            newBuffer.set(data, this.streamBuffer.length);
            this.streamBuffer = newBuffer;
        }
    }

    /** Parse and emit debug data */
    private handleDebug(text: string): void {
        const parts = text.split(':');
        if (parts.length < 4) return;

        const type = parts[1];
        const id = parts[2];
        const value = parts[3];

        if (type === 'S') {
            this.callbacks.onDebugData?.({
                id,
                type: 'signal',
                value: parseFloat(value),
            });
        } else if (type === 'N') {
            this.callbacks.onDebugData?.({
                id,
                type: 'node',
                active: value === '1',
            });
        }
    }

    /** Complete stream and validate */
    private finishStream(error?: W4RPError): void {
        if (this.streamTimeoutId) {
            clearTimeout(this.streamTimeoutId);
            this.streamTimeoutId = null;
        }

        const resolve = this.streamResolve;
        const reject = this.streamReject;
        this.streamResolve = null;
        this.streamReject = null;
        this.streamActive = false;

        if (error) {
            reject?.(error);
            return;
        }

        // Validate length
        if (this.streamBuffer.length !== this.streamExpectedLen) {
            reject?.(
                new W4RPError(
                    W4RPErrorCode.LENGTH_MISMATCH,
                    `Length mismatch: received ${this.streamBuffer.length}, expected ${this.streamExpectedLen}`
                )
            );
            return;
        }

        // Validate CRC
        const crc = this.crc32(this.streamBuffer);
        if (crc !== this.streamExpectedCRC) {
            reject?.(
                new W4RPError(
                    W4RPErrorCode.CRC_MISMATCH,
                    `CRC mismatch: calculated ${crc}, expected ${this.streamExpectedCRC}`
                )
            );
            return;
        }

        resolve?.(this.streamBuffer);
    }

    /** Handle disconnection */
    private handleDisconnect(): void {
        const previousState = this._connectionState;
        const device = this.device;

        this._connectionState = W4RPConnectionState.DISCONNECTED;
        this.server = null;
        this.rxCharacteristic = null;
        this.txCharacteristic = null;
        this.statusCharacteristic = null;
        this.callbacks.onDisconnect?.();

        // Check for auto-reconnect
        // Note: Web Bluetooth can reconnect to a previously paired device without a new user gesture
        if (!this.wasIntentionalDisconnect &&
            this.autoReconnectConfig.enabled &&
            (previousState === W4RPConnectionState.READY || previousState === W4RPConnectionState.DISCOVERING_SERVICES) &&
            device?.gatt &&
            !this.isAutoReconnecting
        ) {
            // Check lifetime reconnect limit
            if (this.autoReconnectConfig.maxLifetimeReconnects > 0 &&
                this.lifetimeReconnectCount >= this.autoReconnectConfig.maxLifetimeReconnects) {
                const err = new W4RPError(W4RPErrorCode.CONNECTION_FAILED, 'Max lifetime reconnects exceeded');
                this.onReconnectFailed?.(err);
                this.wasIntentionalDisconnect = false;
                return;
            }

            // Start auto-reconnect
            this.isAutoReconnecting = true;
            this.attemptAutoReconnect(device);
        }

        this.wasIntentionalDisconnect = false;
    }

    /** Attempt to auto-reconnect to a device */
    private async attemptAutoReconnect(device: BluetoothDevice): Promise<void> {
        let lastReconnectError: W4RPError | null = null;

        for (let attempt = 0; attempt <= this.autoReconnectConfig.retryConfig.maxRetries; attempt++) {
            this.lifetimeReconnectCount++;
            this.onReconnecting?.(attempt + 1);

            try {
                // Attempt to reconnect using cached device
                if (!device.gatt) {
                    throw new W4RPError(W4RPErrorCode.CONNECTION_FAILED, 'Device GATT not available');
                }

                this._connectionState = W4RPConnectionState.CONNECTING;
                this.server = await device.gatt.connect();

                if (!this.server) {
                    throw new W4RPError(W4RPErrorCode.CONNECTION_FAILED, 'Failed to connect to GATT server');
                }

                this._connectionState = W4RPConnectionState.DISCOVERING_SERVICES;
                const service = await this.server.getPrimaryService(W4RP_UUIDS.SERVICE);
                this.rxCharacteristic = await service.getCharacteristic(W4RP_UUIDS.RX);
                this.txCharacteristic = await service.getCharacteristic(W4RP_UUIDS.TX);
                this.statusCharacteristic = await service.getCharacteristic(W4RP_UUIDS.STATUS);

                // Re-subscribe to notifications
                await this.rxCharacteristic.startNotifications();
                this.rxCharacteristic.addEventListener('characteristicvaluechanged', (event) => {
                    const value = (event.target as BluetoothRemoteGATTCharacteristic).value;
                    if (value) {
                        this.handleTX(new Uint8Array(value.buffer));
                    }
                });

                await this.statusCharacteristic.startNotifications();
                this.statusCharacteristic.addEventListener('characteristicvaluechanged', (event) => {
                    const value = (event.target as BluetoothRemoteGATTCharacteristic).value;
                    if (value) {
                        const text = new TextDecoder().decode(value);
                        this.callbacks.onStatusUpdate?.(text);
                    }
                });

                this._connectionState = W4RPConnectionState.READY;
                this._lastError = null;
                this.isAutoReconnecting = false;
                this.onReconnected?.();
                return;
            } catch (error) {
                lastReconnectError = error instanceof W4RPError
                    ? error
                    : new W4RPError(W4RPErrorCode.CONNECTION_FAILED, `Reconnect failed: ${error instanceof Error ? error.message : String(error)}`);

                if (attempt < this.autoReconnectConfig.retryConfig.maxRetries) {
                    const delayMs = calculateRetryDelay(this.autoReconnectConfig.retryConfig, attempt);
                    await this.delay(delayMs);
                }
            }
        }

        this._connectionState = W4RPConnectionState.DISCONNECTED;
        this.isAutoReconnecting = false;
        this.onReconnectFailed?.(lastReconnectError ?? new W4RPError(W4RPErrorCode.CONNECTION_FAILED, 'Auto-reconnect failed'));
    }

    /** Promise-based delay */
    private delay(ms: number): Promise<void> {
        return new Promise((resolve) => setTimeout(resolve, ms));
    }

    /**
     * Calculate CRC32 checksum (IEEE 802.3 polynomial).
     * Used for data integrity validation in the W4RP protocol.
     */
    private crc32(data: Uint8Array): number {
        let crc = 0xffffffff;
        for (const byte of data) {
            crc ^= byte;
            for (let i = 0; i < 8; i++) {
                crc = crc & 1 ? (crc >>> 1) ^ 0xedb88320 : crc >>> 1;
            }
        }
        return (crc ^ 0xffffffff) >>> 0;
    }
}
