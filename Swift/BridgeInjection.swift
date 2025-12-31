/**
 * W4RP Bridge - JavaScript Injection
 *
 * JavaScript code injected into WKWebView to provide the native bridge API.
 * Creates `window.W4RPBridge` with unified API matching Web/Android implementations.
 *
 * @license MIT
 * @copyright 2024 W4RP Automotive
 * @version 2.0.0
 */

import Foundation

// MARK: - JavaScript Bridge

/** JavaScript bridge extension for W4RPBridge. */
extension W4RPBridge {
    
    /** Message handler name for WKUserContentController registration. */
    public static let messageHandlerName = "W4RPBridge"
    
    /**
     * JavaScript bridge code to inject at document start.
     *
     * Creates `window.W4RPBridge` with the following API:
     * - `platform`: 'ios'
     * - `connectionState`: Current connection state
     * - `connectedDevice`: Connected device info or null
     * - `isConnected`: Boolean convenience getter
     * - `scan(timeoutMs?)`: Scan for devices
     * - `stopScan()`: Stop scanning
     * - `connect(deviceId)`: Connect to device
     * - `disconnect()`: Disconnect
     * - `getProfile()`: Get module profile
     * - `getRules()`: Get current rules binary
     * - `setRules(binary, persistent)`: Upload rules binary
     * - `setDebugMode(enabled)`: Toggle debug mode
     * - `startOTA(patchBase64)`: Start firmware update
     * - `setCallbacks(callbacks)`: Set event callbacks
     */
    public static let jsBridge: String = """
    /**
     * W4RPBridge - Native iOS Bridge
     * Injected by WKWebView. Provides unified API matching Web/Android.
     */
    (function() {
      'use strict';
      
      if (window.W4RPBridge) {
        console.log('[W4RP] Bridge already exists');
        return;
      }

      // Internal state
      const _pending = new Map();
      let _nextId = 1;
      let _connectionState = 'DISCONNECTED';
      let _connectedDevice = null;
      let _lastError = null;
      let _bluetoothStatus = 'UNKNOWN';
      let _callbacks = {};

      // Helper: decode base64 to Uint8Array (for data FROM native)
      function base64ToUint8Array(base64) {
        const binary = atob(base64);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) {
          bytes[i] = binary.charCodeAt(i);
        }
        return bytes;
      }

      // Helper: encode Uint8Array to base64 (for data TO native)
      function uint8ArrayToBase64(bytes) {
        let binary = '';
        for (let i = 0; i < bytes.length; i++) {
          binary += String.fromCharCode(bytes[i]);
        }
        return btoa(binary);
      }

      // Native communication
      function callNative(method, params = {}) {
        return new Promise((resolve, reject) => {
          const id = _nextId++;
          _pending.set(id, { resolve, reject, method });
          
          try {
            window.webkit.messageHandlers.W4RPBridge.postMessage({ id, method, params });
          } catch (e) {
            _pending.delete(id);
            reject(new Error('Failed to call native: ' + e.message));
          }
        });
      }

      // Bridge object
      const bridge = {
        platform: 'ios',

        get connectionState() { return _connectionState; },
        get connectedDevice() { return _connectedDevice; },
        get lastError() { return _lastError; },
        get bluetoothStatus() { return _bluetoothStatus; },
        get isConnected() { return _connectionState === 'READY'; },

        async scan(timeoutMs = 8000) {
          _lastError = null;
          return callNative('scan', { timeoutMs });
        },

        stopScan() {
          callNative('stopScan').catch(() => {});
        },

        async connect(deviceId) {
          if (_connectionState === 'READY') {
            throw new Error('Already connected');
          }
          _lastError = null;
          return callNative('connect', { deviceId });
        },

        async disconnect() {
          return callNative('disconnect');
        },

        async getProfile() {
          if (_connectionState !== 'READY') {
            throw new Error('Not connected');
          }
          // Native sends base64, decode to Uint8Array for unified API
          const base64 = await callNative('getProfile');
          return base64ToUint8Array(base64);
        },

        async getRules() {
          if (_connectionState !== 'READY') {
            throw new Error('Not connected');
          }
          // Native sends base64, decode to Uint8Array for unified API
          const base64 = await callNative('getRules');
          return base64ToUint8Array(base64);
        },

        async setRules(binary, persistent = true) {
          if (_connectionState !== 'READY') {
            throw new Error('Not connected');
          }
          // Native expects base64, encode Uint8Array before sending
          const base64 = uint8ArrayToBase64(binary);
          console.log('[W4RP] setRules - encoding to base64 (len: ' + binary.length + ')');
          return callNative('setRules', { binary: base64, persistent });
        },

        async setDebugMode(enabled) {
          if (_connectionState !== 'READY') {
            throw new Error('Not connected');
          }
          return callNative('setDebugMode', { enabled });
        },

        async watchDebugSignals(signals) {
          if (_connectionState !== 'READY') {
            throw new Error('Not connected');
          }
          return callNative('watchDebugSignals', { signals });
        },

        async startOTA(patchBase64) {
          if (_connectionState !== 'READY') {
            throw new Error('Not connected');
          }
          return callNative('startOTA', { patch: patchBase64 });
        },

        stopOTA() {
          callNative('stopOTA').catch(() => {});
        },

        async scanQRCode() {
          return callNative('scanQRCode');
        },

        setCallbacks(callbacks) {
          _callbacks = callbacks || {};
          // Immediately notify current status if available
          if (_callbacks.onBluetoothStatusChanged) {
             _callbacks.onBluetoothStatusChanged(_bluetoothStatus);
          }
        },

        notifyAppReady() {
          callNative('appReady').catch(() => {});
        },

        // Auto-reconnect config
        autoReconnectConfig: { enabled: false },
        onReconnecting: null,
        onReconnected: null,
        onReconnectFailed: null,

        // Native → JS handlers (called by coordinator)
        _resolve(id, result) {
          const p = _pending.get(id);
          if (p) {
            _pending.delete(id);
            p.resolve(result);
          }
        },

        _reject(id, error) {
          const p = _pending.get(id);
          if (p) {
            _pending.delete(id);
            _lastError = error;
            p.reject(new Error(error.message || 'Unknown error'));
          }
        },

        _setConnectionState(state) {
          const old = _connectionState;
          _connectionState = state;
          console.log('[W4RP] _setConnectionState:', old, '->', state);
          // CRITICAL: Notify React of state change
          _callbacks.onConnectionStateChanged?.(state);
          if (state === 'DISCONNECTED' && old === 'READY') {
            _connectedDevice = null;
            _callbacks.onDisconnect?.();
          }
        },

        _setConnectedDevice(device) {
          console.log('[W4RP] _setConnectedDevice called:', JSON.stringify(device));
          _connectedDevice = device;
        },

        _setBluetoothStatus(status) {
          const old = _bluetoothStatus;
          console.log('[W4RP] _setBluetoothStatus called:', old, '->', status);
          _bluetoothStatus = status;
          if (old !== status) {
            _callbacks.onBluetoothStatusChanged?.(status);
            window.dispatchEvent(new CustomEvent('w4rpBluetoothStatus', { detail: status }));
          }
        },

        _notifyDeviceDiscovered(devices) {
          _callbacks.onDeviceDiscovered?.(devices);
        },

        _notifyStatus(json) {
          _callbacks.onStatusUpdate?.(json);
        },

        _notifyDebug(data) {
          _callbacks.onDebugData?.(data);
        },

        _notifyProgress(sent, total) {
          _callbacks.onProgress?.(sent, total);
        },

        _notifyError(error) {
          _lastError = error;
          _callbacks.onError?.(error);
        },

        _notifyReconnecting(attempt) {
          bridge.onReconnecting?.(attempt);
        },

        _notifyReconnected() {
          _connectionState = 'READY';
          bridge.onReconnected?.();
        },

        _notifyReconnectFailed(error) {
          bridge.onReconnectFailed?.(error);
        }
      };

      window.W4RPBridge = bridge;
      console.log('[W4RP] Bridge installed (platform: ios)');
    })();
    """
}
