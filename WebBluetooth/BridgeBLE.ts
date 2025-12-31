/**
 * BridgeBLE.ts
 *
 * Core Web Bluetooth Implementation for W4RP Bridge.
 *
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 2.0.0
 */

/// <reference types="web-bluetooth" />

import {
    W4RP_UUIDS,
    W4RPBluetoothStatus,
    W4RPErrorCode,
    W4RPError,
    W4RPModuleProfile,
    W4RPBridgeCallbacks,
    W4RPDevice,
    W4RPProgressCallback,
    W4RPConnectionState,
    W4RPRetryConfig,
    W4RPAutoReconnectConfig,
    W4RPRetryCallback,
    W4RPReconnectCallback,
    W4RP_DEFAULT_RETRY_CONFIG,
    W4RP_DISABLED_AUTO_RECONNECT,
    calculateRetryDelay
} from './BridgeTypes';

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
    // ---------------------------------------------------------------------------
    // Platform Identifier (standardized across iOS, Android, Web)
    // ---------------------------------------------------------------------------

    /** Platform identifier - 'web' for Web Bluetooth, 'ios' for Swift, 'android' for Kotlin */
    readonly platform: 'web' | 'ios' | 'android' = 'web';

    // BLE State
    private _device: BluetoothDevice | null = null;
    private server: BluetoothRemoteGATTServer | null = null;
    private rxCharacteristic: BluetoothRemoteGATTCharacteristic | null = null;
    private txCharacteristic: BluetoothRemoteGATTCharacteristic | null = null;
    private statusCharacteristic: BluetoothRemoteGATTCharacteristic | null = null;

    // Device Cache (for scan -> connect flow)
    private discoveredDevicesMap: Map<string, BluetoothDevice> = new Map();

    // Connection State
    private _connectionState: W4RPConnectionState = W4RPConnectionState.DISCONNECTED;

    /** Internal helper to update state and trigger callbacks */
    private updateConnectionState(newState: W4RPConnectionState): void {
        if (this._connectionState !== newState) {
            this._connectionState = newState;
            this.callbacks.onConnectionStateChanged?.(newState);
            this.onConnectionStateChanged?.(newState);
        }
    }

    /** Called when connection state changes (direct property assignment) */
    onConnectionStateChanged: ((state: W4RPConnectionState) => void) | null = null;
    /** Called when Bluetooth status changes (direct property assignment) */
    onBluetoothStatusChanged: ((status: W4RPBluetoothStatus) => void) | null = null;

    private _lastError: W4RPError | null = null;
    private _bluetoothStatus: W4RPBluetoothStatus = W4RPBluetoothStatus.UNKNOWN;

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

    /** UUID configuration (default: W4RP standard UUIDs) */
    uuids: typeof W4RP_UUIDS = { ...W4RP_UUIDS };

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
    /** Called when device(s) are discovered during scanning */
    onDeviceDiscovered: ((devices: W4RPDevice[]) => void) | null = null;
    /** Called when module sends a status update */
    onStatusUpdate: ((json: string) => void) | null = null;
    /** Called when debug data is received */
    onDebugData: ((data: import('./BridgeTypes').W4RPDebugData) => void) | null = null;
    /** Called when an error occurs */
    onError: ((error: W4RPError) => void) | null = null;

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

    /** Whether the bridge is currently scanning for devices */
    get isScanning(): boolean {
        return this._connectionState === W4RPConnectionState.SCANNING;
    }

    /** List of discovered devices (populated during scan) */
    get discoveredDevices(): W4RPDevice[] {
        return Array.from(this.discoveredDevicesMap.values()).map(d => ({
            id: d.id,
            name: d.name || 'Unknown',
            rssi: 0
        }));
    }

    /** Name of the connected device, or null if not connected */
    get connectedDeviceName(): string | null {
        return this.device?.name ?? null;
    }

    /** Current Bluetooth adapter status */
    get bluetoothStatus(): W4RPBluetoothStatus {
        return this._bluetoothStatus;
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
     * Scan for W4RP devices.
     * 
     * IMPORTANT: This method MUST be called from a user gesture (e.g., button click)
     * due to Web Bluetooth security requirements.
     * 
     * Note: Web Bluetooth shows a picker dialog rather than returning a list.
     * The selected device is cached and returned as a single-item array.
     * Use connect(deviceId) after scan to complete the connection.
     * 
     * @param _timeoutMs - Ignored for Web Bluetooth (picker is modal)
     * @returns Array of discovered devices (typically one device from picker)
     * @throws {W4RPError} If Bluetooth is unsupported or user cancels
     */
    async scan(_timeoutMs: number = 8000): Promise<W4RPDevice[]> {
        if (!W4RPBridge.isSupported()) {
            throw new W4RPError(
                W4RPErrorCode.BLUETOOTH_UNSUPPORTED,
                'Web Bluetooth is not supported in this browser. Use Chrome, Edge, or Opera.'
            );
        }

        this.updateConnectionState(W4RPConnectionState.SCANNING);
        this._lastError = null;

        try {
            // Request device - shows browser's device picker
            const device = await navigator.bluetooth.requestDevice({
                filters: [{ services: [W4RP_UUIDS.SERVICE] }],
            });

            if (!device) {
                this.updateConnectionState(W4RPConnectionState.DISCONNECTED);
                throw new W4RPError(W4RPErrorCode.DEVICE_NOT_FOUND, 'No device was selected');
            }

            // Cache the device for connect()
            this.discoveredDevicesMap.set(device.id, device);

            // Notify device discovered
            const devices: W4RPDevice[] = [{
                id: device.id,
                name: device.name || 'Unknown W4RP Device',
                rssi: 0
            }];
            this.callbacks.onDeviceDiscovered?.(devices);
            this.onDeviceDiscovered?.(devices);

            this.updateConnectionState(W4RPConnectionState.DISCONNECTED);

            // Return standardized device format matching Swift/Kotlin
            return devices;

        } catch (error) {
            this.updateConnectionState(W4RPConnectionState.DISCONNECTED);

            if (error instanceof W4RPError) {
                this._lastError = error;
                throw error;
            }

            // Handle browser-specific errors
            const message = error instanceof Error ? error.message : String(error);
            if (message.includes('User cancelled') || message.includes('user gesture')) {
                const err = new W4RPError(W4RPErrorCode.DEVICE_NOT_FOUND, 'Device selection was cancelled');
                this._lastError = err;
                throw err;
            }
            const err = new W4RPError(W4RPErrorCode.SCAN_TIMEOUT, `Scan failed: ${message}`);
            this._lastError = err;
            throw err;
        }
    }

    /**
     * Stop scanning for devices.
     * Note: No-op for Web Bluetooth since the picker is modal.
     */
    stopScan(): void {
        if (this._connectionState === W4RPConnectionState.SCANNING) {
            this.updateConnectionState(W4RPConnectionState.DISCONNECTED);
        }
    }

    /**
     * Connect to a device by ID.
     * 
     * The device must have been discovered via scan() first.
     * 
     * @param deviceId - Device ID from scan() results
     * @throws {W4RPError} If device not found or connection fails
     */
    async connect(deviceId: string): Promise<void> {
        // Look up the device from cache
        const device = this.discoveredDevicesMap.get(deviceId);
        if (!device) {
            throw new W4RPError(
                W4RPErrorCode.DEVICE_NOT_FOUND,
                `Device with ID '${deviceId}' not found. Call scan() first.`
            );
        }

        if (this._connectionState === W4RPConnectionState.READY) {
            throw new W4RPError(W4RPErrorCode.ALREADY_CONNECTED, 'Already connected to a device');
        }

        this.updateConnectionState(W4RPConnectionState.CONNECTING);
        this._lastError = null;
        this._device = device;
        this.wasIntentionalDisconnect = false;

        try {
            // Listen for unexpected disconnection
            this._device.addEventListener('gattserverdisconnected', () => {
                this.handleDisconnect();
            });

            // Connect to GATT server
            this.server = await this._device.gatt?.connect();
            if (!this.server) {
                throw new W4RPError(W4RPErrorCode.CONNECTION_FAILED, 'Failed to connect to GATT server');
            }

            this.updateConnectionState(W4RPConnectionState.DISCOVERING_SERVICES);

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
                    this.onStatusUpdate?.(text);
                }
            });

            this.updateConnectionState(W4RPConnectionState.READY);
            this._lastError = null;

        } catch (error) {
            this.updateConnectionState(W4RPConnectionState.ERROR);

            if (error instanceof W4RPError) {
                this._lastError = error;
                throw error;
            }

            const message = error instanceof Error ? error.message : String(error);
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
     * @param deviceId - Device ID from scan() results
     * @param retryConfig - Retry configuration (default: 3 retries, 1s base delay)
     * @param onRetry - Optional callback invoked before each retry attempt
     * @throws {W4RPError} If all attempts fail
     *
     * @example
     * ```typescript
     * const devices = await bridge.scan();
     * await bridge.connectWithRetry(devices[0].id, W4RP_DEFAULT_RETRY_CONFIG, (attempt, delay, error) => {
     *     console.log(`Retry ${attempt} after ${delay}ms: ${error.message}`);
     * });
     * ```
     */
    async connectWithRetry(
        deviceId: string,
        retryConfig: W4RPRetryConfig = W4RP_DEFAULT_RETRY_CONFIG,
        onRetry?: W4RPRetryCallback
    ): Promise<void> {
        let lastError: W4RPError | null = null;

        for (let attempt = 0; attempt <= retryConfig.maxRetries; attempt++) {
            try {
                await this.connect(deviceId);
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
                    this.updateConnectionState(W4RPConnectionState.DISCONNECTED);

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
        this.updateConnectionState(W4RPConnectionState.DISCONNECTING);
        this.server?.disconnect();
        this.handleDisconnect();
    }

    /**
     * Fetch raw WBP profile binary from the module.
     * 
     * Returns raw bytes - UI is responsible for parsing with WBPParser.
     * 
     * @returns Raw WBP binary data
     * @throws {W4RPError} If not connected or request times out
     */
    async getProfile(): Promise<Uint8Array> {
        this.ensureConnected();
        return this.streamRequest('GET:PROFILE');
    }

    /**
     * Fetch raw WBP rules binary from the module.
     * 
     * Returns raw bytes - UI is responsible for parsing with WBPParser.
     * 
     * @returns Raw WBP binary data (or throws if no rules loaded)
     * @throws {W4RPError} If not connected or request times out
     */
    async getRules(): Promise<Uint8Array> {
        this.ensureConnected();
        return this.streamRequest('GET:RULES');
    }

    /**
     * Send compiled WBP rules binary to the module.
     * 
     * @param binary - Compiled WBP binary ruleset (from WBPCompiler)
     * @param persistent - If true, rules are saved to NVS (survive reboot)
     * @param onProgress - Optional callback reporting upload progress
     * @throws {W4RPError} If not connected or write fails
     */
    async setRules(
        binary: Uint8Array,
        persistent: boolean,
        onProgress?: W4RPProgressCallback
    ): Promise<void> {
        this.ensureConnected();

        const crc = this.crc32(binary);
        const mode = persistent ? 'NVS' : 'RAM';
        const header = `SET:RULES:${mode}:${binary.length}:${crc}`;

        await this.write(new TextEncoder().encode(header));
        await this.sendChunked(binary, 180, onProgress);
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
        const cmd = `OTA:DELTA:${patchData.length}:${crc.toString(16).toUpperCase()}`;

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

    /**
     * Set specific signals to watch in debug mode.
     * Sends comma-separated signal specs to firmware.
     * 
     * @param signals - Array of signal objects with can_id, start, len/length, be/big_endian, factor, offset
     */
    async watchDebugSignals(signals: any[]): Promise<void> {
        this.ensureConnected();

        // Build comma-separated specs: canId:start:len:be:factor:offset
        const specs = signals.map(s => {
            const can_id = s.can_id;
            const start = s.start ?? s.start_bit ?? 0;
            const len = s.length ?? s.len ?? s.bit_length ?? 8;
            const be = (s.big_endian ?? s.be ?? false) ? 1 : 0;
            const factor = s.factor ?? 1;
            const offset = s.offset ?? 0;
            return `${can_id}:${start}:${len}:${be}:${factor}:${offset}`;
        });

        const payload = specs.join(',');
        const data = new TextEncoder().encode(payload);
        const crc = this.crc32(data);
        const header = `DEBUG:WATCH:${data.length}:${crc}`;

        await this.write(new TextEncoder().encode(header));
        await this.delay(50); // Allow firmware to prepare for stream
        await this.sendChunked(data);
        await this.write(new TextEncoder().encode('END'));
    }

    /**
     * Stop an ongoing OTA update.
     * The module remains on current firmware; no rollback occurs.
     */
    stopOTA(): void {
        // OTA cancellation - sets internal flag
        // The actual OTA loop should check this flag
        this._otaCancelled = true;
    }

    /** Internal flag for OTA cancellation */
    private _otaCancelled = false;

    /**
     * Information about the currently connected device.
     * Returns null if not connected.
     */
    get connectedDevice(): W4RPDevice | null {
        if (!this.isConnected || !this._device) {
            return null;
        }
        return {
            id: this._device.id,
            name: this._device.name || 'Unknown',
            rssi: 0 // Not available via Web Bluetooth after connection
        };
    }

    /**
     * Unified API: The device object - null if not connected, populated if connected.
     * This is the preferred property for cross-platform consistency (matches Swift/Kotlin).
     */
    get device(): W4RPDevice | null {
        return this.connectedDevice;
    }

    /**
     * Get detailed information about the current connection.
     * 
     * @returns Connection info object or null if not connected
     */
    getConnectionInfo(): { deviceId: string; deviceName: string; rssi: number; isConnected: boolean } | null {
        if (!this.isConnected || !this.device) {
            return null;
        }
        return {
            deviceId: this.device.id,
            deviceName: this.device.name || 'Unknown',
            rssi: 0, // Not available via Web Bluetooth
            isConnected: true
        };
    }

    /**
     * Notify that the application is ready (used for splash screen handling in native wrappers).
     * In web mode, this is a no-op or logging operation.
     */
    notifyAppReady(): void {
        console.log("App Ready (Web Mode)");
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

    /** Centralized error handler - mirrors Swift/Kotlin pattern */
    private handleError(error: W4RPError): void {
        this._lastError = error;
        this.updateConnectionState(W4RPConnectionState.ERROR);
        this.callbacks.onError?.(error);
        this.onError?.(error);
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

        // Error responses from firmware (ERR:LEN_MISMATCH, ERR:CRC_FAIL, ERR:CAP_UNKNOWN:*, etc.)
        if (text.startsWith('ERR:')) {
            const errorType = text.substring(4);
            const error = new W4RPError(W4RPErrorCode.INVALID_RESPONSE, `Firmware error: ${errorType}`);
            this.handleError(error);

            // If we have an active stream, fail it
            if (this.streamActive || this.streamResolve) {
                this.finishStream(error);
            }
            return;
        }

        // OTA responses
        if (text.startsWith('OTA:')) {
            // OTA:READY, OTA:SUCCESS, OTA:ERROR, OTA:CANCELLED handled here if needed
            // For now, these are informational - could add callback
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

        if (type === 'S') {
            // Signal format: D:S:can_id:start:len:be:factor:offset:value
            // Signal key = parts[2] through parts[length-2]
            // Value = parts[length-1]
            const signalKey = parts.slice(2, -1).join(':');
            const value = parseFloat(parts[parts.length - 1]);
            const debugData = {
                id: signalKey,
                type: 'signal' as const,
                value,
            };
            this.callbacks.onDebugData?.(debugData);
            this.onDebugData?.(debugData);
        } else if (type === 'N') {
            const id = parts[2];
            const value = parts[3];
            const debugData = {
                id,
                type: 'node' as const,
                active: value === '1',
            };
            this.callbacks.onDebugData?.(debugData);
            this.onDebugData?.(debugData);
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
        const device = this._device;

        this.updateConnectionState(W4RPConnectionState.DISCONNECTED);
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

                this.updateConnectionState(W4RPConnectionState.CONNECTING);
                this.server = await device.gatt.connect();

                if (!this.server) {
                    throw new W4RPError(W4RPErrorCode.CONNECTION_FAILED, 'Failed to connect to GATT server');
                }

                this.updateConnectionState(W4RPConnectionState.DISCOVERING_SERVICES);
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

                this.updateConnectionState(W4RPConnectionState.READY);
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

        this.updateConnectionState(W4RPConnectionState.DISCONNECTED);
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
    private async checkBluetoothStatus(): Promise<void> {
        let status = W4RPBluetoothStatus.UNKNOWN;

        try {
            if (typeof navigator === 'undefined' || !navigator.bluetooth) {
                status = W4RPBluetoothStatus.UNSUPPORTED;
            } else if (typeof window !== 'undefined' && !window.isSecureContext) {
                status = W4RPBluetoothStatus.UNAUTHORIZED;
            } else {
                const available = await navigator.bluetooth.getAvailability();
                status = available ? W4RPBluetoothStatus.READY : W4RPBluetoothStatus.POWERED_OFF;
            }
        } catch (error: any) {
            if (error.name === 'NotSupportedError') {
                status = W4RPBluetoothStatus.UNSUPPORTED;
            } else if (error.name === 'SecurityError') {
                status = W4RPBluetoothStatus.UNAUTHORIZED;
            } else {
                status = W4RPBluetoothStatus.UNKNOWN;
            }
        }

        this.updateBluetoothStatus(status);
    }

    private updateBluetoothStatus(newStatus: W4RPBluetoothStatus): void {
        if (this._bluetoothStatus !== newStatus) {
            this._bluetoothStatus = newStatus;
            this.callbacks.onBluetoothStatusChanged?.(newStatus);
            this.onBluetoothStatusChanged?.(newStatus);
        }
    }

    /**
     * Start monitoring Bluetooth availability.
     * Called automatically by constructor if platform is web.
     */
    private initStatusMonitoring(): void {
        if (typeof navigator !== 'undefined' && navigator.bluetooth) {
            this.checkBluetoothStatus();
            navigator.bluetooth.addEventListener('availabilitychanged', (event: Event) => {
                // event.value is boolean in Web Bluetooth spec
                const available = (event as any).value;
                const status = available ? W4RPBluetoothStatus.READY : W4RPBluetoothStatus.POWERED_OFF;
                this.updateBluetoothStatus(status);
            });
        }
    }

    constructor() {
        if (typeof window !== 'undefined') {
            // Auto install on window for easy access
            (window as any).W4RPBridge = this;
            this.initStatusMonitoring();
        }
    }
}
