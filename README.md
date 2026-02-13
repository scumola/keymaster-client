# KeyMaster Client SDK

Kotlin client code for the [KeyMaster](http://www.badcheese.com/keymaster/) self-hosted device control server — an open alternative to the QIUI cloud API for controlling BLE chastity devices (KeyPod, CellMate, Metal).

## What is this?

KeyMaster lets you pair a **wearer** (who has the physical BLE device) with a **keyholder** (who can remotely lock/unlock it) through a self-hosted server instead of relying on QIUI's cloud. This repo contains the Kotlin client-side code you need to integrate with the server.

**Download the full app:** [keymaster.apk](http://www.badcheese.com/keymaster.apk)

## Quick Start

### 1. Add Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
}
```

### 2. Create the API Client

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder()
            .header("Authorization", "Bearer $jwtToken")
            .build())
    }
    .build()

val api = Retrofit.Builder()
    .baseUrl("http://www.badcheese.com/keymaster/api/")
    .client(client)
    .addConverterFactory(GsonConverterFactory.create())
    .build()
    .create(KeyMasterApi::class.java)
```

### 3. Register & Authenticate

```kotlin
// Register (first time)
val auth = api.register(AuthRequest("myuser", "mypass", "keyholder"))
val token = auth.token  // Save this — valid for 30 days

// Login (subsequent)
val auth = api.login(AuthRequest("myuser", "mypass"))
```

### 4. Pair Devices

The pairing flow requires both parties to be present (or to securely exchange the code + secret):

```kotlin
// WEARER: Generate a pairing code
val code = api.createPairingCode(CreateCodeRequest(deviceId))
// code.code = "PAZ3X4H4" (8-char alphanumeric)
// code.hmacSecret = "f04f5592..." (64-char hex - share with keyholder!)

// KEYHOLDER: Accept the code
val pairing = api.acceptPairingCode(AcceptCodeRequest("PAZ3X4H4"))
// pairing.hmacSecret = same secret (store securely!)
```

### 5. Send Commands (Keyholder)

Every command must be signed with HMAC-SHA256 and include a unique nonce:

```kotlin
val signer = CommandSigner()
val signed = signer.sign(
    pairingId = pairing.pairingId,
    commandType = "unlock",
    hmacSecret = pairing.hmacSecret,
)

api.sendCommand(SendCommandRequest(
    pairingId = pairing.pairingId,
    commandType = "unlock",
    nonce = signed.nonce,
    hmac = signed.hmac,
))
```

### 6. Poll & Execute Commands (Wearer)

```kotlin
// Poll for pending commands
val pending = api.pollCommands()
for (cmd in pending.commands) {
    // Execute the BLE command on the device...

    // Report result
    api.reportCommandResult(CommandResultRequest(
        commandId = cmd.id,
        status = "executed",
    ))
}
```

## API Reference

### Authentication

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/register` | POST | No | Create account. Rate: 5/min |
| `/login` | POST | No | Get JWT token. Rate: 5/min |

### Devices

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/device/register` | POST | JWT | Register a BLE device |

### Pairing

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/pairing/create-code` | POST | JWT | Generate 8-char code + HMAC secret |
| `/pairing/accept` | POST | JWT | Accept code, become keyholder. Rate: 10/min |
| `/pairing/list` | GET | JWT | List active pairings |
| `/pairing/revoke` | POST | JWT | Revoke a pairing |

### Commands

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/command/send` | POST | JWT + HMAC | Send command (keyholder only). Rate: 30/min |
| `/command/poll` | GET | JWT | Poll pending commands (wearer only) |
| `/command/result` | POST | JWT | Report command execution result |

### Status

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/status/update` | POST | JWT | Push device status (wearer) |
| `/status/{pairingId}` | GET | JWT | Get device status |

### Command Types

| Command | Description | Params |
|---------|-------------|--------|
| `unlock` | Unlock the device | — |
| `lock` | Lock the device | — |
| `timed_unlock` | Unlock for N seconds | `{"seconds": 30}` |
| `shock` | Send shock pulse | `{"voltage": 1-3, "duration": 1-5}` |
| `vibration` | Vibrate | `{"intensity": 1-3}` |
| `stop_all` | Stop all active commands | — |

## Security

KeyMaster implements multiple security layers to prevent the kind of mass-exploitation that affected QIUI's cloud service:

| Protection | How it works |
|------------|-------------|
| **JWT Authentication** | All endpoints (except register/login) require a valid bearer token |
| **IP Rate Limiting** | Per-endpoint limits prevent brute-force attacks |
| **Pairing Lockout** | 5 wrong pairing codes from one IP → 10-minute lockout + pending pairings invalidated |
| **8-char Pairing Codes** | 36^8 = 2.8 trillion combinations (vs QIUI's sequential IDs) |
| **HMAC Command Signing** | Every command is signed with a per-pairing secret key |
| **Nonce Replay Protection** | Each command nonce can only be used once |

### HMAC Signing Details

Commands are signed with: `HMAC-SHA256("{pairing_id}:{command_type}:{nonce}", hmac_secret)`

The server recomputes the HMAC and uses constant-time comparison (`hash_equals`) to verify. This ensures:
- Only someone with the shared secret can send commands
- Commands can't be tampered with in transit
- Captured commands can't be replayed (nonce uniqueness)

## Known Limitation: QIUI Cloud Dependency

The KeyMaster server is fully independent — authentication, pairing, command signing, and status tracking are all self-hosted. However, the **wearer's app currently still contacts QIUI's cloud** (`openapi.qiuitoy.com`) to obtain the raw BLE hex command bytes that make the hardware respond. QIUI's cloud is not involved in any authorization decisions — it just generates the device-specific byte sequences.

This means if QIUI's cloud goes down, commands can't execute. The security model is not affected (QIUI can't forge commands or identify keyholders), but availability depends on their uptime.

**Work is underway to reverse-engineer the BLE command format and generate bytes locally, eliminating this dependency entirely.** See [PROTOCOL-NEW.md](PROTOCOL-NEW.md#remaining-qiui-cloud-dependency) for the full technical breakdown.

## Supported Devices

| Device | BLE Service UUID | Type ID |
|--------|-----------------|---------|
| QIUI KeyPod | `0000fff0-...` | 1 |
| QIUI CellMate | `0000fee7-...` | 2 |
| QIUI Metal | `0000fee5-...` | 3 |

## Files

```
src/
├── KeyMasterApi.kt        # Retrofit interface (all HTTP endpoints)
├── KeyMasterApiModels.kt  # Request/response data classes
└── CommandSigner.kt       # HMAC signing utility
```

## License

MIT — do whatever you want with it.

## Contributing

Issues and PRs welcome. If you find a security vulnerability, please email steve@badcheese.com instead of opening a public issue.
