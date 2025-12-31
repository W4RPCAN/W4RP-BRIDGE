/**
 * W4RP Bridge - Coordinator
 *
 * Handles WebView message routing and coordinates between
 * JavaScript bridge and native BLE operations.
 *
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 2.0.0
 */

package com.w4rp.bridge

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Coordinates communication between Android WebView and W4RPBridge.
 *
 * Responsibilities:
 * - Receives JavaScript messages via @JavascriptInterface
 * - Routes method calls to W4RPBridge
 * - Sends responses back to JavaScript
 * - Synchronizes state between native and JS
 *
 * Usage:
 * ```kotlin
 * val bridge = W4RPBridge(context)
 * val coordinator = W4RPCoordinator(bridge)
 * webView.addJavascriptInterface(coordinator, W4RPInjection.INTERFACE_NAME)
 * webView.evaluateJavascript(W4RPInjection.jsBridge, null)
 * ```
class W4RPBridgeCoordinator(private val bridge: W4RPBridge) {
    
    private var webView: WebView? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    /** Optional callback for QR code scanning. */
    var onScanQRCode: ((callback: (String?) -> Unit) -> Unit)? = null
    
    /**
     * Attach WebView and configure bridge callbacks.
     */
    fun attach(webView: WebView) {
        this.webView = webView
        configureBridgeCallbacks()
        pushCurrentState()
    }
    
    // =========================================================================
    // JavaScript Interface
    // =========================================================================
    
    /**
     * Called from JavaScript via W4RPBridgeNative.postMessage(json).
     */
    @JavascriptInterface
    fun postMessage(messageJson: String) {
        try {
            val json = JSONObject(messageJson)
            val id = json.optInt("id", 0)
            val method = json.optString("method", "")
            val params = json.optJSONObject("params") ?: JSONObject()
            
            // Route on main thread
            scope.launch {
                handleMethod(id, method, params)
            }
        } catch (e: Exception) {
            android.util.Log.e("W4RP", "Failed to parse message: $messageJson", e)
        }
    }
    
    // =========================================================================
    // Method Routing
    // =========================================================================
    
    private suspend fun handleMethod(id: Int, method: String, params: JSONObject) {
        when (method) {
            "scan" -> handleScan(id, params)
            "stopScan" -> {
                bridge.stopScan()
                sendSuccess(id)
            }
            "connect" -> handleConnect(id, params)
            "disconnect" -> handleDisconnect(id)
            "getProfile" -> handleGetProfile(id)
            "getRules" -> handleGetRules(id)
            "setRules" -> handleSetRules(id, params)
            "setDebugMode" -> handleSetDebugMode(id, params)
            "watchDebugSignals" -> handleWatchDebugSignals(id, params)
            "startOTA" -> handleStartOTA(id, params)
            "stopOTA" -> handleStopOTA(id)
            "scanQRCode" -> handleScanQRCode(id)
            "appReady" -> handleAppReady(id)
            else -> sendError(id, 9999, "Unknown method: $method")
        }
    }
    
    // =========================================================================
    // Handlers
    // =========================================================================
    
    private suspend fun handleScan(id: Int, params: JSONObject) {
        val timeoutMs = params.optLong("timeoutMs", 8000)
        
        try {
            val devices = bridge.scan(timeoutMs)
            val devicesArray = JSONArray()
            devices.forEach { device ->
                devicesArray.put(JSONObject(device.toMap()))
            }
            sendSuccess(id, devicesArray)
        } catch (e: W4RPError) {
            sendError(id, e.code.code, e.message ?: "Scan failed")
        } catch (e: Exception) {
            sendError(id, W4RPErrorCode.CONNECTION_FAILED.code, e.message ?: "Unknown error")
        }
    }
    
    private suspend fun handleConnect(id: Int, params: JSONObject) {
        val deviceId = params.optString("deviceId", "")
        val device = bridge.discoveredDevices.find { it.device.address == deviceId }
        
        if (device == null) {
            sendError(id, W4RPErrorCode.DEVICE_NOT_FOUND.code, "Device not found")
            return
        }
        
        try {
            bridge.connect(device)
            updateConnectedDevice(device)
            sendSuccess(id)
        } catch (e: W4RPError) {
            sendError(id, e.code.code, e.message ?: "Connection failed")
        } catch (e: Exception) {
            sendError(id, W4RPErrorCode.CONNECTION_FAILED.code, e.message ?: "Unknown error")
        }
    }
    
    private suspend fun handleDisconnect(id: Int) {
        try {
            bridge.disconnect()
            updateConnectedDevice(null)
            sendSuccess(id)
        } catch (e: W4RPError) {
            sendError(id, e.code.code, e.message ?: "Disconnect failed")
        } catch (e: Exception) {
            sendError(id, W4RPErrorCode.CONNECTION_FAILED.code, e.message ?: "Unknown error")
        }
    }
    
    private suspend fun handleGetProfile(id: Int) {
        try {
            // Get raw binary from bridge
            val data = bridge.getProfile()
            
            // Send as base64 - UI will parse
            val base64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
            sendSuccess(id, base64)
        } catch (e: W4RPError) {
            sendError(id, e.code.code, e.message ?: "getProfile failed")
        } catch (e: Exception) {
            sendError(id, W4RPErrorCode.INVALID_DATA.code, e.message ?: "Unknown error")
        }
    }
    
    private suspend fun handleGetRules(id: Int) {
        try {
            // Get raw binary from bridge
            val data = bridge.getRules()
            
            // Send as base64 - UI will parse
            val base64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
            sendSuccess(id, base64)
        } catch (e: W4RPError) {
            sendError(id, e.code.code, e.message ?: "getRules failed")
        } catch (e: Exception) {
            sendError(id, W4RPErrorCode.INVALID_DATA.code, e.message ?: "Unknown error")
        }
    }
    
    private suspend fun handleSetRules(id: Int, params: JSONObject) {
        // WBP v2: Expect base64-encoded binary
        val base64 = params.optString("binary", "")
        
        if (base64.isNotEmpty()) {
            try {
                val binary = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val persistent = params.optBoolean("persistent", true)
                
                bridge.setRules(binary, persistent) { sent, total ->
                    scope.launch {
                        evaluateJS("window.W4RPBridge._notifyProgress($sent, $total);")
                    }
                }
                sendSuccess(id)
            } catch (e: W4RPError) {
                sendError(id, e.code.code, e.message ?: "setRules failed")
            } catch (e: Exception) {
                sendError(id, W4RPErrorCode.WRITE_FAILED.code, e.message ?: "Unknown error")
            }
            return
        }
        
        sendError(id, W4RPErrorCode.INVALID_DATA.code, "Missing binary parameter (base64)")
    }
    
    private suspend fun handleSetDebugMode(id: Int, params: JSONObject) {
        val enabled = params.optBoolean("enabled", false)
        
        try {
            bridge.setDebugMode(enabled)
            sendSuccess(id)
        } catch (e: W4RPError) {
            sendError(id, e.code.code, e.message ?: "setDebugMode failed")
        } catch (e: Exception) {
            sendError(id, W4RPErrorCode.WRITE_FAILED.code, e.message ?: "Unknown error")
        }
    }
    
    private suspend fun handleWatchDebugSignals(id: Int, params: JSONObject) {
        val signalsArray = params.optJSONArray("signals")
        
        if (signalsArray == null) {
            sendError(id, W4RPErrorCode.INVALID_DATA.code, "Missing signals parameter")
            return
        }
        
        // Parse as array of signal objects (not strings)
        val signals = (0 until signalsArray.length()).map { i ->
            val obj = signalsArray.getJSONObject(i)
            obj.keys().asSequence().associateWith { key -> obj.get(key) as Any }
        }
        
        try {
            bridge.watchDebugSignals(signals)
            sendSuccess(id)
        } catch (e: W4RPError) {
            sendError(id, e.code.code, e.message ?: "watchDebugSignals failed")
        } catch (e: Exception) {
            sendError(id, W4RPErrorCode.WRITE_FAILED.code, e.message ?: "Unknown error")
        }
    }
    
    private fun handleStopOTA(id: Int) {
        bridge.stopOTA()
        sendSuccess(id)
    }
    
    private suspend fun handleStartOTA(id: Int, params: JSONObject) {
        val patchBase64 = params.optString("patch", "")
        
        if (patchBase64.isEmpty()) {
            sendError(id, W4RPErrorCode.INVALID_DATA.code, "Invalid patch data")
            return
        }
        
        try {
            val patchData = android.util.Base64.decode(patchBase64, android.util.Base64.DEFAULT)
            bridge.startOTA(patchData)
            sendSuccess(id)
        } catch (e: W4RPError) {
            sendError(id, e.code.code, e.message ?: "OTA failed")
        } catch (e: Exception) {
            sendError(id, W4RPErrorCode.INVALID_DATA.code, e.message ?: "Unknown error")
        }
    }
    
    private fun handleScanQRCode(id: Int) {
        val handler = onScanQRCode
        if (handler == null) {
            sendError(id, 9999, "QR scanning not available")
            return
        }
        
        handler { code ->
            if (code != null) {
                sendSuccess(id, code)
            } else {
                sendError(id, 1103, "QR scan cancelled")
            }
        }
    }
    
    private fun handleAppReady(id: Int) {
        pushCurrentState()
        sendSuccess(id)
    }
    
    // =========================================================================
    // Bridge Callbacks
    // =========================================================================
    
    private fun configureBridgeCallbacks() {
        bridge.onConnectionStateChanged = { state ->
            updateConnectionState(state)
        }
        
        bridge.onBluetoothStatusChanged = { status ->
            pushBluetoothStatus(status)
        }
        
        bridge.onDeviceDiscovered = { devices ->
            val devicesArray = JSONArray()
            devices.forEach { device ->
                devicesArray.put(JSONObject(device.toMap()))
            }
            evaluateJS("window.W4RPBridge._notifyDeviceDiscovered($devicesArray);")
        }
        
        bridge.onStatusUpdate = { json ->
            evaluateJS("window.W4RPBridge._notifyStatus($json);")
        }
        
        bridge.onDebugData = { data ->
            val dataJson = JSONObject().apply {
                put("id", data.id)
                put("type", data.type.value)
                data.value?.let { put("value", it) }
                data.active?.let { put("active", it) }
            }
            evaluateJS("window.W4RPBridge._notifyDebug($dataJson);")
        }
        
        bridge.onReconnecting = { attempt ->
            evaluateJS("window.W4RPBridge._notifyReconnecting($attempt);")
        }
        
        bridge.onReconnected = {
            evaluateJS("window.W4RPBridge._notifyReconnected();")
        }
        
        bridge.onReconnectFailed = { error ->
            val errorJson = JSONObject().apply {
                put("code", error.code.code)
                put("message", error.message)
            }
            evaluateJS("window.W4RPBridge._notifyReconnectFailed($errorJson);")
        }
        
        bridge.onError = { error ->
            val errorJson = JSONObject().apply {
                put("code", error.code.code)
                put("message", error.message)
                error.context?.let { put("context", JSONObject(it)) }
            }
            evaluateJS("window.W4RPBridge._notifyError($errorJson);")
        }
    }
    
    // =========================================================================
    // JS Communication
    // =========================================================================
    
    private fun sendSuccess(id: Int, result: Any? = null) {
        val resultStr = when (result) {
            null -> "null"
            is JSONObject, is JSONArray -> result.toString()
            else -> result.toString()
        }
        evaluateJS("window.W4RPBridge._resolve($id, $resultStr);")
    }
    
    private fun sendError(id: Int, code: Int, message: String) {
        val errorJson = """{"code":$code,"message":"${message.replace("\"", "\\\"")}"}"""
        evaluateJS("window.W4RPBridge._reject($id, $errorJson);")
    }
    
    private fun updateConnectionState(state: W4RPConnectionState) {
        evaluateJS("window.W4RPBridge._setConnectionState('${state.name}');")
    }
    
    fun pushBluetoothStatus(status: W4RPBluetoothStatus? = null) {
        val s = status ?: bridge.bluetoothStatus.value
        evaluateJS("window.W4RPBridge._setBluetoothStatus('${s.name}');")
    }
    
    private fun updateConnectedDevice(device: W4RPDevice?) {
        if (device != null) {
            val deviceJson = JSONObject(device.toMap()).toString()
            evaluateJS("window.W4RPBridge._setConnectedDevice($deviceJson);")
        } else {
            evaluateJS("window.W4RPBridge._setConnectedDevice(null);")
        }
    }
    
    private fun pushCurrentState() {
        scope.launch {
            updateConnectionState(bridge.connectionState)
            pushBluetoothStatus(bridge.bluetoothStatus.value)
            bridge.connectedDevice?.let { updateConnectedDevice(it) }
        }
    }
    
    private fun evaluateJS(script: String) {
        webView?.post {
            webView?.evaluateJavascript(script, null)
        }
    }
}
