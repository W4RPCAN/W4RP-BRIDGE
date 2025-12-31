# W4RP-BRIDGE

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-2.0.0-blue.svg)]()

**Official client libraries for W4RPBLE firmware modules.**

These client libraries are designed to communicate with devices running the [W4RP-BLE firmware library](https://github.com/W4RPCAN/W4RP-BLE). The firmware is **required** and must be installed on your ESP32-based W4RP-compatible device for these clients to function.

**Website:** [https://w4rp.dev](https://w4rp.dev)

**ESP32 Firmware Library:** [https://github.com/W4RPCAN/W4RP-BLE](https://github.com/W4RPCAN/W4RP-BLE)

## Architecture

The bridge acts as a **dumb pipe** - a pure binary transport layer. All parsing and compiling logic lives in the UI:

```
UI (TypeScript)                Bridge                    Firmware
───────────────                ──────                    ────────
WBPCompiler (JSON→bin) ──────► pass bytes ─────────►    Binary
WBPParser (bin→JSON)  ◄─────── pass bytes ◄──────────   Binary
```

| Component | Location | Purpose |
|-----------|----------|---------|
| **WBPCompiler** | UI/SDK | Compiles JSON rules → WBP binary |
| **WBPParser** | UI/SDK | Parses WBP binary → JSON |
| **Bridge** | iOS/Android/Web | Just moves bytes |

## Implementations

| Platform | File | Runtime |
|---|---|---|
| **iOS / macOS** | [`Swift/BridgeBLE.swift`](Swift/BridgeBLE.swift) | iOS 15+, macOS 12+ (async/await) |
| **Android** | [`Kotlin/BridgeBLE.kt`](Kotlin/BridgeBLE.kt) | API 21+ (Lollipop) |
| **Web** | [`WebBluetooth/BridgeBLE.ts`](WebBluetooth/BridgeBLE.ts) | Chrome 56+, Edge 79+ |

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
| `GET:PROFILE` | Request WBP binary profile |
| `SET:RULES:NVS:LEN:CRC` | Upload WBP binary rules (persistent) |
| `SET:RULES:RAM:LEN:CRC` | Upload WBP binary rules (volatile) |
| `DEBUG:START` | Enable debug streaming |
| `DEBUG:STOP` | Disable debug streaming |
| `DEBUG:WATCH:LEN:CRC` | Set watched signals for debugging |
| `OTA:BEGIN:DELTA:SIZE:CRC` | Start Delta OTA firmware update |

### WBP Binary Protocol (v2)

All profile and rules data uses the **WBP binary format**:

- `GET:PROFILE` returns raw WBP binary (not JSON)
- `SET:RULES` expects compiled WBP binary (not JSON)

The UI is responsible for parsing/compiling using `WBPParser` and `WBPCompiler`.

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

### Swift (iOS) - Dumb Pipe

```swift
let bridge = W4RPBridge()

Task {
    do {
        let devices = try await bridge.scan()
        guard let device = devices.first else { return }
        try await bridge.connect(to: device)
        
        // getProfile returns raw Data - parse in UI
        let profileData = try await bridge.getProfile()
        let base64 = profileData.base64EncodedString()
        // Send to JS: WBPParser.parse(atob(base64))
        
        // setRules expects compiled binary - compile in UI
        let binary: Data = ... // from WBPCompiler
        try await bridge.setRules(binary: binary, persistent: true)
    } catch let error as W4RPError {
        print("Error \(error.code.rawValue): \(error.message)")
    }
}
```

### Kotlin (Android) - Dumb Pipe

```kotlin
val bridge = W4RPBridge(context)

lifecycleScope.launch {
    try {
        val devices = bridge.scan()
        val device = devices.firstOrNull() ?: return@launch
        bridge.connect(device)
        
        // getProfile returns raw ByteArray - parse in UI
        val profileData = bridge.getProfile()
        val base64 = Base64.encodeToString(profileData, Base64.NO_WRAP)
        // Send to JS: WBPParser.parse(atob(base64))
        
        // setRules expects compiled binary - compile in UI
        val binary: ByteArray = ... // from WBPCompiler
        bridge.setRules(binary, persistent = true)
    } catch (e: W4RPError) {
        Log.e("W4RP", "Error ${e.code.code}: ${e.message}")
    }
}
```

### TypeScript (Web) - Dumb Pipe

```typescript
import { W4RPBridge } from './BridgeBLE';
import { WBPParser, WBPCompiler } from './wbp'; // Your UI library

const bridge = new W4RPBridge();

async function connect() {
    const devices = await bridge.scan();
    await bridge.connect(devices[0].id);
    
    // getProfile returns Uint8Array - parse locally
    const profileBinary = await bridge.getProfile();
    const profile = WBPParser.parse(profileBinary);
    console.log('Module:', profile.moduleId);
    
    // setRules expects Uint8Array - compile locally
    const rules = { signals: [...], nodes: [...] };
    const binary = WBPCompiler.compile(rules);
    await bridge.setRules(binary, true);
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
