# W4RP iOS Bridge

Integrate the W4RP Bridge with your iOS WKWebView for BLE communication.

## Files

- `Bridge/BridgeBLE.swift` – Core Bluetooth logic
- `Bridge/BridgeCoordinator.swift` – WebView message handler
- `Bridge/BridgeInjection.swift` – JavaScript bridge code
- `Bridge/BridgeTypes.swift` – Data models

## Permissions

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>W4RP needs Bluetooth to connect to vehicle modules.</string>
```

## WebView Integration

Add this to your existing WKWebView setup:

```swift
// 1. Create bridge & coordinator
let bridge = W4RPBridge()
let coordinator = W4RPBridgeCoordinator(bridge: bridge)

// 2. Configure content controller
let contentController = WKUserContentController()

// 3. Inject bridge JS at document start
contentController.addUserScript(WKUserScript(
    source: W4RPBridge.jsBridge,
    injectionTime: .atDocumentStart,
    forMainFrameOnly: true
))

// 4. Register message handler
contentController.add(coordinator, name: W4RPBridge.messageHandlerName)

// 5. Create WebView with config
let config = WKWebViewConfiguration()
config.userContentController = contentController
let webView = WKWebView(frame: .zero, configuration: config)

// 6. Attach coordinator
coordinator.webView = webView

// 7. Load your app
webView.load(URLRequest(url: URL(string: "https://your-app-url")!))
```

That's it. The web app now has access to `window.W4RPBridge`.
