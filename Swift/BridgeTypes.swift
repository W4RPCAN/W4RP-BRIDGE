/**
 * W4RP Bridge - Type Definitions
 *
 * Centralized types, enums, and error definitions for the W4RP BLE bridge.
 * All public types are pure data transfer objects (DTOs) - truly Sendable.
 *
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 2.0.0
 */

import Foundation

// MARK: - UUIDs

/**
 * W4RP BLE Service and Characteristic UUIDs.
 * Stores raw UUID strings - CBUUIDs created on demand.
 * The UUID structure `5734-5250` encodes "W4RP" in ASCII hex.
 */
public struct W4RPUUIDs: Sendable, Codable {
    public let service: String
    public let rx: String
    public let tx: String
    public let status: String
    
    public static let `default` = W4RPUUIDs(
        service: "0000fff0-5734-5250-5734-525000000000",
        rx:      "0000fff1-5734-5250-5734-525000000000",
        tx:      "0000fff2-5734-5250-5734-525000000000",
        status:  "0000fff3-5734-5250-5734-525000000000"
    )
}

// MARK: - Error Codes

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
public enum W4RPErrorCode: Int, Sendable, Codable {
    case connectionFailed       = 1000
    case connectionLost         = 1001
    case notConnected           = 1002
    case alreadyConnected       = 1003
    case deviceNotFound         = 1100
    case serviceNotFound        = 1101
    case characteristicNotFound = 1102
    case scanCancelled          = 1103
    case bluetoothOff           = 1200
    case bluetoothUnauthorized  = 1201
    case bluetoothUnsupported   = 1202
    case invalidResponse        = 2000
    case writeFailed            = 2001
    case readFailed             = 2002
    case invalidData            = 3000
    case crcMismatch            = 3001
    case lengthMismatch         = 3002
    case scanTimeout            = 4000
    case connectionTimeout      = 4001
    case profileTimeout         = 4002
    case streamTimeout          = 4003
    case otaCancelled           = 5000
}

/** W4RP operation error. */
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

// MARK: - Connection State

/**
 * BLE connection state machine.
 *
 * State Transitions:
 * - DISCONNECTED → SCANNING → DISCONNECTED
 * - DISCONNECTED → CONNECTING → DISCOVERING → READY
 * - READY → DISCONNECTING → DISCONNECTED
 * - ANY → ERROR → DISCONNECTED
 */
public enum W4RPConnectionState: String, Sendable, Codable {
    case disconnected = "DISCONNECTED"
    case scanning = "SCANNING"
    case connecting = "CONNECTING"
    case discoveringServices = "DISCOVERING_SERVICES"
    case ready = "READY"
    case disconnecting = "DISCONNECTING"
    case error = "ERROR"
    
    /** True if connection is fully established and ready. */
    public var isReady: Bool { self == .ready }
    
    /** True if any async operation is in progress. */
    public var isBusy: Bool {
        switch self {
        case .scanning, .connecting, .discoveringServices, .disconnecting: return true
        default: return false
        }
    }
}

// MARK: - Bluetooth Status

/**
 * Unified Bluetooth adapter availability status.
 *
 * Abstracts platform-specific Bluetooth state detection into a consistent enum
 * that matches the TypeScript/Kotlin implementations.
 *
 * Platform Mapping (iOS):
 * - `.poweredOn` → `.ready`
 * - `.poweredOff` → `.poweredOff`
 * - `.unauthorized` → `.unauthorized`
 * - `.unsupported` → `.unsupported`
 * - `.resetting` → `.unavailable`
 * - `.unknown` → `.unknown`
 */
public enum W4RPBluetoothStatus: String, Sendable, Codable {
    /// Bluetooth is powered on and ready to use
    case ready = "READY"
    /// Bluetooth adapter is powered off
    case poweredOff = "POWERED_OFF"
    /// Application lacks Bluetooth permissions
    case unauthorized = "UNAUTHORIZED"
    /// Device does not support Bluetooth
    case unsupported = "UNSUPPORTED"
    /// Bluetooth temporarily unavailable (resetting)
    case unavailable = "UNAVAILABLE"
    /// Status cannot be determined yet
    case unknown = "UNKNOWN"
}

// MARK: - Device

/**
 * Discovered W4RP BLE device.
 * Pure data transfer object - no CoreBluetooth references.
 * CBPeripheral is stored separately in W4RPBridge.peripheralMap.
 */
public struct W4RPDevice: Identifiable, Sendable, Codable, Hashable {
    public let id: UUID
    public let name: String
    public var rssi: Int
    
    public init(id: UUID, name: String, rssi: Int) {
        self.id = id
        self.name = name
        self.rssi = rssi
    }
    
    /** Convert to JSON-safe dictionary for JS bridge. */
    public func toDict() -> [String: Any] {
        ["id": id.uuidString, "name": name, "rssi": rssi]
    }
    
    public func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
    
    public static func == (lhs: W4RPDevice, rhs: W4RPDevice) -> Bool {
        lhs.id == rhs.id
    }
}

// MARK: - WBP Protocol Constants

/// WBP Protocol Magic Numbers.
/// Used to identify profile vs rules binary streams.
public enum WBPMagic: UInt32, Sendable {
    /// Profile response magic (GET:PROFILE)
    case profile = 0xC0DE5701
    /// Rules binary magic (SET:RULES / GET:RULES)
    case rules = 0xC0DE5702
}

/// Current WBP protocol version
public let WBP_VERSION: UInt8 = 2

/// WBP Header Size in bytes
public let WBP_PROFILE_HEADER_SIZE = 32

// MARK: - WBP Binary Structs

/**
 * WBP Profile Header (32 bytes, packed).
 * Matches C++ WBPProfileHeader exactly.
 *
 * Layout:
 * - 0:  magic (u32) - 0xC0DE5701
 * - 4:  version (u8)
 * - 5:  flags (u8)
 * - 6:  moduleIdStrIdx (u16)
 * - 8:  hwStrIdx (u16)
 * - 10: fwStrIdx (u16)
 * - 12: serialStrIdx (u16)
 * - 14: capabilityCount (u8)
 * - 15: rulesMode (u8)
 * - 16: rulesCRC (u32)
 * - 20: signalCount (u8)
 * - 21: conditionCount (u8)
 * - 22: actionCount (u8)
 * - 23: ruleCount (u8)
 * - 24: uptimeMs (u32)
 * - 28: bootCount (u16)
 * - 30: stringTableOffset (u16)
 */
public struct WBPProfileHeader: Sendable {
    public let magic: UInt32
    public let version: UInt8
    public let flags: UInt8
    public let moduleIdStrIdx: UInt16
    public let hwStrIdx: UInt16
    public let fwStrIdx: UInt16
    public let serialStrIdx: UInt16
    public let capabilityCount: UInt8
    public let rulesMode: UInt8
    public let rulesCRC: UInt32
    public let signalCount: UInt8
    public let conditionCount: UInt8
    public let actionCount: UInt8
    public let ruleCount: UInt8
    public let uptimeMs: UInt32
    public let bootCount: UInt16
    public let stringTableOffset: UInt16
    
    public static let SIZE = 32
}

/**
 * WBP Capability Entry (12 bytes, packed).
 * Describes a registered capability on the module.
 *
 * Layout:
 * - 0: idStrIdx (u16)
 * - 2: labelStrIdx (u16)
 * - 4: descStrIdx (u16)
 * - 6: categoryStrIdx (u16)
 * - 8: paramCount (u8)
 * - 9: paramStartIdx (u8)
 * - 10: reserved (u16)
 */
public struct WBPCapability: Sendable {
    public let idStrIdx: UInt16
    public let labelStrIdx: UInt16
    public let descStrIdx: UInt16
    public let categoryStrIdx: UInt16
    public let paramCount: UInt8
    public let paramStartIdx: UInt8
    public let reserved: UInt16
    
    public static let SIZE = 12
}

/**
 * WBP Capability Param (12 bytes, packed).
 * Describes a parameter for a capability.
 *
 * Layout:
 * - 0: nameStrIdx (u16)
 * - 2: descStrIdx (u16)
 * - 4: type (u8) - WBPParamType
 * - 5: required (u8)
 * - 6: reserved (u16)
 * - 8: min (i16)
 * - 10: max (i16)
 */
public struct WBPCapParam: Sendable {
    public let nameStrIdx: UInt16
    public let descStrIdx: UInt16
    public let type: UInt8
    public let required: UInt8
    public let reserved: UInt16
    public let min: Int16
    public let max: Int16
    
    public static let SIZE = 12
}

/**
 * WBP Param type enum.
 * Matches C++ ParamType enum.
 */
public enum WBPParamType: UInt8, Sendable, Codable, CaseIterable {
    case int = 0
    case float = 1
    case string = 2
    case bool = 3
    
    /// String representation for JSON/UI
    public var stringValue: String {
        switch self {
        case .int: return "int"
        case .float: return "float"
        case .string: return "string"
        case .bool: return "bool"
        }
    }
}

// MARK: - Module Profile (WBP v2)

/**
 * Parsed module profile from WBP binary.
 * Contains hardware/firmware info and registered capabilities.
 */
public struct W4RPModuleProfile: Codable, Sendable {
    /// Module identifier (e.g., "W4RP-ABC123")
    public let moduleId: String
    /// Hardware version
    public let hwVersion: String
    /// Firmware version
    public let fwVersion: String
    /// Serial number
    public let serial: String
    /// Uptime in milliseconds
    public let uptimeMs: UInt32
    /// Boot count
    public let bootCount: UInt16
    /// Rules mode (0=none, 1=RAM, 2=NVS)
    public let rulesMode: UInt8
    /// Active rules CRC32
    public let rulesCRC: UInt32
    /// Active signal count
    public let signalCount: UInt8
    /// Active condition count
    public let conditionCount: UInt8
    /// Active action count
    public let actionCount: UInt8
    /// Active rule count
    public let ruleCount: UInt8
    /// Registered capabilities
    public let capabilities: [W4RPCapability]
    
    /// Rules mode as human-readable string
    public var rulesModeString: String {
        switch rulesMode {
        case 0: return "Empty"
        case 1: return "RAM"
        case 2: return "NVS"
        default: return "Unknown"
        }
    }
    
    /// Uptime formatted as seconds
    public var uptimeSeconds: Double {
        Double(uptimeMs) / 1000.0
    }
    
    /// Parsed capability with params
    public struct W4RPCapability: Codable, Sendable, Identifiable {
        public let id: String
        public let label: String
        public let description: String
        public let category: String
        public let params: [W4RPCapabilityParam]
    }
    
    /// Parsed capability param
    public struct W4RPCapabilityParam: Codable, Sendable {
        public let name: String
        public let description: String
        public let type: String
        public let required: Bool
        public let min: Int16
        public let max: Int16
    }
}

// MARK: - Debug Data

/** Debug stream data from module. */
public struct W4RPDebugData: Sendable, Codable {
    public let id: String
    public let type: DebugType
    public let value: Float?
    public let active: Bool?
    
    public enum DebugType: String, Sendable, Codable {
        case signal
        case node
    }
}

// MARK: - Configuration

/** Exponential backoff retry configuration. */
public struct W4RPRetryConfig: Sendable {
    public let maxRetries: Int
    public let baseDelay: TimeInterval
    public let maxDelay: TimeInterval
    public let multiplier: Double
    
    public static let `default` = W4RPRetryConfig()
    
    public init(maxRetries: Int = 3, baseDelay: TimeInterval = 1.0, maxDelay: TimeInterval = 16.0, multiplier: Double = 2.0) {
        self.maxRetries = maxRetries
        self.baseDelay = baseDelay
        self.maxDelay = maxDelay
        self.multiplier = multiplier
    }
    
    /** Calculate delay for attempt (0-indexed). */
    public func delay(forAttempt attempt: Int) -> TimeInterval {
        min(baseDelay * pow(multiplier, Double(attempt)), maxDelay)
    }
}

/** Automatic reconnection configuration. */
public struct W4RPAutoReconnectConfig: Sendable {
    public let enabled: Bool
    public let retryConfig: W4RPRetryConfig
    public let maxLifetimeReconnects: Int
    
    public static let disabled = W4RPAutoReconnectConfig(enabled: false)
    public static let `default` = W4RPAutoReconnectConfig(enabled: true, retryConfig: W4RPRetryConfig(maxRetries: 5, baseDelay: 2.0))
    
    public init(enabled: Bool = false, retryConfig: W4RPRetryConfig = .default, maxLifetimeReconnects: Int = 0) {
        self.enabled = enabled
        self.retryConfig = retryConfig
        self.maxLifetimeReconnects = maxLifetimeReconnects
    }
}

// MARK: - AnyCodable

/** Type-erased wrapper for arbitrary JSON values. */
public struct AnyCodable: Codable, Sendable {
    public let value: AnyCodableValue
    
    public init(_ value: Any) {
        self.value = AnyCodableValue.from(value)
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        self.value = try container.decode(AnyCodableValue.self)
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(value)
    }
}

/** Sendable enum representing any JSON value. */
public indirect enum AnyCodableValue: Codable, Sendable {
    case null
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([AnyCodableValue])
    case object([String: AnyCodableValue])
    
    public static func from(_ value: Any) -> AnyCodableValue {
        switch value {
        case is NSNull: return .null
        case let v as Bool: return .bool(v)
        case let v as Int: return .int(v)
        case let v as Double: return .double(v)
        case let v as String: return .string(v)
        case let v as [Any]: return .array(v.map { from($0) })
        case let v as [String: Any]: return .object(v.mapValues { from($0) })
        default: return .null
        }
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let v = try? container.decode(Bool.self) {
            self = .bool(v)
        } else if let v = try? container.decode(Int.self) {
            self = .int(v)
        } else if let v = try? container.decode(Double.self) {
            self = .double(v)
        } else if let v = try? container.decode(String.self) {
            self = .string(v)
        } else if let v = try? container.decode([AnyCodableValue].self) {
            self = .array(v)
        } else if let v = try? container.decode([String: AnyCodableValue].self) {
            self = .object(v)
        } else {
            self = .null
        }
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let v): try container.encode(v)
        case .int(let v): try container.encode(v)
        case .double(let v): try container.encode(v)
        case .string(let v): try container.encode(v)
        case .array(let v): try container.encode(v)
        case .object(let v): try container.encode(v)
        }
    }
}
