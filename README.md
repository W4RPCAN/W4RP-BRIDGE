# W4RP-BRIDGE

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-1.1.0-green.svg)]()

**Official client libraries for W4RPBLE firmware modules.**

These client libraries are designed to communicate with devices running the [W4RP-BLE firmware library](https://github.com/W4RPCAN/W4RP-BLE). The firmware is **required** and must be installed on your ESP32-based W4RP-compatible device for these clients to function.

**Website:** [https://w4rp.dev](https://w4rp.dev)

**ESP32 Firmware Library:** [https://github.com/W4RPCAN/W4RP-BLE](https://github.com/W4RPCAN/W4RP-BLE)

## Implementations

| Platform | File | Runtime |
|---|---|---|
| **iOS / macOS** | [`Swift/W4RPBridge.swift`](Swift/W4RPBridge.swift) | iOS 15+, macOS 12+ (async/await) |
| **Android** | [`Kotlin/W4RPBridge.kt`](Kotlin/W4RPBridge.kt) | API 21+ (Lollipop) |
| **Web** | [`WebBluetooth/W4RPBridge.ts`](WebBluetooth/W4RPBridge.ts) | Chrome 56+, Edge 79+ |

---

## Protocol Specification

### UUID Structure

All W4RP devices advertise the following BLE UUIDs:

```
Service:  0000fff0-5734-5250-5734-525000000000
RX:       0000fff1-5734-5250-5734-525000000000
TX:       0000fff2-5734-5250-5734-525000000000
Status:   0000fff3-5734-5250-5734-525000000000
```

**Why `5734-5250`?**

The middle octets encode "W4RP" in ASCII hexadecimal:

| Character | ASCII | Hex |
|-----------|-------|-----|
| W | 87 | 0x57 |
| 4 | 52 | 0x34 |
| R | 82 | 0x52 |
| P | 80 | 0x50 |

This provides a recognizable namespace (`W4RP`) while remaining fully compliant with the Bluetooth SIG UUID specification. The `fff0`-`fff3` prefix uses the reserved vendor-specific range.

### Characteristics

| UUID Suffix | Name | Properties | Description |
|-------------|------|------------|-------------|
| `fff1` | RX | Write, Write Without Response | Commands TO the module |
| `fff2` | TX | Notify | Data FROM the module |
| `fff3` | Status | Notify | Module status updates |

### Commands

Commands are UTF-8 strings written to the RX characteristic.

| Command | Description |
|---------|-------------|
| `GET:PROFILE` | Request module profile JSON |
| `SET:RULES:NVS:LEN:CRC` | Upload and save rules (persistent, survives reboot) |
| `SET:RULES:RAM:LEN:CRC` | Upload and test rules (volatile, lost on reboot) |
| `DEBUG:START` | Enable debug streaming |
| `DEBUG:STOP` | Disable debug streaming |
| `DEBUG:WATCH:LEN:CRC` | Set watched signals for debugging |
| `OTA:BEGIN:DELTA:SIZE:CRC` | Start Delta OTA firmware update |

### Streaming Protocol

Large payloads (rules, OTA patches) use a chunked streaming protocol:

```
[Client → Module]
1. Header:  "SET:RULES:NVS:1024:DEADBEEF"  (command:mode:length:crc32)
2. Chunks:  180-byte binary packets
3. Trailer: "END"

[Module → Client]
1. Header:  "BEGIN"
2. Chunks:  180-byte binary packets  
3. Trailer: "END:1024:DEADBEEF"  (marker:length:crc32)
```

**Chunk Size**: 180 bytes (safe for all MTU sizes)
**CRC32**: IEEE 802.3 polynomial (`0xEDB88320`)

---

## Error Codes

All implementations use standardized error codes organized by category:

### 1xxx: BLE Infrastructure

| Code | Name | Description |
|------|------|-------------|
| 1000 | `CONNECTION_FAILED` | GATT connection failed |
| 1001 | `CONNECTION_LOST` | Connection was lost unexpectedly |
| 1002 | `NOT_CONNECTED` | Operation requires an active connection |
| 1003 | `ALREADY_CONNECTED` | Already connected to a device |
| 1004 | `DEVICE_NOT_FOUND` | No device was found during scan |
| 1005 | `SERVICE_NOT_FOUND` | W4RP service not found on device |
| 1006 | `CHARACTERISTIC_NOT_FOUND` | Required characteristic not found |
| 1007 | `BLUETOOTH_OFF` | Bluetooth adapter is powered off |
| 1008 | `BLUETOOTH_UNAUTHORIZED` | Bluetooth permission denied |
| 1009 | `BLUETOOTH_UNSUPPORTED` | Platform does not support BLE (Web only) |

### 2xxx: Protocol Errors

| Code | Name | Description |
|------|------|-------------|
| 2000 | `INVALID_RESPONSE` | Module returned invalid response |
| 2003 | `WRITE_FAILED` | Failed to write to RX characteristic |

### 3xxx: Data Validation

| Code | Name | Description |
|------|------|-------------|
| 3000 | `INVALID_DATA` | Data format is invalid |
| 3001 | `CRC_MISMATCH` | CRC32 checksum does not match |
| 3002 | `LENGTH_MISMATCH` | Received length does not match expected |

### 4xxx: Timeouts

| Code | Name | Description |
|------|------|-------------|
| 4000 | `PROFILE_TIMEOUT` | Profile request timed out |
| 4001 | `STREAM_TIMEOUT` | Data stream timed out |
| 4002 | `SCAN_TIMEOUT` | Device scan timed out |
| 4003 | `CONNECTION_TIMEOUT` | Connection attempt timed out |

---

## Platform-Specific Notes

### iOS / macOS

**Required Info.plist keys:**
```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>Bluetooth is used to connect to your W4RP module.</string>

<key>NSBluetoothPeripheralUsageDescription</key>
<string>Bluetooth is used to connect to your W4RP module.</string>
```

### Android

**Required permissions (AndroidManifest.xml):**

```xml
<!-- API 31+ -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- API 23-30 -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<!-- All versions -->
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

### Web Bluetooth

**Requirements:**
- HTTPS connection (or localhost)
- User gesture required (button click)
- Chrome 56+, Edge 79+, Opera 43+

**NOT supported:** Firefox, Safari, any iOS browser

---

## Usage Examples

### Swift (iOS) - Async/Await

```swift
import W4RPBridge

let bridge = W4RPBridge()

Task {
    do {
        let devices = try await bridge.scan()
        guard let device = devices.first else { return }
        try await bridge.connect(to: device)
        let profile = try await bridge.getProfile()
        print("Module: \(profile.module.id)")
    } catch let error as W4RPError {
        print("Error \(error.code.rawValue): \(error.message)")
    }
}
```

### Kotlin (Android) - Suspend Functions

```kotlin
import com.w4rp.bridge.W4RPBridge
import kotlinx.coroutines.launch

val bridge = W4RPBridge(context)

lifecycleScope.launch {
    try {
        val devices = bridge.scan()
        val device = devices.firstOrNull() ?: return@launch
        bridge.connect(device)
        val profile = bridge.getProfile()
        Log.d("W4RP", "Profile: $profile")
    } catch (e: W4RPException) {
        Log.e("W4RP", "Error ${e.errorCode.code}: ${e.message}")
    }
}
```

### TypeScript (Web)

```typescript
import { W4RPBridge, W4RPError } from './W4RPBridge';

const bridge = new W4RPBridge();

// Must be triggered by user gesture
async function connect() {
    try {
        await bridge.connect();
        const profile = await bridge.getProfile();
        console.log('Module:', profile.module.id);
    } catch (error) {
        if (error instanceof W4RPError) {
            console.error(`Error ${error.code}: ${error.message}`);
        }
    }
}
```

---

## Roadmap

Future improvements planned:

- [x] **Async/Await API** - Native async/await patterns (Swift 5.5+ `async throws`, Kotlin Coroutines `suspend`, TypeScript `async`). Legacy callback APIs maintained for backward compatibility.
- [x] **OTA Progress Callbacks** - All implementations now support `onProgress(bytesWritten, totalBytes)` callback for firmware updates and rule uploads. Callback is optional.
- [x] **Connection State Machine** - All implementations now export `W4RPConnectionState` enum with states: `DISCONNECTED`, `SCANNING`, `CONNECTING`, `DISCOVERING_SERVICES`, `READY`, `DISCONNECTING`, `ERROR`. Added `connectionState` and `lastError` properties. Backward-compatible `isConnected` preserved.
- [x] **Retry with Exponential Backoff** - All implementations now provide `connectWithRetry()` method with configurable `W4RPRetryConfig` (maxRetries, baseDelay, maxDelay, multiplier). Includes `onRetry` callback for monitoring attempts.
- [x] **Automatic Reconnection** - All implementations now support optional auto-reconnect via `W4RPAutoReconnectConfig` with `onReconnecting`, `onReconnected`, `onReconnectFailed` callbacks. Uses exponential backoff and supports lifetime reconnect limits.
- [ ] **Package Distribution** - Publish to Swift Package Manager (SPM), Maven Central / JitPack, and npm

Contributions welcome! See individual implementation files for platform-specific notes.

---

## License

MIT License

Copyright (c) 2024 W4RP Automotive

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
