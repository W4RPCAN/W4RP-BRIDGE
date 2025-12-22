/**
 * W4RPBridge.swift
 *
 * W4RP BLE Bridge - Swift/iOS/macOS Implementation
 * Official client library for connecting to W4RPBLE firmware modules.
 *
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 1.0.0
 *
 * Compatible with:
 * - iOS 14.0+
 * - macOS 11.0+
 * - watchOS 7.0+ (limited)
 * - tvOS 14.0+ (limited)
 *
 * Required Info.plist keys:
 * - NSBluetoothAlwaysUsageDescription
 * - NSBluetoothPeripheralUsageDescription (iOS 12 and earlier)
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
public struct W4RPUUIDs {
    /// Primary GATT Service UUID - filter for this during scanning
    public let service: CBUUID
    /// RX Characteristic - Write commands to the module
    public let rx: CBUUID
    /// TX Characteristic - Receive data from module (Notify)
    public let tx: CBUUID
    /// Status Characteristic - Module status updates (Notify)
    public let status: CBUUID
    
    /// Default W4RP UUIDs
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
    // MARK: 1xxx - BLE Infrastructure
    
    /// GATT connection failed
    case connectionFailed       = 1000
    /// Connection was lost unexpectedly
    case connectionLost         = 1001
    /// Operation requires an active connection
    case notConnected           = 1002
    /// Already connected to a device
    case alreadyConnected       = 1003
    /// No device was found during scan
    case deviceNotFound         = 1004
    /// W4RP service not found on device
    case serviceNotFound        = 1005
    /// Required characteristic not found
    case characteristicNotFound = 1006
    /// Bluetooth adapter is powered off
    case bluetoothOff           = 1007
    /// Bluetooth permission denied by user or system
    case bluetoothUnauthorized  = 1008
    
    // MARK: 2xxx - Protocol Errors
    
    /// Module returned an invalid or unexpected response
    case invalidResponse        = 2000
    /// Failed to write data to the RX characteristic
    case writeFailed            = 2003
    
    // MARK: 3xxx - Data Validation Errors
    
    /// Data format is invalid (e.g., not valid UTF-8)
    case invalidData            = 3000
    /// CRC32 checksum does not match expected value
    case crcMismatch            = 3001
    /// Received data length does not match expected length
    case lengthMismatch         = 3002
    
    // MARK: 4xxx - Timeout Errors
    
    /// Module profile request timed out
    case profileTimeout         = 4000
    /// Data stream timed out before completion
    case streamTimeout          = 4001
    /// Device scan timed out with no results
    case scanTimeout            = 4002
    /// Connection attempt timed out
    case connectionTimeout      = 4003
}

/// Custom error type for W4RP operations
public struct W4RPError: Error, Sendable {
    /// Standardized error code
    public let code: W4RPErrorCode
    /// Human-readable error message
    public let message: String
    /// Additional context for debugging
    public let context: [String: String]?
    
    public init(_ code: W4RPErrorCode, _ message: String, context: [String: String]? = nil) {
        self.code = code
        self.message = message
        self.context = context
    }
}

// MARK: - Data Models

/// Discovered W4RP device during scanning
public struct W4RPDevice: Identifiable {
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

/// Module profile returned by GET:PROFILE command
public struct W4RPModuleProfile: Decodable, Sendable {
    public let module: ModuleInfo
    public let capabilities: [String: Capability]?
    public let runtime: RuntimeInfo?
    
    public struct ModuleInfo: Decodable, Sendable {
        /// Unique module identifier (e.g., "W4RP-A1B2C3")
        public let id: String
        /// Hardware revision (e.g., "esp32c3-mini-1")
        public let hw: String?
        /// Firmware version (e.g., "1.0.0")
        public let fw: String?
        /// Human-readable device name
        public let device_name: String?
    }
    
    public struct Capability: Decodable, Sendable {
        /// Human-readable label
        public let label: String?
        /// Category for grouping
        public let category: String?
        /// Parameters this capability accepts
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

/// Debug data from module
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

// MARK: - Bridge

/**
 * W4RPBridge - CoreBluetooth client for W4RPBLE modules.
 *
 * Usage:
 * ```swift
 * let bridge = W4RPBridge()
 *
 * bridge.startScan { result in
 *     switch result {
 *     case .success(let devices):
 *         bridge.connect(to: devices[0]) { ... }
 *     case .failure(let error):
 *         print("Scan failed: \(error.message)")
 *     }
 * }
 * ```
 */
public final class W4RPBridge: NSObject, ObservableObject {
    
    // MARK: Published State
    
    @Published public private(set) var isConnected = false
    @Published public private(set) var isScanning = false
    @Published public private(set) var discoveredDevices: [W4RPDevice] = []
    @Published public private(set) var connectedDeviceName: String?
    
    // MARK: Configuration
    
    /// BLE UUIDs to use (defaults to standard W4RP UUIDs)
    public var uuids: W4RPUUIDs = .default
    
    // MARK: Callbacks
    
    /// Called when module sends a status update
    public var onStatusUpdate: ((String) -> Void)?
    /// Called when debug data is received
    public var onDebugData: ((W4RPDebugData) -> Void)?
    
    // MARK: Private State
    
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var rxChar: CBCharacteristic?
    private var txChar: CBCharacteristic?
    private var statusChar: CBCharacteristic?
    
    private var deviceMap: [UUID: W4RPDevice] = [:]
    private var scanTimer: Timer?
    private var connectionTimer: Timer?
    
    // Stream State
    private var streamActive = false
    private var streamBuffer = Data()
    private var streamExpectedLen = 0
    private var streamExpectedCRC: UInt32 = 0
    private var streamCompletion: ((Result<Data, W4RPError>) -> Void)?
    private var streamTimeout: Timer?
    
    // Completions
    private var connectCompletion: ((Result<Void, W4RPError>) -> Void)?
    private var disconnectCompletion: ((Result<Void, W4RPError>) -> Void)?
    
    // MARK: Initialization
    
    public override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }
    
    // MARK: - Public API
    
    /**
     * Start scanning for W4RP devices.
     *
     * - Parameter timeout: Scan duration in seconds (default: 8)
     * - Parameter completion: Called with discovered devices or error
     */
    public func startScan(
        timeout: TimeInterval = 8.0,
        completion: @escaping (Result<[W4RPDevice], W4RPError>) -> Void
    ) {
        guard central.state == .poweredOn else {
            let error: W4RPError
            switch central.state {
            case .poweredOff:
                error = W4RPError(.bluetoothOff, "Bluetooth is powered off")
            case .unauthorized:
                error = W4RPError(.bluetoothUnauthorized, "Bluetooth permission denied")
            default:
                error = W4RPError(.connectionFailed, "Bluetooth not ready (state: \(central.state.rawValue))")
            }
            completion(.failure(error))
            return
        }
        
        isScanning = true
        deviceMap.removeAll()
        discoveredDevices = []
        
        central.scanForPeripherals(
            withServices: [uuids.service],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        
        scanTimer = Timer.scheduledTimer(withTimeInterval: timeout, repeats: false) { [weak self] _ in
            self?.stopScan()
            let devices = self?.discoveredDevices ?? []
            if devices.isEmpty {
                completion(.failure(W4RPError(.deviceNotFound, "No W4RP devices found")))
            } else {
                completion(.success(devices))
            }
        }
    }
    
    /// Stop scanning for devices
    public func stopScan() {
        isScanning = false
        central.stopScan()
        scanTimer?.invalidate()
        scanTimer = nil
    }
    
    /**
     * Connect to a discovered device.
     *
     * - Parameter device: Device to connect to
     * - Parameter timeout: Connection timeout in seconds (default: 10)
     * - Parameter completion: Called on success or failure
     */
    public func connect(
        to device: W4RPDevice,
        timeout: TimeInterval = 10.0,
        completion: @escaping (Result<Void, W4RPError>) -> Void
    ) {
        guard !isConnected else {
            completion(.failure(W4RPError(.alreadyConnected, "Already connected")))
            return
        }
        
        stopScan()
        connectCompletion = completion
        peripheral = device.peripheral
        connectedDeviceName = device.name
        device.peripheral.delegate = self
        
        connectionTimer = Timer.scheduledTimer(withTimeInterval: timeout, repeats: false) { [weak self] _ in
            guard let self = self, !self.isConnected else { return }
            self.central.cancelPeripheralConnection(device.peripheral)
            self.connectCompletion?(.failure(W4RPError(.connectionTimeout, "Connection timed out")))
            self.connectCompletion = nil
        }
        
        central.connect(device.peripheral, options: nil)
    }
    
    /// Disconnect from current device
    public func disconnect(completion: @escaping (Result<Void, W4RPError>) -> Void) {
        guard let p = peripheral else {
            completion(.success(()))
            return
        }
        disconnectCompletion = completion
        central.cancelPeripheralConnection(p)
    }
    
    /**
     * Fetch the module profile.
     *
     * - Parameter completion: Called with profile or error
     */
    public func getProfile(completion: @escaping (Result<W4RPModuleProfile, W4RPError>) -> Void) {
        guard isConnected, let rxChar = rxChar, let p = peripheral else {
            completion(.failure(W4RPError(.notConnected, "Not connected")))
            return
        }
        
        startStream { result in
            switch result {
            case .success(let data):
                do {
                    let profile = try JSONDecoder().decode(W4RPModuleProfile.self, from: data)
                    completion(.success(profile))
                } catch {
                    completion(.failure(W4RPError(.invalidData, "Failed to decode profile: \(error)")))
                }
            case .failure(let error):
                completion(.failure(error))
            }
        }
        
        guard let cmdData = "GET:PROFILE".data(using: .utf8) else { return }
        p.writeValue(cmdData, for: rxChar, type: .withResponse)
    }
    
    /**
     * Send rules to the module.
     *
     * - Parameter json: JSON string containing the ruleset
     * - Parameter persistent: If true, rules are saved to NVS (survive reboot)
     * - Parameter completion: Called on success or failure
     */
    public func setRules(
        json: String,
        persistent: Bool,
        completion: @escaping (Result<Void, W4RPError>) -> Void
    ) {
        guard isConnected, let rxChar = rxChar, let p = peripheral else {
            completion(.failure(W4RPError(.notConnected, "Not connected")))
            return
        }
        
        guard let data = json.data(using: .utf8) else {
            completion(.failure(W4RPError(.invalidData, "Invalid JSON")))
            return
        }
        
        let crc = crc32(data)
        let mode = persistent ? "NVS" : "RAM"
        let header = "SET:RULES:\(mode):\(data.count):\(crc)"
        
        Task {
            do {
                try await write(header.data(using: .utf8)!, to: p, char: rxChar)
                try await sendChunked(data: data, to: p, char: rxChar)
                try await write("END".data(using: .utf8)!, to: p, char: rxChar)
                completion(.success(()))
            } catch let error as W4RPError {
                completion(.failure(error))
            } catch {
                completion(.failure(W4RPError(.writeFailed, "Write failed: \(error)")))
            }
        }
    }
    
    /**
     * Start a Delta OTA firmware update.
     *
     * - Parameter patchData: Binary patch data (Janpatch format)
     * - Parameter completion: Called on success or failure
     */
    public func startOTA(
        patchData: Data,
        completion: @escaping (Result<Void, W4RPError>) -> Void
    ) {
        guard isConnected, let rxChar = rxChar, let p = peripheral else {
            completion(.failure(W4RPError(.notConnected, "Not connected")))
            return
        }
        
        let crc = crc32(patchData)
        let cmd = "OTA:BEGIN:DELTA:\(patchData.count):\(String(format: "%X", crc))"
        
        Task {
            do {
                try await write(cmd.data(using: .utf8)!, to: p, char: rxChar)
                try await Task.sleep(nanoseconds: 200_000_000)
                try await sendChunked(data: patchData, to: p, char: rxChar)
                try await write("END".data(using: .utf8)!, to: p, char: rxChar)
                completion(.success(()))
            } catch let error as W4RPError {
                completion(.failure(error))
            } catch {
                completion(.failure(W4RPError(.writeFailed, "OTA failed: \(error)")))
            }
        }
    }
    
    // MARK: - Private Helpers
    
    private func write(_ data: Data, to peripheral: CBPeripheral, char: CBCharacteristic) async throws {
        peripheral.writeValue(data, for: char, type: .withResponse)
    }
    
    private func sendChunked(data: Data, to peripheral: CBPeripheral, char: CBCharacteristic, chunkSize: Int = 180) async throws {
        var offset = 0
        while offset < data.count {
            let end = min(offset + chunkSize, data.count)
            let chunk = data.subdata(in: offset..<end)
            peripheral.writeValue(chunk, for: char, type: .withResponse)
            try await Task.sleep(nanoseconds: 3_000_000)
            offset = end
        }
    }
    
    private func startStream(timeout: TimeInterval = 10.0, completion: @escaping (Result<Data, W4RPError>) -> Void) {
        streamActive = false
        streamBuffer = Data()
        streamExpectedLen = 0
        streamExpectedCRC = 0
        streamCompletion = completion
        
        streamTimeout = Timer.scheduledTimer(withTimeInterval: timeout, repeats: false) { [weak self] _ in
            self?.finishStream(error: W4RPError(.streamTimeout, "Stream timed out"))
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
        
        guard let completion = streamCompletion else { return }
        streamCompletion = nil
        streamActive = false
        
        if let error = error {
            completion(.failure(error))
            return
        }
        
        guard streamBuffer.count == streamExpectedLen else {
            completion(.failure(W4RPError(.lengthMismatch, 
                "Length mismatch: \(streamBuffer.count) != \(streamExpectedLen)")))
            return
        }
        
        let crc = crc32(streamBuffer)
        guard crc == streamExpectedCRC else {
            completion(.failure(W4RPError(.crcMismatch, "CRC mismatch")))
            return
        }
        
        completion(.success(streamBuffer))
    }
    
    /// Calculate CRC32 checksum (IEEE 802.3 polynomial)
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
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        // State changes are handled in startScan
    }
    
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
        peripheral.discoverServices([uuids.service])
    }
    
    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectionTimer?.invalidate()
        connectCompletion?(.failure(W4RPError(.connectionFailed, error?.localizedDescription ?? "Connection failed")))
        connectCompletion = nil
    }
    
    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        isConnected = false
        rxChar = nil
        txChar = nil
        statusChar = nil
        self.peripheral = nil
        
        disconnectCompletion?(.success(()))
        disconnectCompletion = nil
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
            isConnected = true
            connectCompletion?(.success(()))
            connectCompletion = nil
        } else {
            connectCompletion?(.failure(W4RPError(.characteristicNotFound, "Missing characteristics")))
            connectCompletion = nil
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
