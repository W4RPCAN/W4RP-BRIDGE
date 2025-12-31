/**
 * W4RP Bridge - Coordinator
 *
 * Handles WKWebView message routing and coordinates between
 * JavaScript bridge and native BLE operations.
 *
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 2.0.0
 */

import Foundation
import WebKit

// MARK: - W4RPBridgeCoordinator

/**
 * Coordinates communication between WKWebView and W4RPBridge.
 *
 * Responsibilities:
 * - Receives JavaScript messages via WKScriptMessageHandler
 * - Routes method calls to W4RPBridge
 * - Sends responses back to JavaScript
 * - Synchronizes state between native and JS
 */
@MainActor
public final class W4RPBridgeCoordinator: NSObject, WKScriptMessageHandler {
    
    // MARK: - Properties
    
    public let bridge: W4RPBridge
    public weak var webView: WKWebView? {
        didSet {
            if webView != nil {
                pushCurrentState()
            }
        }
    }
    
    /** Optional callback for QR code scanning. */
    public var onScanQRCode: ((@escaping (String?) -> Void) -> Void)?
    
    // MARK: - Init
    
    public init(bridge: W4RPBridge) {
        self.bridge = bridge
        super.init()
        print("🔵 [Coordinator] Init - bridge.bluetoothStatus = \(bridge.bluetoothStatus.rawValue)")
        configureBridgeCallbacks()
    }
    
    // MARK: - WKScriptMessageHandler
    
    public func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == W4RPBridge.messageHandlerName,
              let body = message.body as? [String: Any] else { return }
        
        let id = body["id"] as? Int ?? 0
        let method = body["method"] as? String ?? ""
        let params = body["params"] as? [String: Any] ?? [:]
        
        handleMethod(id: id, method: method, params: params)
    }
    
    // MARK: - Method Routing
    
    private func handleMethod(id: Int, method: String, params: [String: Any]) {
        switch method {
        case "scan":
            handleScan(id: id, params: params)
        case "stopScan":
            bridge.stopScan()
            sendSuccess(id: id)
        case "connect":
            handleConnect(id: id, params: params)
        case "disconnect":
            handleDisconnect(id: id)
        case "getProfile":
            handleGetProfile(id: id)
        case "getRules":
            handleGetRules(id: id)
        case "setRules":
            handleSetRules(id: id, params: params)
        case "setDebugMode":
            handleSetDebugMode(id: id, params: params)
        case "watchDebugSignals":
            handleWatchDebugSignals(id: id, params: params)
        case "startOTA":
            handleStartOTA(id: id, params: params)
        case "stopOTA":
            handleStopOTA(id: id)
        case "scanQRCode":
            handleScanQRCode(id: id)
        case "appReady":
            handleAppReady(id: id)
        default:
            sendError(id: id, code: 9999, message: "Unknown method: \(method)")
        }
    }
    
    // MARK: - Handlers
    
    private func handleScan(id: Int, params: [String: Any]) {
        let timeoutMs = params["timeoutMs"] as? Int ?? 8000
        let timeout = TimeInterval(timeoutMs) / 1000.0
        
        // State will be updated via bridge.onConnectionStateChange callback
        
        Task {
            do {
                let devices = try await bridge.scan(timeout: timeout)
                // Do NOT manually update state - let BLE callbacks handle it
                sendSuccess(id: id, result: devices.map { $0.toDict() })
            } catch let error as W4RPError {
                sendError(id: id, code: error.code.rawValue, message: error.message)
            } catch {
                sendError(id: id, code: 1000, message: "\(error)")
            }
        }
    }
    
    private func handleConnect(id: Int, params: [String: Any]) {
        guard let deviceId = params["deviceId"] as? String,
              let uuid = UUID(uuidString: deviceId),
              let device = bridge.discoveredDevices.first(where: { $0.id == uuid }) else {
            sendError(id: id, code: W4RPErrorCode.deviceNotFound.rawValue, message: "Device not found")
            return
        }
        
        // State will be updated via bridge.onConnectionStateChange callback
        
        Task {
            do {
                try await bridge.connect(to: device)
                // Do NOT manually update state - let BLE callbacks handle it
                updateConnectedDevice(device)
                sendSuccess(id: id)
            } catch let error as W4RPError {
                // Do NOT manually update state - let BLE callbacks handle it
                sendError(id: id, code: error.code.rawValue, message: error.message)
            } catch {
                sendError(id: id, code: 1000, message: "\(error)")
            }
        }
    }
    
    private func handleDisconnect(id: Int) {
        Task {
            do {
                try await bridge.disconnect()
                // Do NOT manually update state - let BLE callbacks handle it
                updateConnectedDevice(nil)
                sendSuccess(id: id)
            } catch let error as W4RPError {
                sendError(id: id, code: error.code.rawValue, message: error.message)
            } catch {
                sendError(id: id, code: 1000, message: "\(error)")
            }
        }
    }
    
    private func handleGetProfile(id: Int) {
        Task {
            do {
                // Get raw binary from bridge
                let data = try await bridge.getProfile()
                
                // Send as base64 - UI will parse
                let base64 = data.base64EncodedString()
                sendSuccess(id: id, result: base64)
            } catch let error as W4RPError {
                sendError(id: id, code: error.code.rawValue, message: error.message)
            } catch {
                sendError(id: id, code: 3000, message: "\(error)")
            }
        }
    }
    
    private func handleGetRules(id: Int) {
        Task {
            do {
                // Get raw binary from bridge
                let data = try await bridge.getRules()
                
                // Send as base64 - UI will parse
                let base64 = data.base64EncodedString()
                sendSuccess(id: id, result: base64)
            } catch let error as W4RPError {
                sendError(id: id, code: error.code.rawValue, message: error.message)
            } catch {
                sendError(id: id, code: 3000, message: "\(error)")
            }
        }
    }
    
    private func handleSetRules(id: Int, params: [String: Any]) {
        print("🔵 [Coordinator] handleSetRules params keys: \(params.keys)")
        
        // WBP v2: Expect base64-encoded binary
        if let base64 = params["binary"] as? String,
           let binary = Data(base64Encoded: base64) {
            print("🔵 [Coordinator] handleSetRules binary length: \(binary.count)")
            let persistent = params["persistent"] as? Bool ?? true
            
            Task {
                do {
                    try await bridge.setRules(binary: binary, persistent: persistent) { sent, total in
                        self.evaluateJS("window.W4RPBridge._notifyProgress(\(sent), \(total));")
                    }
                    sendSuccess(id: id)
                } catch let error as W4RPError {
                    sendError(id: id, code: error.code.rawValue, message: error.message)
                } catch {
                    sendError(id: id, code: 2000, message: "\(error)")
                }
            }
            return
        }
        
        print("🔴 [Coordinator] handleSetRules: missing 'binary' param (base64). Params: \(params)")
        sendError(id: id, code: W4RPErrorCode.invalidData.rawValue, message: "Missing binary parameter (base64)")
    }
    
    private func handleSetDebugMode(id: Int, params: [String: Any]) {
        let enabled = params["enabled"] as? Bool ?? false
        
        Task {
            do {
                try await bridge.setDebugMode(enabled: enabled)
                sendSuccess(id: id)
            } catch let error as W4RPError {
                sendError(id: id, code: error.code.rawValue, message: error.message)
            } catch {
                sendError(id: id, code: 2000, message: "\(error)")
            }
        }
    }
    
    private func handleWatchDebugSignals(id: Int, params: [String: Any]) {
        guard let signals = params["signals"] as? [[String: Any]] else {
            sendError(id: id, code: W4RPErrorCode.invalidData.rawValue, message: "Missing or invalid signals parameter")
            return
        }
        
        Task {
            do {
                try await bridge.watchDebugSignals(signals)
                sendSuccess(id: id)
            } catch let error as W4RPError {
                sendError(id: id, code: error.code.rawValue, message: error.message)
            } catch {
                sendError(id: id, code: 2000, message: "\(error)")
            }
        }
    }
    
    private func handleStopOTA(id: Int) {
        bridge.stopOTA()
        sendSuccess(id: id)
    }
    
    private func handleStartOTA(id: Int, params: [String: Any]) {
        guard let patchBase64 = params["patch"] as? String,
              let patchData = Data(base64Encoded: patchBase64) else {
            sendError(id: id, code: W4RPErrorCode.invalidData.rawValue, message: "Invalid patch data")
            return
        }
        
        Task {
            do {
                try await bridge.startOTA(patchData: patchData)
                sendSuccess(id: id)
            } catch let error as W4RPError {
                sendError(id: id, code: error.code.rawValue, message: error.message)
            } catch {
                sendError(id: id, code: 2000, message: "\(error)")
            }
        }
    }
    
    private func handleScanQRCode(id: Int) {
        guard let handler = onScanQRCode else {
            sendError(id: id, code: 9999, message: "QR scanning not available")
            return
        }
        
        handler { [weak self] code in
            if let code = code {
                self?.sendSuccess(id: id, result: code)
            } else {
                self?.sendError(id: id, code: 1103, message: "QR scan cancelled")
            }
        }
    }
    
    private func handleAppReady(id: Int) {
        pushCurrentState()
        sendSuccess(id: id)
    }
    
    // MARK: - Bridge Callbacks
    
    private func configureBridgeCallbacks() {
        bridge.onConnectionStateChanged = { [weak self] state in
            self?.updateConnectionState(state)
        }
        
        bridge.onDeviceDiscovered = { [weak self] devices in
            self?.notifyDevicesDiscovered(devices)
        }
        
        bridge.onStatusUpdate = { [weak self] json in
            self?.evaluateJS("window.W4RPBridge._notifyStatus('\(json.replacingOccurrences(of: "'", with: "\\'"))');")
        }
        
        bridge.onDebugData = { [weak self] data in
            guard let json = try? JSONEncoder().encode(data),
                  let jsonStr = String(data: json, encoding: .utf8) else { return }
            self?.evaluateJS("window.W4RPBridge._notifyDebug(\(jsonStr));")
        }
        
        bridge.onReconnecting = { [weak self] attempt in
            self?.evaluateJS("window.W4RPBridge._notifyReconnecting(\(attempt));")
        }
        
        bridge.onReconnected = { [weak self] in
            self?.evaluateJS("window.W4RPBridge._notifyReconnected();")
        }
        
        bridge.onReconnectFailed = { [weak self] error in
            self?.evaluateJS("window.W4RPBridge._notifyReconnectFailed({code:\(error.code.rawValue),message:'\(error.message)'});")
        }
        
        bridge.onBluetoothStatusChanged = { [weak self] status in
            print("🔵 [Coordinator] onBluetoothStatusChanged callback: \(status.rawValue)")
            self?.evaluateJS("window.W4RPBridge._setBluetoothStatus('\(status.rawValue)');")
        }
    }
    
    // MARK: - Response Helpers
    
    private func sendSuccess(id: Int, result: Any? = nil) {
        var response: [String: Any] = ["id": id, "success": true]
        if let result = result {
            response["result"] = result
        }
        
        if let data = try? JSONSerialization.data(withJSONObject: response),
           let json = String(data: data, encoding: .utf8) {
            evaluateJS("window.W4RPBridge._resolve(\(id), \(json).result);")
        }
    }
    
    private func sendError(id: Int, code: Int, message: String) {
        evaluateJS("window.W4RPBridge._reject(\(id), {code:\(code),message:'\(message.replacingOccurrences(of: "'", with: "\\'"))'});")
    }
    
    // MARK: - State Sync
    
    private func updateConnectionState(_ state: W4RPConnectionState) {
        evaluateJS("window.W4RPBridge._setConnectionState('\(state.rawValue)');")
    }
    
    private func updateConnectedDevice(_ device: W4RPDevice?) {
        if let device = device {
            print("🔵 [Coordinator] updateConnectedDevice: \(device.toDict())")
            if let data = try? JSONSerialization.data(withJSONObject: device.toDict()),
               let json = String(data: data, encoding: .utf8) {
                print("🔵 [Coordinator] Sending connectedDevice JSON: \(json)")
                evaluateJS("window.W4RPBridge._setConnectedDevice(\(json));")
            }
        } else {
            evaluateJS("window.W4RPBridge._setConnectedDevice(null);")
        }
    }
    
    private func notifyDevicesDiscovered(_ devices: [W4RPDevice]) {
        let list = devices.map { $0.toDict() }
        print("🔵 [Coordinator] notifyDevicesDiscovered: \(list)")
        if let data = try? JSONSerialization.data(withJSONObject: list),
           let json = String(data: data, encoding: .utf8) {
            print("🔵 [Coordinator] Sending devices JSON: \(json)")
            evaluateJS("window.W4RPBridge._notifyDeviceDiscovered(\(json));")
        }
    }
    
    private func pushCurrentState() {
        print("🔵 [Coordinator] pushCurrentState called - bluetoothStatus = \(bridge.bluetoothStatus.rawValue)")
        updateConnectionState(bridge.connectionState)
        if let device = bridge.connectedDevice {
            updateConnectedDevice(device)
        }
        pushBluetoothStatusInternal()
    }
    
    public func pushBluetoothStatus() {
        print("🔵 [Coordinator] pushBluetoothStatus (public) called - bluetoothStatus = \(bridge.bluetoothStatus.rawValue)")
        pushBluetoothStatusInternal()
    }
    
    private func pushBluetoothStatusInternal() {
        let js = "window.W4RPBridge._setBluetoothStatus('\(bridge.bluetoothStatus.rawValue)');"
        print("🔵 [Coordinator] Evaluating: \(js)")
        evaluateJS(js)
    }
    
    private func evaluateJS(_ script: String) {
        webView?.evaluateJavaScript(script, completionHandler: nil)
    }
}

public protocol EquatableBytes: Equatable {
    init(bytes: [UInt8])
    var bytes: [UInt8] { get }
}
