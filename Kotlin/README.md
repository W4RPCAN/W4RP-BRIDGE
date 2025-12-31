# W4RP Android Bridge

Integrate the W4RP Bridge with your Android WebView for BLE communication.

## Files

- `BridgeBLE.kt` – Core Bluetooth logic
- `BridgeCoordinator.kt` – WebView JavaScript interface  
- `BridgeInjection.kt` – JavaScript bridge code
- `BridgeTypes.kt` – Data models

## Permissions

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

## WebView Integration

Add this to your existing WebView setup:

```kotlin
// 1. Create bridge & coordinator
val bridge = W4RPBridge(applicationContext)
val coordinator = W4RPBridgeCoordinator(bridge)

// 2. Add JS interface BEFORE loading
webView.addJavascriptInterface(coordinator, W4RPBridge.INTERFACE_NAME)

// 3. Inject bridge JS on page start
webView.webViewClient = object : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        view?.evaluateJavascript(W4RPBridge.jsBridge, null)
    }
    
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        coordinator.attach(webView)
    }
}

// 4. Load your app
webView.loadUrl("https://your-app-url")
```

That's it. The web app now has access to `window.W4RPBridge`.
