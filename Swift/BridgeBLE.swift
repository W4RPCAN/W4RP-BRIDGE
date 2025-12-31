/**
 * W4RP Bridge - Core BLE Implementation
 *
 * CoreBluetooth client for W4RPBLE firmware modules.
 * Provides async/await APIs for scanning, connecting, and communicating.
 *
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 2.0.0
 */

import Foundation
@preconcurrency import CoreBluetooth
import Combine

// MARK: - W4RPBridge

/**
 * Core BLE bridge for W4RP modules.
 *
 * Usage:
 * ```swift
 * let bridge = W4RPBridge()
 * let devices = try await bridge.scan()
 * try await bridge.connect(to: devices[0])
 * let profile = try await bridge.getProfile()
 * ```
 */
@MainActor
public final class W4RPBridge: NSObject, ObservableObject {
    
    // MARK: - Platform
    
    /** Platform identifier for JS interop. */
    public let platform = "ios"
    
    // MARK: - Published State
    
    /// Internal flags for intermediate states that can't be derived from CBPeripheral alone
    private var _isScanning = false
    private var _isDiscoveringServices = false
    
    @Published public private(set) var discoveredDevices: [W4RPDevice] = []
    @Published public private(set) var connectedDeviceName: String?
    @Published public private(set) var lastError: W4RPError?
    @Published public private(set) var bluetoothStatus: W4RPBluetoothStatus = .unknown
    
    /// Connection state is now COMPUTED from actual BLE state - NEVER stored separately
    /// This guarantees it always reflects reality from the iOS Bluetooth API
    public var connectionState: W4RPConnectionState {
        // If scanning, that takes priority
        if _isScanning {
            return .scanning
        }
        
        // Check if we have a connected peripheral
        if let id = connectedPeripheralId, let peripheral = peripheralMap[id] {
            // print("🔵 [BLE] Computing state for \(id): peripheral.state=\(peripheral.state.rawValue), discovering=\(_isDiscoveringServices)")
            switch peripheral.state {
            case .connecting:
                return .connecting
            case .connected:
                // If connected but still discovering services/chars, return that state
                if _isDiscoveringServices || rxChar == nil {
                    return .discoveringServices
                }
                // Fully connected with characteristics = READY
                return .ready
            case .disconnecting:
                return .disconnecting
            case .disconnected:
                return .disconnected
            @unknown default:
                return .disconnected
            }
        } else {
             if let id = connectedPeripheralId {
                 print("🔴 [BLE] connectionState: Have ID \(id) but NOT in peripheralMap!")
             }
        }
        
        // No peripheral tracked = disconnected
        return .disconnected
    }
    
    // MARK: - Configuration
    
    public var uuids: W4RPUUIDs = .default
    public var autoReconnectConfig: W4RPAutoReconnectConfig = .disabled
    
    // MARK: - Callbacks
    
    public var onConnectionStateChanged: ((W4RPConnectionState) -> Void)?
    public var onDeviceDiscovered: (([W4RPDevice]) -> Void)?
    public var onStatusUpdate: ((String) -> Void)?
    public var onDebugData: ((W4RPDebugData) -> Void)?
    public var onError: ((W4RPError) -> Void)?
    public var onReconnecting: ((Int) -> Void)?
    public var onReconnected: (() -> Void)?
    public var onReconnectFailed: ((W4RPError) -> Void)?
    public var onBluetoothStatusChanged: ((W4RPBluetoothStatus) -> Void)?
    
    // MARK: - Computed Properties
    
    public var isConnected: Bool { connectionState == .ready }
    public var isScanning: Bool { connectionState == .scanning }
    
    public var connectedDevice: W4RPDevice? {
        guard connectionState == .ready, let id = connectedPeripheralId else { return nil }
        return deviceMap[id]
    }

    
    // MARK: - Private State
    
    private var central: CBCentralManager!
    
    /** Maps device UUID to CBPeripheral - keeps framework objects internal. */
    private var peripheralMap: [UUID: CBPeripheral] = [:]
    
    /** Maps device UUID to W4RPDevice data. */
    private var deviceMap: [UUID: W4RPDevice] = [:]
    
    /** Currently connected peripheral ID. */
    private var connectedPeripheralId: UUID? {
        didSet {
            print("🔵 [BLE] connectedPeripheralId changed: \(oldValue?.uuidString ?? "nil") -> \(connectedPeripheralId?.uuidString ?? "nil")")
        }
    }
    
    /** Cached CBUUIDs created from string UUIDs. */
    private lazy var serviceCBUUID: CBUUID = { CBUUID(string: uuids.service) }()
    private lazy var rxCBUUID: CBUUID = { CBUUID(string: uuids.rx) }()
    private lazy var txCBUUID: CBUUID = { CBUUID(string: uuids.tx) }()
    private lazy var statusCBUUID: CBUUID = { CBUUID(string: uuids.status) }()
    
    private var rxChar: CBCharacteristic?
    private var txChar: CBCharacteristic?
    private var statusChar: CBCharacteristic?
    
    private var scanTimer: Timer?
    private var connectionTimer: Timer?
    
    private var lastConnectedDeviceId: UUID?
    private var isAutoReconnecting = false
    private var lifetimeReconnectCount = 0
    private var wasIntentionalDisconnect = false
    
    private var streamActive = false
    private var streamBuffer = Data()
    private var streamExpectedLen = 0
    private var streamExpectedCRC: UInt32 = 0
    private var streamContinuation: CheckedContinuation<Data, Error>?
    private var streamTimeout: Timer?
    
    private var scanContinuation: CheckedContinuation<[W4RPDevice], Error>?
    private var connectContinuation: CheckedContinuation<Void, Error>?
    private var disconnectContinuation: CheckedContinuation<Void, Error>?
    
    // MARK: - Init
    
    public override init() {
        super.init()
        print("🔵 [BLE] W4RPBridge initialized (Address: \(Unmanaged.passUnretained(self).toOpaque()))")
        central = CBCentralManager(delegate: self, queue: .main)
    }
    
    deinit {
        print("🔵 [BLE] W4RPBridge deallocated (Address: \(Unmanaged.passUnretained(self).toOpaque()))")
    }
    
    // MARK: - Scanning
    
    /**
     * Scan for W4RP devices.
     * @param timeout - Scan duration in seconds (default: 8)
     * @returns Array of discovered devices sorted by signal strength
     * @throws W4RPError if Bluetooth unavailable or scan fails
     */
    public func scan(timeout: TimeInterval = 8.0) async throws -> [W4RPDevice] {
        try checkBluetoothState()
        
        if let existing = scanContinuation {
            scanTimer?.invalidate()
            scanContinuation = nil
            existing.resume(throwing: W4RPError(.scanCancelled, "New scan started"))
        }
        
        return try await withCheckedThrowingContinuation { continuation in
            scanContinuation = continuation
            setInternalState(scanning: true)  // Mark as scanning
            // Clear discovered devices list for fresh UI, but keep peripheralMap for reconnection
            discoveredDevices = []
            
            central.scanForPeripherals(withServices: [serviceCBUUID], options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
            
            scanTimer = Timer.scheduledTimer(withTimeInterval: timeout, repeats: false) { [weak self] _ in
                Task { @MainActor in self?.finishScan() }
            }
        }
    }
    
    /** Stop an active scan. */
    public func stopScan() {
        scanTimer?.invalidate()
        scanTimer = nil
        central.stopScan()
        
        // Clear scanning flag - connectionState will recompute correctly
        _isScanning = false
        notifyStateChanged()
        
        if let continuation = scanContinuation {
            scanContinuation = nil
            continuation.resume(returning: discoveredDevices)
        }
    }
    
    // MARK: - Connection
    
    /**
     * Connect to a discovered device.
     * @param device - Device to connect to
     * @param timeout - Connection timeout in seconds (default: 10)
     * @throws W4RPError if connection fails or times out
     */
    public func connect(to device: W4RPDevice, timeout: TimeInterval = 10.0) async throws {
        // If already connected to a different device, disconnect first
        if connectionState == .ready, connectedPeripheralId != device.id {
            try await disconnect()
        }
        
        // If already connected to this device, just return success (no-op)
        guard connectionState != .ready else {
            print("🔵 [BLE] Already connected to this device, returning success")
            return
        }
        
        guard let peripheral = peripheralMap[device.id] else {
            throw W4RPError(.deviceNotFound, "Device not found in peripheral map")
        }
        
        stopScan()
        // connectionState will compute to .connecting from peripheral.state
        notifyStateChanged()
        wasIntentionalDisconnect = false
        lastConnectedDeviceId = device.id
        
        return try await withCheckedThrowingContinuation { continuation in
            connectContinuation = continuation
            connectedPeripheralId = device.id
            connectedDeviceName = device.name
            peripheral.delegate = self
            
            connectionTimer = Timer.scheduledTimer(withTimeInterval: timeout, repeats: false) { [weak self] _ in
                Task { @MainActor in
                    guard let self = self, self.connectionState != .ready else { return }
                    self.central.cancelPeripheralConnection(peripheral)
                    self.handleError(W4RPError(.connectionTimeout, "Connection timed out"))
                    self.connectContinuation?.resume(throwing: self.lastError!)
                    self.connectContinuation = nil
                }
            }
            
            central.connect(peripheral, options: nil)
        }
    }
    
    /**
     * Disconnect from the current device.
     * @throws W4RPError if disconnect fails
     */
    public func disconnect() async throws {
        guard let id = connectedPeripheralId, let peripheral = peripheralMap[id] else { return }
        
        wasIntentionalDisconnect = true
        // connectionState will compute to .disconnecting from peripheral.state
        notifyStateChanged()
        
        return try await withCheckedThrowingContinuation { continuation in
            disconnectContinuation = continuation
            central.cancelPeripheralConnection(peripheral)
        }
    }
    
    // MARK: - Module Operations
    
    /**
     * Fetch raw WBP profile binary from the module.
     *
     * Returns raw bytes - UI is responsible for parsing.
     *
     * - Returns: Raw WBP binary data
     * - Throws: W4RPError if not connected or request fails
     */
    public func getProfile() async throws -> Data {
        try ensureConnected()
        return try await streamRequest(command: "GET:PROFILE")
    }
    
    /**
     * Fetch raw WBP rules binary from the module.
     *
     * Returns raw bytes - UI is responsible for parsing.
     *
     * - Returns: Raw WBP binary data (or throws if no rules loaded)
     * - Throws: W4RPError if not connected or request fails
     */
    public func getRules() async throws -> Data {
        try ensureConnected()
        return try await streamRequest(command: "GET:RULES")
    }
    
    /**
     * Send compiled WBP rules binary to the module.
     *
     * - Parameters:
     *   - binary: Compiled WBP binary ruleset (from compiler)
     *   - persistent: If true, rules are saved to NVS; false for RAM only
     *   - onProgress: Optional progress callback (bytesWritten, totalBytes)
     * - Throws: W4RPError if not connected or write fails
     */
    public func setRules(binary: Data, persistent: Bool, onProgress: ((Int, Int) -> Void)? = nil) async throws {
        try ensureConnected()
        guard let id = connectedPeripheralId, let p = peripheralMap[id], let rx = rxChar else {
            throw W4RPError(.notConnected, "Not connected")
        }
        
        let crc = crc32(binary)
        let mode = persistent ? "NVS" : "RAM"
        let header = "SET:RULES:\(mode):\(binary.count):\(crc)"
        
        try await writeData(header.data(using: .utf8)!, to: p, char: rx)
        try await sendChunked(data: binary, to: p, char: rx, onProgress: onProgress)
        try await writeData("END".data(using: .utf8)!, to: p, char: rx)
    }
    
    /**
     * Enable or disable debug mode.
     * @param enabled - Whether to enable debug streaming
     * @throws W4RPError if not connected or command fails
     */
    public func setDebugMode(enabled: Bool) async throws {
        try ensureConnected()
        guard let id = connectedPeripheralId, let p = peripheralMap[id], let rx = rxChar else {
            throw W4RPError(.notConnected, "Not connected")
        }
        
        let cmd = enabled ? "DEBUG:START" : "DEBUG:STOP"
        try await writeData(cmd.data(using: .utf8)!, to: p, char: rx)
    }
    
    /**
     * Set specific signals to watch in debug mode.
     * Sends comma-separated signal specs to firmware.
     * @param signals - Array of signal objects with can_id, start, length/len, factor, offset, big_endian/be
     * @throws W4RPError if not connected or command fails
     */
    public func watchDebugSignals(_ signals: [[String: Any]]) async throws {
        try ensureConnected()
        guard let id = connectedPeripheralId, let p = peripheralMap[id], let rx = rxChar else {
            throw W4RPError(.notConnected, "Not connected")
        }
        
        // Build comma-separated specs: canId:start:len:be:factor:offset
        let specs: [String] = signals.map { s in
            let canId = s["can_id"] as? Int ?? 0
            let start = s["start"] as? Int ?? s["start_bit"] as? Int ?? 0
            let len = s["length"] as? Int ?? s["len"] as? Int ?? 8
            let be = (s["big_endian"] as? Bool ?? s["be"] as? Bool ?? false) ? 1 : 0
            let factor = s["factor"] as? Double ?? 1.0
            let offset = s["offset"] as? Double ?? 0.0
            return "\(canId):\(start):\(len):\(be):\(factor):\(offset)"
        }
        
        let payload = specs.joined(separator: ",")
        guard let payloadData = payload.data(using: .utf8) else { return }
        let crc = crc32(payloadData)
        let header = "DEBUG:WATCH:\(payloadData.count):\(crc)"
        
        try await writeData(header.data(using: .utf8)!, to: p, char: rx)
        try await Task.sleep(nanoseconds: 50_000_000)
        try await sendChunked(data: payloadData, to: p, char: rx)
        try await writeData("END".data(using: .utf8)!, to: p, char: rx)
    }
    
    /// Internal flag to cancel ongoing OTA
    private var otaCancelled = false
    
    /**
     * Cancel an ongoing OTA update.
     * This sets an internal flag that will abort the OTA at the next chunk boundary.
     */
    public func stopOTA() {
        otaCancelled = true
    }
    
    /**
     * Start a Delta OTA firmware update.
     * @param patchData - Binary patch data
     * @param onProgress - Optional progress callback
     * @throws W4RPError if not connected or OTA fails
     */
    public func startOTA(patchData: Data, onProgress: ((Int, Int) -> Void)? = nil) async throws {
        try ensureConnected()
        guard let id = connectedPeripheralId, let p = peripheralMap[id], let rx = rxChar else {
            throw W4RPError(.notConnected, "Not connected")
        }
        
        otaCancelled = false // Reset cancellation flag
        
        let crc = crc32(patchData)
        let cmd = "OTA:DELTA:\(patchData.count):\(String(format: "%X", crc))"
        
        try await writeData(cmd.data(using: .utf8)!, to: p, char: rx)
        try await Task.sleep(nanoseconds: 200_000_000)
        try await sendChunked(data: patchData, to: p, char: rx, onProgress: onProgress)
        
        if otaCancelled {
            try await writeData("OTA:CANCEL".data(using: .utf8)!, to: p, char: rx)
            throw W4RPError(.otaCancelled, "OTA cancelled by user")
        }
        
        try await writeData("END".data(using: .utf8)!, to: p, char: rx)
    }
    
    // MARK: - Private Helpers
    
    /// Notifies observers of the current connection state (computed from BLE reality)
    private func notifyStateChanged() {
        let currentState = connectionState  // Read computed property
        onConnectionStateChanged?(currentState)
    }
    
    /// Sets internal flags based on desired state and notifies observers
    private func setInternalState(scanning: Bool? = nil, discoveringServices: Bool? = nil) {
        if let s = scanning { _isScanning = s }
        if let d = discoveringServices { _isDiscoveringServices = d }
        notifyStateChanged()
    }
    
    private func handleError(_ error: W4RPError) {
        lastError = error
        // Clear internal flags on error
        _isScanning = false
        _isDiscoveringServices = false
        notifyStateChanged()
        onError?(error)
    }
    
    private func checkBluetoothState() throws {
        switch central.state {
        case .poweredOff: throw W4RPError(.bluetoothOff, "Bluetooth is off")
        case .unauthorized: throw W4RPError(.bluetoothUnauthorized, "Bluetooth unauthorized")
        case .unsupported: throw W4RPError(.bluetoothUnsupported, "Bluetooth unsupported")
        case .poweredOn: break
        default: throw W4RPError(.bluetoothOff, "Bluetooth not ready")
        }
    }
    
    private func ensureConnected() throws {
        guard isConnected, rxChar != nil, connectedPeripheralId != nil else {
            throw W4RPError(.notConnected, "Not connected")
        }
    }
    
    private func finishScan() {
        scanTimer?.invalidate()
        scanTimer = nil
        central.stopScan()
        
        // Clear scanning flag - connectionState will recompute from peripheral state
        _isScanning = false
        notifyStateChanged()
        
        if let continuation = scanContinuation {
            scanContinuation = nil
            // Always return devices (even empty array) - no error on empty results
            continuation.resume(returning: discoveredDevices)
        }
    }
    
    private func writeData(_ data: Data, to peripheral: CBPeripheral, char: CBCharacteristic) async throws {
        peripheral.writeValue(data, for: char, type: .withResponse)
    }
    
    private func sendChunked(data: Data, to peripheral: CBPeripheral, char: CBCharacteristic, chunkSize: Int = 180, onProgress: ((Int, Int) -> Void)? = nil) async throws {
        var offset = 0
        while offset < data.count {
            let end = min(offset + chunkSize, data.count)
            let chunk = data.subdata(in: offset..<end)
            peripheral.writeValue(chunk, for: char, type: .withResponse)
            try await Task.sleep(nanoseconds: 3_000_000)
            offset = end
            onProgress?(offset, data.count)
        }
    }
    
    private func streamRequest(command: String, timeout: TimeInterval = 10.0) async throws -> Data {
        guard let id = connectedPeripheralId, let p = peripheralMap[id], let rx = rxChar else {
            throw W4RPError(.notConnected, "Not connected")
        }
        
        return try await withCheckedThrowingContinuation { continuation in
            streamContinuation = continuation
            streamActive = false
            streamBuffer = Data()
            streamExpectedLen = 0
            streamExpectedCRC = 0
            
            streamTimeout = Timer.scheduledTimer(withTimeInterval: timeout, repeats: false) { [weak self] _ in
                Task { @MainActor in
                    self?.finishStream(error: W4RPError(.streamTimeout, "Stream timed out"))
                }
            }
            
            guard let cmdData = command.data(using: .utf8) else {
                continuation.resume(throwing: W4RPError(.invalidData, "Invalid command"))
                return
            }
            p.writeValue(cmdData, for: rx, type: .withResponse)
        }
    }
    
    private func handleTX(data: Data) {
        if let text = String(data: data, encoding: .utf8) {
            if text == "BEGIN" {
                streamActive = true
                streamBuffer = Data()
                return
            }
            
            if text.hasPrefix("END:") {
                let parts = text.split(separator: ":")
                if parts.count >= 3, let len = Int(parts[1]), let crc = UInt32(parts[2]) {
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
            
            // Error responses from firmware (ERR:LEN_MISMATCH, ERR:CRC_FAIL, etc.)
            if text.hasPrefix("ERR:") {
                let errorType = String(text.dropFirst(4))
                let error = W4RPError(.invalidResponse, "Firmware error: \(errorType)")
                handleError(error)
                
                // If we have an active stream, fail it
                if streamActive || streamContinuation != nil {
                    finishStream(error: error)
                }
                return
            }
            
            // OTA responses (informational)
            if text.hasPrefix("OTA:") {
                // OTA:READY, OTA:SUCCESS, OTA:ERROR, OTA:CANCELLED
                return
            }
        }
        
        if streamActive {
            streamBuffer.append(data)
        }
    }
    
    private func handleDebug(_ text: String) {
        let parts = text.split(separator: ":")
        guard parts.count >= 4 else { return }
        
        let type = String(parts[1])
        
        if type == "S" {
            // D:S:<can>:<start>:<len>:<be>:<factor>:<offset>:<value>
            // Signal key = parts[2] through parts[length-2]
            // Value = parts[length-1]
            let idParts = parts[2..<parts.count-1]
            let id = idParts.joined(separator: ":")
            let value = Float(String(parts.last!)) ?? 0.0
            
            onDebugData?(W4RPDebugData(id: id, type: .signal, value: value, active: nil))
        } else if type == "N" {
            let id = String(parts[2])
            let value = String(parts[3])
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
            continuation.resume(throwing: W4RPError(.lengthMismatch, "Length mismatch"))
            return
        }
        
        guard crc32(streamBuffer) == streamExpectedCRC else {
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

// MARK: - CBCentralManagerDelegate

extension W4RPBridge: CBCentralManagerDelegate {
    nonisolated public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            
            // Map CBCentralManager state to unified W4RPBluetoothStatus
            let newStatus: W4RPBluetoothStatus
            switch central.state {
            case .poweredOn:
                newStatus = .ready
            case .poweredOff:
                newStatus = .poweredOff
            case .unauthorized:
                newStatus = .unauthorized
            case .unsupported:
                newStatus = .unsupported
            case .resetting:
                newStatus = .unavailable
            case .unknown:
                newStatus = .unknown
            @unknown default:
                newStatus = .unknown
            }
            
            // Only notify if status changed
            if self.bluetoothStatus != newStatus {
                print("🔵 [BLE] centralManagerDidUpdateState: \(self.bluetoothStatus.rawValue) -> \(newStatus.rawValue)")
                self.bluetoothStatus = newStatus
                print("🔵 [BLE] Calling onBluetoothStatusChanged callback (isNil: \(self.onBluetoothStatusChanged == nil))")
                self.onBluetoothStatusChanged?(newStatus)
            } else {
                print("🔵 [BLE] centralManagerDidUpdateState: status unchanged (\(newStatus.rawValue))")
            }
            
            // Handle unavailable states - cancel pending operations
            if central.state != .poweredOn {
                if let continuation = self.scanContinuation {
                    self.scanTimer?.invalidate()
                    self.scanContinuation = nil
                    continuation.resume(throwing: W4RPError(.bluetoothOff, "Bluetooth unavailable"))
                }
                if let continuation = self.connectContinuation {
                    self.connectionTimer?.invalidate()
                    self.connectContinuation = nil
                    continuation.resume(throwing: W4RPError(.bluetoothOff, "Bluetooth unavailable"))
                }
                // Clear internal flags - connectionState computes from BLE reality
                self._isScanning = false
                self._isDiscoveringServices = false
                self.notifyStateChanged()
            }
        }
    }
    
    nonisolated public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = peripheral.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? "Unknown"
        let id = peripheral.identifier
        let rssi = RSSI.intValue
        
        print("🔵 [BLE] Discovered: peripheral.name=\(peripheral.name ?? "nil"), localName=\(advertisementData[CBAdvertisementDataLocalNameKey] ?? "nil"), using: \(name), id=\(id.uuidString)")
        
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            
            // Store peripheral reference internally
            self.peripheralMap[id] = peripheral
            
            // Update or create device data
            if var existingDevice = self.deviceMap[id] {
                existingDevice.rssi = rssi
                self.deviceMap[id] = existingDevice
            } else {
                self.deviceMap[id] = W4RPDevice(id: id, name: name, rssi: rssi)
            }
            
            self.discoveredDevices = Array(self.deviceMap.values).sorted { $0.rssi > $1.rssi }
            self.onDeviceDiscovered?(self.discoveredDevices)
        }
    }
    
    nonisolated public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            self.connectionTimer?.invalidate()
            self.setInternalState(discoveringServices: true)
            peripheral.discoverServices([self.serviceCBUUID])
        }
    }
    
    nonisolated public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            self.connectionTimer?.invalidate()
            let err = W4RPError(.connectionFailed, error?.localizedDescription ?? "Connection failed")
            self.handleError(err)
            self.connectContinuation?.resume(throwing: err)
            self.connectContinuation = nil
        }
    }
    
    nonisolated public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            
            // Clear internal state - connectionState computes from BLE reality
            self._isScanning = false
            self._isDiscoveringServices = false
            self.rxChar = nil
            self.txChar = nil
            self.statusChar = nil
            self.connectedPeripheralId = nil
            self.connectedDeviceName = nil
            self.notifyStateChanged()  // Will compute to DISCONNECTED
            
            self.disconnectContinuation?.resume()
            self.disconnectContinuation = nil
            
            guard !self.wasIntentionalDisconnect,
                  self.autoReconnectConfig.enabled,
                  let deviceId = self.lastConnectedDeviceId,
                  let device = self.deviceMap[deviceId],
                  !self.isAutoReconnecting else {
                self.wasIntentionalDisconnect = false
                return
            }
            
            self.isAutoReconnecting = true
            
            for attempt in 0...self.autoReconnectConfig.retryConfig.maxRetries {
                self.lifetimeReconnectCount += 1
                self.onReconnecting?(attempt + 1)
                
                do {
                    try await self.connect(to: device)
                    self.isAutoReconnecting = false
                    self.onReconnected?()
                    return
                } catch {
                    if attempt < self.autoReconnectConfig.retryConfig.maxRetries {
                        let delay = self.autoReconnectConfig.retryConfig.delay(forAttempt: attempt)
                        try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                    }
                }
            }
            
            self.isAutoReconnecting = false
            self.onReconnectFailed?(W4RPError(.connectionFailed, "Auto-reconnect failed"))
        }
    }
}

// MARK: - CBPeripheralDelegate

extension W4RPBridge: CBPeripheralDelegate {
    nonisolated public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        Task { @MainActor [weak self] in
            guard let self = self, let services = peripheral.services else { return }
            for service in services where service.uuid == self.serviceCBUUID {
                peripheral.discoverCharacteristics([self.rxCBUUID, self.txCBUUID, self.statusCBUUID], for: service)
            }
        }
    }
    
    nonisolated public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        Task { @MainActor [weak self] in
            guard let self = self, let chars = service.characteristics else { return }
            
            for c in chars {
                switch c.uuid {
                case self.rxCBUUID: self.rxChar = c
                case self.txCBUUID: self.txChar = c; peripheral.setNotifyValue(true, for: c)
                case self.statusCBUUID: self.statusChar = c; peripheral.setNotifyValue(true, for: c)
                default: break
                }
            }
            
            if self.rxChar != nil && self.txChar != nil && self.statusChar != nil {
                // Clear discovering flag - connectionState will compute to READY
                self._isDiscoveringServices = false
                self.notifyStateChanged()
                self.connectContinuation?.resume()
                self.connectContinuation = nil
            } else {
                let err = W4RPError(.characteristicNotFound, "Missing characteristics")
                self.handleError(err)
                self.connectContinuation?.resume(throwing: err)
                self.connectContinuation = nil
            }
        }
    }
    
    nonisolated public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let value = characteristic.value else { return }
        let data = Data(value)
        let uuid = characteristic.uuid
        
        Task { @MainActor [weak self] in
            guard let self = self else { return }
            if uuid == self.txCBUUID {
                self.handleTX(data: data)
            } else if uuid == self.statusCBUUID {
                if let text = String(data: data, encoding: .utf8) {
                    self.onStatusUpdate?(text)
                }
            }
        }
    }
}
