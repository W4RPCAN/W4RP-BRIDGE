/**
 * W4RPBridge.ts
 * 
 * W4RP BLE Bridge - Web Bluetooth API Implementation
 * Official client library for connecting to W4RPBLE firmware modules.
 * 
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 1.0.0
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
 * @see https://github.com/user/w4rpble for firmware source
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

    // Stream State (for receiving large payloads)
    private streamActive = false;
    private streamBuffer: Uint8Array = new Uint8Array(0);
    private streamExpectedLen = 0;
    private streamExpectedCRC = 0;
    private streamResolve: ((data: Uint8Array) => void) | null = null;
    private streamReject: ((error: W4RPError) => void) | null = null;
    private streamTimeoutId: ReturnType<typeof setTimeout> | null = null;

    // Event Callbacks
    private callbacks: W4RPBridgeCallbacks = {};

    // ---------------------------------------------------------------------------
    // Public Getters
    // ---------------------------------------------------------------------------

    /** Whether the bridge is currently connected to a device */
    get isConnected(): boolean {
        return this.server?.connected ?? false;
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

        if (this.isConnected) {
            throw new W4RPError(W4RPErrorCode.ALREADY_CONNECTED, 'Already connected to a device');
        }

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

        } catch (error) {
            if (error instanceof W4RPError) throw error;

            // Handle browser-specific errors
            const message = error instanceof Error ? error.message : String(error);
            if (message.includes('User cancelled')) {
                throw new W4RPError(W4RPErrorCode.DEVICE_NOT_FOUND, 'Device selection was cancelled');
            }
            throw new W4RPError(W4RPErrorCode.CONNECTION_FAILED, `Connection failed: ${message}`);
        }
    }

    /**
     * Disconnect from the currently connected device.
     */
    disconnect(): void {
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
     * @throws {W4RPError} If not connected or write fails
     */
    async setRules(json: string, persistent: boolean): Promise<void> {
        this.ensureConnected();

        const data = new TextEncoder().encode(json);
        const crc = this.crc32(data);
        const mode = persistent ? 'NVS' : 'RAM';
        const header = `SET:RULES:${mode}:${data.length}:${crc}`;

        await this.write(new TextEncoder().encode(header));
        await this.sendChunked(data);
        await this.write(new TextEncoder().encode('END'));
    }

    /**
     * Start a Delta OTA firmware update.
     * 
     * @param patchData - Binary patch data (Janpatch format)
     * @throws {W4RPError} If not connected or OTA fails
     */
    async startOTA(patchData: Uint8Array): Promise<void> {
        this.ensureConnected();

        const crc = this.crc32(patchData);
        const cmd = `OTA:BEGIN:DELTA:${patchData.length}:${crc.toString(16).toUpperCase()}`;

        await this.write(new TextEncoder().encode(cmd));
        await this.delay(200); // Allow ESP32 to prepare
        await this.sendChunked(patchData);
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
    private async sendChunked(data: Uint8Array, chunkSize: number = 180): Promise<void> {
        let offset = 0;
        while (offset < data.length) {
            const end = Math.min(offset + chunkSize, data.length);
            const chunk = data.slice(offset, end);
            await this.write(chunk);
            await this.delay(3); // Prevent BLE congestion
            offset = end;
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
        this.device = null;
        this.server = null;
        this.rxCharacteristic = null;
        this.txCharacteristic = null;
        this.statusCharacteristic = null;
        this.callbacks.onDisconnect?.();
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
