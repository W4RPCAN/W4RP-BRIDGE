/**
 * W4RPBridge.swift
 *
 * W4RP Bridge - Swift/iOS/macOS Implementation
 * Official client library for connecting to W4RPBLE firmware modules.
 *
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 1.1.0
 *
 * Compatible with:
 * - iOS 15.0+ (async/await requires iOS 15)
 * - macOS 12.0+ (async/await requires macOS 12)
 * - watchOS 8.0+ (limited)
 * - tvOS 15.0+ (limited)
 *
 * Required Info.plist keys:
 * - NSBluetoothAlwaysUsageDescription
 * - NSBluetoothPeripheralUsageDescription (iOS 12 and earlier)
 *
 * @see https://github.com/W4RPCAN/W4RPBLE for firmware source
 */

import Foundation
import CoreBluetooth
import Combine

// MARK: - UUID Configuration

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
public struct W4RPUUIDs: Sendable {
    public let service: CBUUID
    public let rx: CBUUID
    public let tx: CBUUID
    public let status: CBUUID
    
    public static let `default` = W4RPUUIDs(
        service: CBUUID(string: "0000fff0-5734-5250-5734-525000000000"),
        rx:      CBUUID(string: "0000fff1-5734-5250-5734-525000000000"),
        tx:      CBUUID(string: "0000fff2-5734-5250-5734-525000000000"),
        status:  CBUUID(string: "0000fff3-5734-5250-5734-525000000000")
    )
}

// MARK: - Error Handling

/**
 * Standardized error codes for W4RP operations.
 *
 * Error code ranges:
 * - 1xxx: BLE Infrastructure errors (connection, scanning, permissions)
 * - 2xxx: Protocol errors (write failures, invalid responses)
 * - 3xxx: Data validation errors (CRC mismatch, length mismatch)
 * - 4xxx: Timeout errors (profile, stream, connection)
 */
public enum W4RPErrorCode: Int, Sendable {
    case connectionFailed       = 1000
    case connectionLost         = 1001
    case notConnected           = 1002
    case alreadyConnected       = 1003
    case deviceNotFound         = 1004
    case serviceNotFound        = 1005
    case characteristicNotFound = 1006
    case bluetoothOff           = 1007
    case bluetoothUnauthorized  = 1008
    
    case invalidResponse        = 2000
    case writeFailed            = 2003
    
    case invalidData            = 3000
    case crcMismatch            = 3001
    case lengthMismatch         = 3002
    
    case profileTimeout         = 4000
    case streamTimeout          = 4001
    case scanTimeout            = 4002
    case connectionTimeout      = 4003
}

public struct W4RPError: Error, Sendable {
    public let code: W4RPErrorCode
    public let message: String
    public let context: [String: String]?
    
    public init(_ code: W4RPErrorCode, _ message: String, context: [String: String]? = nil) {
        self.code = code
        self.message = message
        self.context = context
    }
}

// MARK: - Data Models

public struct W4RPDevice: Identifiable, Sendable {
    public let id: UUID
    public let peripheral: CBPeripheral
    public let name: String
    public var rssi: Int
    public let advertisementData: [String: Any]
    
    public init(peripheral: CBPeripheral, name: String, rssi: Int, advertisementData: [String: Any]) {
        self.id = peripheral.identifier
        self.peripheral = peripheral
        self.name = name
        self.rssi = rssi
        self.advertisementData = advertisementData
    }
}

public struct W4RPModuleProfile: Decodable, Sendable {
    public let module: ModuleInfo
    public let capabilities: [String: Capability]?
    public let runtime: RuntimeInfo?
    
    public struct ModuleInfo: Decodable, Sendable {
        public let id: String
        public let hw: String?
        public let fw: String?
        public let device_name: String?
    }
    
    public struct Capability: Decodable, Sendable {
        public let label: String?
        public let category: String?
        public let params: [Param]?
        
        public struct Param: Decodable, Sendable {
            public let name: String
            public let type: String
            public let min: Int?
            public let max: Int?
        }
    }
    
    public struct RuntimeInfo: Decodable, Sendable {
        public let mode: String?
    }
}

public struct W4RPDebugData: Sendable {
    public let id: String
    public let type: DebugType
    public let value: Float?
    public let active: Bool?
    
    public enum DebugType: Sendable {
        case signal
        case node
    }
}

// MARK: - Connection State Machine

/**
 * Represents the current state of the BLE connection.
 *
 * State transitions:
 * ```
 * DISCONNECTED -> SCANNING (via scan())
 * SCANNING -> DISCONNECTED (scan complete/timeout)
 * DISCONNECTED -> CONNECTING (via connect())
 * CONNECTING -> DISCOVERING_SERVICES (GATT connected)
 * DISCOVERING_SERVICES -> READY (characteristics found)
 * READY -> DISCONNECTING (via disconnect())
 * DISCONNECTING -> DISCONNECTED (complete)
 * ANY -> ERROR (on failure)
 * ERROR -> DISCONNECTED (reset)
 * ```
 */
public enum W4RPConnectionState: String, Sendable {
    /// Not connected to any device
    case disconnected = "DISCONNECTED"
    
    /// Scanning for nearby W4RP devices
    case scanning = "SCANNING"
    
    /// Initiating GATT connection to a device
    case connecting = "CONNECTING"
    
    /// Connected, discovering services and characteristics
    case discoveringServices = "DISCOVERING_SERVICES"
    
    /// Fully connected and ready for operations
    case ready = "READY"
    
    /// Disconnection in progress
    case disconnecting = "DISCONNECTING"
    
    /// An error occurred (check lastError for details)
    case error = "ERROR"
    
    /// Convenience property for checking if ready for operations
    public var isReady: Bool { self == .ready }
    
    /// Convenience property for checking if any connection activity is in progress
    public var isBusy: Bool {
        switch self {
        case .scanning, .connecting, .discoveringServices, .disconnecting:
            return true
        default:
            return false
        }
    }
}

// MARK: - Retry Configuration

/**
 * Configuration for exponential backoff retry logic.
 *
 * Example usage:
 * ```swift
 * let config = W4RPRetryConfig(maxRetries: 3, baseDelay: 1.0, maxDelay: 8.0)
 * try await bridge.connectWithRetry(to: device, retryConfig: config)
 * ```
 */
public struct W4RPRetryConfig: Sendable {
    /// Maximum number of retry attempts (0 = no retries, just the initial attempt)
    public let maxRetries: Int
    
    /// Base delay in seconds before first retry (default: 1.0)
    public let baseDelay: TimeInterval
    
    /// Maximum delay cap in seconds (default: 16.0)
    public let maxDelay: TimeInterval
    
    /// Multiplier for exponential growth (default: 2.0)
    public let multiplier: Double
    
    /// Default configuration: 3 retries, 1s base, 16s max, 2x multiplier
    public static let `default` = W4RPRetryConfig()
    
    public init(
        maxRetries: Int = 3,
        baseDelay: TimeInterval = 1.0,
        maxDelay: TimeInterval = 16.0,
        multiplier: Double = 2.0
    ) {
        self.maxRetries = maxRetries
        self.baseDelay = baseDelay
        self.maxDelay = maxDelay
        self.multiplier = multiplier
    }
    
    /// Calculate delay for a given attempt (0-indexed)
    public func delay(forAttempt attempt: Int) -> TimeInterval {
        let delay = baseDelay * pow(multiplier, Double(attempt))
        return min(delay, maxDelay)
    }
}

// MARK: - Auto-Reconnect Configuration

/**
 * Configuration for automatic reconnection when connection is lost unexpectedly.
 *
 * When enabled, the bridge will attempt to reconnect automatically if the connection
 * is lost (e.g., device goes out of range, power loss). The reconnection uses
 * exponential backoff as configured in `retryConfig`.
 *
 * Example usage:
 * ```swift
 * bridge.autoReconnectConfig = W4RPAutoReconnectConfig(
 *     enabled: true,
 *     retryConfig: W4RPRetryConfig(maxRetries: 5)
 * )
 * bridge.onReconnecting = { attempt in print("Reconnecting attempt \(attempt)...") }
 * bridge.onReconnected = { print("Reconnected!") }
 * bridge.onReconnectFailed = { error in print("Failed: \(error)") }
 * ```
 */
public struct W4RPAutoReconnectConfig: Sendable {
    /// Whether auto-reconnect is enabled (default: false)
    public let enabled: Bool
    
    /// Retry configuration for reconnection attempts
    public let retryConfig: W4RPRetryConfig
    
    /// Maximum total reconnection attempts across all disconnections (0 = unlimited)
    public let maxLifetimeReconnects: Int
    
    /// Disabled configuration (default)
    public static let disabled = W4RPAutoReconnectConfig(enabled: false)
    
    /// Default enabled configuration: up to 5 reconnect attempts
    public static let `default` = W4RPAutoReconnectConfig(
        enabled: true,
        retryConfig: W4RPRetryConfig(maxRetries: 5, baseDelay: 2.0)
    )
    
    public init(
        enabled: Bool = false,
        retryConfig: W4RPRetryConfig = .default,
        maxLifetimeReconnects: Int = 0
    ) {
        self.enabled = enabled
        self.retryConfig = retryConfig
        self.maxLifetimeReconnects = maxLifetimeReconnects
    }
}

/**
 * W4RPBridge - CoreBluetooth client for W4RPBLE modules.
 *
 * This class provides both async/await and callback-based APIs.
 *
 * ### Async/Await Usage (Recommended - iOS 15+)
 * ```swift
 * let bridge = W4RPBridge()
 *
 * do {
 *     let devices = try await bridge.scan()
 *     try await bridge.connect(to: devices[0])
 *     let profile = try await bridge.getProfile()
 *     print("Connected to: \(profile.module.id)")
 * } catch {
 *     print("Error: \(error)")
 * }
 * ```
 *
 * ### Callback Usage (Legacy)
 * ```swift
 * bridge.startScan { result in
 *     switch result {
 *     case .success(let devices): ...
 *     case .failure(let error): ...
 *     }
 * }
 * ```
 */
@MainActor
public final class W4RPBridge: NSObject, ObservableObject {
    
    // MARK: - Published State
    
    /// Current connection state (replaces deprecated isConnected)
    @Published public private(set) var connectionState: W4RPConnectionState = .disconnected
    
    /// List of discovered devices during scanning
    @Published public private(set) var discoveredDevices: [W4RPDevice] = []
    
    /// Name of the connected device, or nil if not connected
    @Published public private(set) var connectedDeviceName: String?
    
    /// Last error that occurred, cleared on successful operations
    @Published public private(set) var lastError: W4RPError?
    
    /// Convenience computed property for backward compatibility
    public var isConnected: Bool { connectionState == .ready }
    
    /// Convenience computed property for backward compatibility
    public var isScanning: Bool { connectionState == .scanning }
    
    // MARK: - Configuration
    
    public var uuids: W4RPUUIDs = .default
    
    /// Auto-reconnect configuration (default: disabled)
    public var autoReconnectConfig: W4RPAutoReconnectConfig = .disabled
    
    // MARK: - Callbacks
    
    /// Called when module sends a status update
    public var onStatusUpdate: ((String) -> Void)?
    /// Called when debug data is received
    public var onDebugData: ((W4RPDebugData) -> Void)?
    /// Called when auto-reconnect starts (provides attempt number)
    public var onReconnecting: ((Int) -> Void)?
    /// Called when auto-reconnect succeeds
    public var onReconnected: (() -> Void)?
    /// Called when auto-reconnect fails after all attempts
    public var onReconnectFailed: ((W4RPError) -> Void)?
    
    // MARK: - Private State
    
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var rxChar: CBCharacteristic?
    private var txChar: CBCharacteristic?
    private var statusChar: CBCharacteristic?
    
    private var deviceMap: [UUID: W4RPDevice] = [:]
    private var scanTimer: Timer?
    private var connectionTimer: Timer?
    
    // Auto-reconnect State
    private var lastConnectedDevice: W4RPDevice?
    private var isAutoReconnecting = false
    private var lifetimeReconnectCount = 0
    private var wasIntentionalDisconnect = false
    
    // Stream State
    private var streamActive = false
    private var streamBuffer = Data()
    private var streamExpectedLen = 0
    private var streamExpectedCRC: UInt32 = 0
    private var streamContinuation: CheckedContinuation<Data, Error>?
    private var streamTimeout: Timer?
    
    // Connection Continuations
    private var scanContinuation: CheckedContinuation<[W4RPDevice], Error>?
    private var connectContinuation: CheckedContinuation<Void, Error>?
    private var disconnectContinuation: CheckedContinuation<Void, Error>?
    
    // MARK: - Initialization
    
    public override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }
    
    // MARK: - Async/Await Public API
    
    /**
     * Scan for W4RP devices.
     *
     * - Parameter timeout: Scan duration in seconds (default: 8)
     * - Returns: Array of discovered devices sorted by signal strength
     * - Throws: `W4RPError` if Bluetooth is unavailable or no devices found
     */
    public func scan(timeout: TimeInterval = 8.0) async throws -> [W4RPDevice] {
        try checkBluetoothState()
        
        return try await withCheckedThrowingContinuation { continuation in
            scanContinuation = continuation
            
            connectionState = .scanning
            lastError = nil
            deviceMap.removeAll()
            discoveredDevices = []
            
            central.scanForPeripherals(
                withServices: [uuids.service],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
            )
            
            scanTimer = Timer.scheduledTimer(withTimeInterval: timeout, repeats: false) { [weak self] _ in
                self?.finishScan()
            }
        }
    }
    
    /**
     * Connect to a discovered device.
     *
     * - Parameter device: Device to connect to
     * - Parameter timeout: Connection timeout in seconds (default: 10)
     * - Throws: `W4RPError` if connection fails or times out
     */
    public func connect(to device: W4RPDevice, timeout: TimeInterval = 10.0) async throws {
        guard connectionState != .ready else {
            throw W4RPError(.alreadyConnected, "Already connected to a device")
        }
        
        stopScan()
        connectionState = .connecting
        lastError = nil
        wasIntentionalDisconnect = false
        lastConnectedDevice = device
        
        return try await withCheckedThrowingContinuation { continuation in
            connectContinuation = continuation
            peripheral = device.peripheral
            connectedDeviceName = device.name
            device.peripheral.delegate = self
            
            connectionTimer = Timer.scheduledTimer(withTimeInterval: timeout, repeats: false) { [weak self] _ in
                guard let self = self, self.connectionState != .ready else { return }
                self.central.cancelPeripheralConnection(device.peripheral)
                self.connectionState = .error
                self.lastError = W4RPError(.connectionTimeout, "Connection timed out")
                self.connectContinuation?.resume(throwing: W4RPError(.connectionTimeout, "Connection timed out"))
                self.connectContinuation = nil
            }
            
            central.connect(device.peripheral, options: nil)
        }
    }
    
    /**
     * Connect to a device with automatic retry using exponential backoff.
     *
     * This method wraps `connect()` and automatically retries on failure using
     * exponential backoff delays (e.g., 1s → 2s → 4s → 8s).
     *
     * - Parameter device: Device to connect to
     * - Parameter timeout: Connection timeout in seconds per attempt (default: 10)
     * - Parameter retryConfig: Retry configuration (default: 3 retries, 1s base delay)
     * - Parameter onRetry: Optional callback invoked before each retry attempt with (attempt, delay, error)
     * - Throws: `W4RPError` if all attempts fail
     *
     * ```swift
     * try await bridge.connectWithRetry(to: device) { attempt, delay, error in
     *     print("Retry \(attempt) after \(delay)s: \(error)")
     * }
     * ```
     */
    public func connectWithRetry(
        to device: W4RPDevice,
        timeout: TimeInterval = 10.0,
        retryConfig: W4RPRetryConfig = .default,
        onRetry: ((Int, TimeInterval, W4RPError) -> Void)? = nil
    ) async throws {
        var lastError: W4RPError?
        
        for attempt in 0...retryConfig.maxRetries {
            do {
                try await connect(to: device, timeout: timeout)
                return // Success
            } catch let error as W4RPError {
                lastError = error
                
                // Don't retry for certain errors
                if error.code == .alreadyConnected {
                    throw error
                }
                
                // If we've exhausted retries, throw
                if attempt >= retryConfig.maxRetries {
                    throw error
                }
                
                // Calculate delay and wait
                let delay = retryConfig.delay(forAttempt: attempt)
                onRetry?(attempt + 1, delay, error)
                
                // Ensure we're in a clean state before retrying
                connectionState = .disconnected
                
                try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            }
        }
        
        // Should not reach here, but safety fallback
        if let error = lastError {
            throw error
        }
    }
    
    /**
     * Disconnect from the current device.
     *
     * - Throws: `W4RPError` if disconnection fails
     */
    public func disconnect() async throws {
        guard let p = peripheral else { return }
        
        wasIntentionalDisconnect = true
        connectionState = .disconnecting
        
        return try await withCheckedThrowingContinuation { continuation in
            disconnectContinuation = continuation
            central.cancelPeripheralConnection(p)
        }
    }
    
    /**
     * Fetch the module profile.
     *
     * - Returns: Module profile containing device info and capabilities
     * - Throws: `W4RPError` if not connected or request fails
     */
    public func getProfile() async throws -> W4RPModuleProfile {
        try ensureConnected()
        
        let data = try await streamRequest(command: "GET:PROFILE")
        
        do {
            return try JSONDecoder().decode(W4RPModuleProfile.self, from: data)
        } catch {
            throw W4RPError(.invalidData, "Failed to decode profile: \(error)")
        }
    }
    
    /**
     * Send rules to the module.
     *
     * - Parameter json: JSON string containing the ruleset
     * - Parameter persistent: If true, rules are saved to NVS (survive reboot)
     * - Parameter onProgress: Optional callback reporting upload progress (bytesWritten, totalBytes)
     * - Throws: `W4RPError` if not connected or write fails
     */
    public func setRules(
        json: String,
        persistent: Bool,
        onProgress: ((Int, Int) -> Void)? = nil
    ) async throws {
        try ensureConnected()
        guard let p = peripheral, let rx = rxChar else {
            throw W4RPError(.notConnected, "Not connected")
        }
        
        guard let data = json.data(using: .utf8) else {
            throw W4RPError(.invalidData, "Invalid JSON encoding")
        }
        
        let crc = crc32(data)
        let mode = persistent ? "NVS" : "RAM"
        let header = "SET:RULES:\(mode):\(data.count):\(crc)"
        
        try await writeData(header.data(using: .utf8)!, to: p, char: rx)
        try await sendChunked(data: data, to: p, char: rx, onProgress: onProgress)
        try await writeData("END".data(using: .utf8)!, to: p, char: rx)
    }
    
    /**
     * Start a Delta OTA firmware update.
     *
     * - Parameter patchData: Binary patch data (Janpatch format)
     * - Parameter onProgress: Optional callback reporting upload progress (bytesWritten, totalBytes)
     * - Throws: `W4RPError` if not connected or OTA fails
     */
    public func startOTA(
        patchData: Data,
        onProgress: ((Int, Int) -> Void)? = nil
    ) async throws {
        try ensureConnected()
        guard let p = peripheral, let rx = rxChar else {
            throw W4RPError(.notConnected, "Not connected")
        }
        
        let crc = crc32(patchData)
        let cmd = "OTA:BEGIN:DELTA:\(patchData.count):\(String(format: "%X", crc))"
        
        try await writeData(cmd.data(using: .utf8)!, to: p, char: rx)
        try await Task.sleep(nanoseconds: 200_000_000)
        try await sendChunked(data: patchData, to: p, char: rx, onProgress: onProgress)
        try await writeData("END".data(using: .utf8)!, to: p, char: rx)
    }
    
    // MARK: - Legacy Callback API (Backward Compatibility)
    
    public func startScan(timeout: TimeInterval = 8.0, completion: @escaping (Result<[W4RPDevice], W4RPError>) -> Void) {
        Task {
            do {
                let devices = try await scan(timeout: timeout)
                completion(.success(devices))
            } catch let error as W4RPError {
                completion(.failure(error))
            } catch {
                completion(.failure(W4RPError(.connectionFailed, "\(error)")))
            }
        }
    }
    
    public func connect(to device: W4RPDevice, timeout: TimeInterval = 10.0, completion: @escaping (Result<Void, W4RPError>) -> Void) {
        Task {
            do {
                try await connect(to: device, timeout: timeout)
                completion(.success(()))
            } catch let error as W4RPError {
                completion(.failure(error))
            } catch {
                completion(.failure(W4RPError(.connectionFailed, "\(error)")))
            }
        }
    }
    
    public func disconnect(completion: @escaping (Result<Void, W4RPError>) -> Void) {
        Task {
            do {
                try await disconnect()
                completion(.success(()))
            } catch let error as W4RPError {
                completion(.failure(error))
            } catch {
                completion(.failure(W4RPError(.connectionFailed, "\(error)")))
            }
        }
    }
    
    public func getProfile(completion: @escaping (Result<W4RPModuleProfile, W4RPError>) -> Void) {
        Task {
            do {
                let profile = try await getProfile()
                completion(.success(profile))
            } catch let error as W4RPError {
                completion(.failure(error))
            } catch {
                completion(.failure(W4RPError(.invalidData, "\(error)")))
            }
        }
    }
    
    public func setRules(
        json: String,
        persistent: Bool,
        onProgress: ((Int, Int) -> Void)? = nil,
        completion: @escaping (Result<Void, W4RPError>) -> Void
    ) {
        Task {
            do {
                try await setRules(json: json, persistent: persistent, onProgress: onProgress)
                completion(.success(()))
            } catch let error as W4RPError {
                completion(.failure(error))
            } catch {
                completion(.failure(W4RPError(.writeFailed, "\(error)")))
            }
        }
    }
    
    public func startOTA(
        patchData: Data,
        onProgress: ((Int, Int) -> Void)? = nil,
        completion: @escaping (Result<Void, W4RPError>) -> Void
    ) {
        Task {
            do {
                try await startOTA(patchData: patchData, onProgress: onProgress)
                completion(.success(()))
            } catch let error as W4RPError {
                completion(.failure(error))
            } catch {
                completion(.failure(W4RPError(.writeFailed, "\(error)")))
            }
        }
    }
    
    // MARK: - Public Utilities
    
    public func stopScan() {
        if connectionState == .scanning {
            connectionState = .disconnected
        }
        central.stopScan()
        scanTimer?.invalidate()
        scanTimer = nil
    }
    
    // MARK: - Private Helpers
    
    private func checkBluetoothState() throws {
        switch central.state {
        case .poweredOff:
            throw W4RPError(.bluetoothOff, "Bluetooth is powered off")
        case .unauthorized:
            throw W4RPError(.bluetoothUnauthorized, "Bluetooth permission denied")
        case .poweredOn:
            break
        default:
            throw W4RPError(.connectionFailed, "Bluetooth not ready (state: \(central.state.rawValue))")
        }
    }
    
    private func ensureConnected() throws {
        guard isConnected, rxChar != nil, peripheral != nil else {
            throw W4RPError(.notConnected, "Not connected to a device")
        }
    }
    
    private func finishScan() {
        stopScan()
        let devices = discoveredDevices
        
        if devices.isEmpty {
            scanContinuation?.resume(throwing: W4RPError(.deviceNotFound, "No W4RP devices found"))
        } else {
            scanContinuation?.resume(returning: devices)
        }
        scanContinuation = nil
    }
    
    private func writeData(_ data: Data, to peripheral: CBPeripheral, char: CBCharacteristic) async throws {
        peripheral.writeValue(data, for: char, type: .withResponse)
    }
    
    private func sendChunked(
        data: Data,
        to peripheral: CBPeripheral,
        char: CBCharacteristic,
        chunkSize: Int = 180,
        onProgress: ((Int, Int) -> Void)? = nil
    ) async throws {
        let totalBytes = data.count
        var offset = 0
        while offset < data.count {
            let end = min(offset + chunkSize, data.count)
            let chunk = data.subdata(in: offset..<end)
            peripheral.writeValue(chunk, for: char, type: .withResponse)
            try await Task.sleep(nanoseconds: 3_000_000)
            offset = end
            onProgress?(offset, totalBytes)
        }
    }
    
    private func streamRequest(command: String, timeout: TimeInterval = 10.0) async throws -> Data {
        guard let p = peripheral, let rx = rxChar else {
            throw W4RPError(.notConnected, "Not connected")
        }
        
        return try await withCheckedThrowingContinuation { continuation in
            streamContinuation = continuation
            streamActive = false
            streamBuffer = Data()
            streamExpectedLen = 0
            streamExpectedCRC = 0
            
            streamTimeout = Timer.scheduledTimer(withTimeInterval: timeout, repeats: false) { [weak self] _ in
                self?.finishStream(error: W4RPError(.streamTimeout, "Stream timed out"))
            }
            
            guard let cmdData = command.data(using: .utf8) else {
                continuation.resume(throwing: W4RPError(.invalidData, "Invalid command"))
                return
            }
            p.writeValue(cmdData, for: rx, type: .withResponse)
        }
    }
    
    private func handleTX(data: Data) {
        guard let text = String(data: data, encoding: .utf8) else {
            if streamActive { streamBuffer.append(data) }
            return
        }
        
        if text == "BEGIN" {
            streamActive = true
            streamBuffer = Data()
            return
        }
        
        if text.hasPrefix("END:") {
            let parts = text.split(separator: ":")
            if parts.count >= 3,
               let len = Int(parts[1]),
               let crc = UInt32(parts[2]) {
                streamExpectedLen = len
                streamExpectedCRC = crc
                finishStream(error: nil)
            }
            return
        }
        
        if text.hasPrefix("D:") {
            handleDebug(text)
            return
        }
        
        if streamActive {
            streamBuffer.append(data)
        }
    }
    
    private func handleDebug(_ text: String) {
        let parts = text.split(separator: ":")
        guard parts.count >= 4 else { return }
        
        let type = String(parts[1])
        let id = String(parts[2])
        let value = String(parts[3])
        
        if type == "S" {
            onDebugData?(W4RPDebugData(id: id, type: .signal, value: Float(value), active: nil))
        } else if type == "N" {
            onDebugData?(W4RPDebugData(id: id, type: .node, value: nil, active: value == "1"))
        }
    }
    
    private func finishStream(error: W4RPError?) {
        streamTimeout?.invalidate()
        streamTimeout = nil
        streamActive = false
        
        guard let continuation = streamContinuation else { return }
        streamContinuation = nil
        
        if let error = error {
            continuation.resume(throwing: error)
            return
        }
        
        guard streamBuffer.count == streamExpectedLen else {
            continuation.resume(throwing: W4RPError(.lengthMismatch,
                "Length mismatch: \(streamBuffer.count) != \(streamExpectedLen)"))
            return
        }
        
        let crc = crc32(streamBuffer)
        guard crc == streamExpectedCRC else {
            continuation.resume(throwing: W4RPError(.crcMismatch, "CRC mismatch"))
            return
        }
        
        continuation.resume(returning: streamBuffer)
    }
    
    private func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFFFFFF
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0..<8 {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xEDB88320 : crc >> 1
            }
        }
        return crc ^ 0xFFFFFFFF
    }
}

// MARK: - CoreBluetooth Delegates

extension W4RPBridge: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {}
    
    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = peripheral.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? "Unknown"
        
        if deviceMap[peripheral.identifier] != nil {
            deviceMap[peripheral.identifier]?.rssi = RSSI.intValue
        } else {
            deviceMap[peripheral.identifier] = W4RPDevice(
                peripheral: peripheral,
                name: name,
                rssi: RSSI.intValue,
                advertisementData: advertisementData
            )
        }
        
        discoveredDevices = Array(deviceMap.values).sorted { $0.rssi > $1.rssi }
    }
    
    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectionTimer?.invalidate()
        connectionState = .discoveringServices
        peripheral.discoverServices([uuids.service])
    }
    
    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectionTimer?.invalidate()
        connectionState = .error
        let err = W4RPError(.connectionFailed, error?.localizedDescription ?? "Connection failed")
        lastError = err
        connectContinuation?.resume(throwing: err)
        connectContinuation = nil
    }
    
    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let previousState = connectionState
        connectionState = .disconnected
        rxChar = nil
        txChar = nil
        statusChar = nil
        self.peripheral = nil
        connectedDeviceName = nil
        
        // Resume any pending disconnect continuation
        disconnectContinuation?.resume()
        disconnectContinuation = nil
        
        // Check for auto-reconnect
        guard !wasIntentionalDisconnect,
              autoReconnectConfig.enabled,
              previousState == .ready || previousState == .discoveringServices,
              let device = lastConnectedDevice,
              !isAutoReconnecting else {
            wasIntentionalDisconnect = false
            return
        }
        
        // Check lifetime reconnect limit
        if autoReconnectConfig.maxLifetimeReconnects > 0 &&
           lifetimeReconnectCount >= autoReconnectConfig.maxLifetimeReconnects {
            let err = W4RPError(.connectionFailed, "Max lifetime reconnects exceeded")
            onReconnectFailed?(err)
            return
        }
        
        // Start auto-reconnect
        isAutoReconnecting = true
        
        Task { @MainActor in
            var lastReconnectError: W4RPError?
            
            for attempt in 0...autoReconnectConfig.retryConfig.maxRetries {
                lifetimeReconnectCount += 1
                onReconnecting?(attempt + 1)
                
                do {
                    try await connect(to: device)
                    isAutoReconnecting = false
                    onReconnected?()
                    return
                } catch let error as W4RPError {
                    lastReconnectError = error
                    
                    if attempt < autoReconnectConfig.retryConfig.maxRetries {
                        let delay = autoReconnectConfig.retryConfig.delay(forAttempt: attempt)
                        try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                    }
                }
            }
            
            isAutoReconnecting = false
            onReconnectFailed?(lastReconnectError ?? W4RPError(.connectionFailed, "Auto-reconnect failed"))
        }
    }
}

extension W4RPBridge: CBPeripheralDelegate {
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == uuids.service {
            peripheral.discoverCharacteristics([uuids.rx, uuids.tx, uuids.status], for: service)
        }
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let chars = service.characteristics else { return }
        
        for c in chars {
            if c.uuid == uuids.rx { rxChar = c }
            if c.uuid == uuids.tx { txChar = c; peripheral.setNotifyValue(true, for: c) }
            if c.uuid == uuids.status { statusChar = c; peripheral.setNotifyValue(true, for: c) }
        }
        
        if rxChar != nil && txChar != nil && statusChar != nil {
            connectionState = .ready
            lastError = nil
            connectContinuation?.resume()
            connectContinuation = nil
        } else {
            connectionState = .error
            let err = W4RPError(.characteristicNotFound, "Missing characteristics")
            lastError = err
            connectContinuation?.resume(throwing: err)
            connectContinuation = nil
        }
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let value = characteristic.value else { return }
        
        if characteristic.uuid == uuids.tx {
            handleTX(data: value)
        } else if characteristic.uuid == uuids.status {
            if let text = String(data: value, encoding: .utf8) {
                onStatusUpdate?(text)
            }
        }
    }
}
