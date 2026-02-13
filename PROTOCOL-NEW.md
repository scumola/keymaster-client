# KeyMaster Protocol (New)

How the KeyMaster self-hosted server authenticates users, pairs devices, and signs commands. Designed to prevent every attack vector that was exploited in the QIUI cloud breach.

## Architecture

```
┌──────────────┐                              ┌──────────────┐
│   Keyholder  │                              │    Wearer    │
│     App      │                              │     App      │
└──────┬───────┘                              └──┬────┬──────┘
       │                                         │    │
       │  HTTPS + JWT + HMAC                     │    │ HTTPS + JWT
       │                                         │    │
       ▼                                         │    ▼
┌─────────────────────────────────────────────── │ ──────────┐
│              KeyMaster Server (PHP)             │           │
│                                                 │           │
│  ┌───────────┐  ┌──────────┐  ┌──────────────┐│           │
│  │ Rate      │  │ JWT Auth │  │ HMAC         ││           │
│  │ Limiter   │  │          │  │ Verification ││           │
│  └───────────┘  └──────────┘  └──────────────┘│           │
│                                                 │           │
│  ┌───────────┐  ┌──────────┐  ┌──────────────┐│           │
│  │ Pairing   │  │ Nonce    │  │ Command      ││           │
│  │ Lockout   │  │ Registry │  │ Queue        ││           │
│  └───────────┘  └──────────┘  └──────────────┘│           │
└────────────────────┬───────────────────────────┘           │
                     │                                        │
                     │ MySQL                                  │
                     ▼                                        │
                ┌──────────┐                                  │
                │ Database │                                  │
                └──────────┘                                  │
                                                              │
                        ┌─────────────────────────────────────┘
                        │
                        │  HTTPS (get BLE hex bytes)
                        │  ⚠ REMAINING QIUI DEPENDENCY
                        ▼
                  ┌──────────────────┐
                  │  QIUI Cloud      │
                  │  openapi.qiuitoy │
                  └──────────────────┘
                        │
                        │ Returns hex command bytes
                        ▼
                  Wearer's Phone
                        │
                        │ BLE (write hex, read response)
                        ▼
                  ┌──────────┐
                  │  Device  │
                  │ (KeyPod/ │
                  │ CellMate/│
                  │  Metal)  │
                  └──────────┘
```

**Key difference from QIUI's model:** Our server handles all user authentication, pairing, and command authorization. QIUI's cloud is never involved in deciding *who* can control a device — it only provides the raw BLE hex bytes that make the hardware respond. See [Remaining QIUI Dependency](#remaining-qiui-cloud-dependency) for details and the plan to eliminate it.

## Authentication

### Registration

```
POST /register
Content-Type: application/json

{
  "username": "alice",
  "password": "hunter2hunter2",
  "role": "wearer"
}

Response (200):
{
  "user_id": 42,
  "username": "alice",
  "role": "wearer",
  "token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9..."
}
```

**Rate limit:** 5 registration attempts per IP per minute.

### Login

```
POST /login
Content-Type: application/json

{
  "username": "alice",
  "password": "hunter2hunter2"
}

Response (200):
{
  "user_id": 42,
  "username": "alice",
  "role": "wearer",
  "token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9..."
}
```

**Rate limit:** 5 login attempts per IP per minute. 6th attempt in the window:

```
HTTP 429 Too Many Requests
Retry-After: 60

{ "error": "Too many requests", "retry_after": 60 }
```

### JWT Token

```
Header:  { "typ": "JWT", "alg": "HS256" }
Payload: { "user_id": 42, "username": "alice", "role": "wearer",
           "iat": 1771000000, "exp": 1773592000 }
Signature: HMAC-SHA256(header.payload, server_secret)
```

| Property | Value |
|----------|-------|
| Algorithm | HMAC-SHA256 |
| Secret | Server-side only (never transmitted) |
| Lifetime | 30 days from issuance |
| `iat` | Issued-at timestamp (Unix epoch seconds) |
| `exp` | Expiration timestamp (Unix epoch seconds) |
| Validation | Signature verified + `exp` checked on every request |

The server checks `exp < time()` on every authenticated request. Expired tokens are rejected with 401.

**All subsequent requests** require:
```
Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9...
```

### Contrast with QIUI

```
QIUI:      Hardcoded Client ID → shared token for ALL users
KeyMaster: Per-user bcrypt password → unique JWT per session

QIUI:      No rate limiting on auth
KeyMaster: 5 attempts/minute, then 429 for 60 seconds
```

## Device Registration

```
POST /device/register
Authorization: Bearer {jwt}
Content-Type: application/json

{
  "mac_address": "AA:BB:CC:DD:EE:FF",
  "serial_number": "QIUIabc1234567",
  "type_id": 1,
  "display_name": "My KeyPod"
}

Response (200):
{
  "device_id": 7,
  "mac_address": "AA:BB:CC:DD:EE:FF",
  "serial_number": "QIUIabc1234567",
  "type_id": 1
}
```

The device is linked to the authenticated user's account. **Only the owner can operate on it.** Device IDs are opaque — knowing one doesn't help you guess or access others, because every operation checks ownership via JWT.

## Pairing Flow

Pairing is how a wearer grants a keyholder control of their device. It requires **out-of-band communication** — the wearer must give the keyholder both a code and a secret, either in person or through a trusted channel.

### Full Flow

```
       Wearer                    Server                   Keyholder
         │                         │                         │
    ┌────┴─────┐                   │                         │
    │ Registers│                   │                         │
    │ device   │                   │                         │
    └────┬─────┘                   │                         │
         │                         │                         │
  ────── │ ─── STEP 1: CREATE CODE │ ─────────────────────── │ ──
         │                         │                         │
         │   POST /pairing/        │                         │
         │   create-code           │                         │
         │   {device_id: 7}        │                         │
         │────────────────────────►│                         │
         │                         │  Generate:              │
         │                         │  - 8-char code (A-Z0-9) │
         │                         │  - 64-char HMAC secret  │
         │                         │  - 10-minute expiry     │
         │◄────────────────────────│                         │
         │   {                     │                         │
         │     code: "PAZ3X4H4",   │                         │
         │     hmac_secret: "f04f. │..",                     │
         │     expires_at: "..."   │                         │
         │   }                     │                         │
         │                         │                         │
  ────── │ ─── STEP 2: SHARE (OUT- │OF-BAND) ────────────── │ ──
         │                         │                         │
         │   "The code is PAZ3X4H4 │and the key is f04f..."  │
         │─────────────────────────┼────────────────────────►│
         │   (in person, phone,    │  encrypted message)     │
         │                         │                         │
  ────── │ ─── STEP 3: ACCEPT CODE │ ─────────────────────── │ ──
         │                         │                         │
         │                         │   POST /pairing/accept  │
         │                         │   {code: "PAZ3X4H4"}    │
         │                         │◄────────────────────────│
         │                         │                         │
         │                         │  ┌─────────────────┐    │
         │                         │  │Check IP lockout  │    │
         │                         │  │Log attempt       │    │
         │                         │  │Validate code     │    │
         │                         │  │Verify not expired│    │
         │                         │  │Verify not self   │    │
         │                         │  │Activate pairing  │    │
         │                         │  └─────────────────┘    │
         │                         │                         │
         │                         │────────────────────────►│
         │                         │   {                     │
         │                         │     pairing_id: 2,      │
         │                         │     wearer_username:     │
         │                         │       "alice",          │
         │                         │     hmac_secret:         │
         │                         │       "f04f...",        │
         │                         │     status: "active"    │
         │                         │   }                     │
         │                         │                         │
         ▼                         ▼                         ▼
```

### Pairing Code Properties

```
Code alphabet:    A B C D E F G H I J K L M N O P Q R S T U V W X Y Z 0 1 2 3 4 5 6 7 8 9
Code length:      8 characters
Code space:       36^8 = 2,821,109,907,456 combinations
Code lifetime:    10 minutes (server checks code_expires_at > NOW())
Code source:      PHP random_bytes(8) → mapped to alphabet
                  (cryptographically secure PRNG)
```

### HMAC Secret Properties

```
Secret length:    64 hex characters (32 bytes of entropy)
Secret source:    PHP bin2hex(random_bytes(32))
                  (cryptographically secure PRNG)
Secret lifetime:  Lives as long as the pairing is active
Secret scope:     One secret per pairing (not shared across pairings)
```

### Pairing Lockout

The server tracks every pairing attempt per IP address:

```
Attempt 1:  WRONG1XX  → 404 "Invalid or expired pairing code"
Attempt 2:  WRONG2XX  → 404 "Invalid or expired pairing code"
Attempt 3:  WRONG3XX  → 404 "Invalid or expired pairing code"
Attempt 4:  WRONG4XX  → 404 "Invalid or expired pairing code"
Attempt 5:  WRONG5XX  → 404 "Invalid or expired pairing code"
                          └──► 5 failures reached:
                               DELETE any pending pairings
                               whose codes were tried by this IP
Attempt 6:  ────────  → 429 "Too many pairing attempts.
                              Try again in 10 minutes."
```

**Window:** 10 minutes sliding. Attempts older than 10 minutes are not counted.

**Invalidation:** On the 5th failure, any pending pairing codes that this IP guessed are deleted from the database — even if one of them was correct. This prevents a slow-roll attack where the attacker gets lucky on attempt 4.

### Brute-Force Feasibility

```
Code space:              2,821,109,907,456 (2.8 trillion)
Attempts before lockout: 5
Lockout duration:        10 minutes
Attempts per hour:       30

Expected attempts to
  crack one code:        ~1.4 trillion
Time at 30/hour:         ~5.3 million years

Code expiry:             10 minutes
Effective window:        5 attempts total
Success probability:     5 / 2,821,109,907,456 = 0.00000000018%
```

### Contrast with QIUI

```
QIUI:      No pairing code. Know the MAC → own the device.
KeyMaster: 8-char code + 64-char HMAC secret, expires in 10 minutes,
           5 guesses then lockout + code invalidation.

QIUI:      Sequential device IDs (enumerate all devices)
KeyMaster: IDs are useless without JWT + HMAC secret for the specific pairing
```

## Command Flow

### Sending a Command (Keyholder → Server → Wearer → Device)

```
  Keyholder App                Server                    Wearer App          Device
       │                         │                         │                   │
       │  1. Build signed command│                         │                   │
       │                         │                         │                   │
       │  nonce = UUID()         │                         │                   │
       │  message = "2:unlock:   │                         │                   │
       │    a1b2c3d4-..."        │                         │                   │
       │  hmac = HMAC-SHA256(    │                         │                   │
       │    message, hmac_secret)│                         │                   │
       │                         │                         │                   │
       │  POST /command/send     │                         │                   │
       │  {                      │                         │                   │
       │    pairing_id: 2,       │                         │                   │
       │    command_type:"unlock",│                        │                   │
       │    nonce: "a1b2c3d4-...",│                        │                   │
       │    hmac: "7f3a..."      │                         │                   │
       │  }                      │                         │                   │
       │────────────────────────►│                         │                   │
       │                         │                         │                   │
       │                  ┌──────┴──────────┐              │                   │
       │                  │ 2. Verify JWT   │              │                   │
       │                  │ 3. Check user is│              │                   │
       │                  │    keyholder of │              │                   │
       │                  │    pairing #2   │              │                   │
       │                  │ 4. Fetch        │              │                   │
       │                  │    hmac_secret  │              │                   │
       │                  │    from pairing │              │                   │
       │                  │ 5. Recompute:   │              │                   │
       │                  │    expected =   │              │                   │
       │                  │    HMAC-SHA256( │              │                   │
       │                  │    "2:unlock:   │              │                   │
       │                  │    a1b2c3d4-.", │              │                   │
       │                  │    hmac_secret) │              │                   │
       │                  │ 6. hash_equals( │              │                   │
       │                  │    expected,    │              │                   │
       │                  │    submitted)   │              │                   │
       │                  │ 7. Check nonce  │              │                   │
       │                  │    not in DB    │              │                   │
       │                  │ 8. Store command│              │                   │
       │                  │    + nonce      │              │                   │
       │                  └──────┬──────────┘              │                   │
       │                         │                         │                   │
       │◄────────────────────────│                         │                   │
       │  {command_id: 19,       │                         │                   │
       │   status: "pending"}    │                         │                   │
       │                         │                         │                   │
       │                         │  3. Wearer polls        │                   │
       │                         │                         │                   │
       │                         │   GET /command/poll     │                   │
       │                         │◄────────────────────────│                   │
       │                         │                         │                   │
       │                         │────────────────────────►│                   │
       │                         │   {commands: [{         │                   │
       │                         │     id: 19,             │                   │
       │                         │     command_type:       │                   │
       │                         │       "unlock",         │                   │
       │                         │     ...                 │                   │
       │                         │   }]}                   │                   │
       │                         │                         │                   │
       │                         │                 4. BLE  │                   │
       │                         │                         │  Write unlock     │
       │                         │                         │  bytes to char    │
       │                         │                         │──────────────────►│
       │                         │                         │◄──────────────────│
       │                         │                         │  BLE notification │
       │                         │                         │  (success/fail)   │
       │                         │                         │                   │
       │                         │  5. Report result       │                   │
       │                         │                         │                   │
       │                         │   POST /command/result  │                   │
       │                         │   {command_id: 19,      │                   │
       │                         │    status: "executed"}  │                   │
       │                         │◄────────────────────────│                   │
       │                         │                         │                   │
       ▼                         ▼                         ▼                   ▼
```

### HMAC Signing Details

```
┌─────────────────────────────────────────────────────────┐
│                    HMAC Computation                      │
│                                                         │
│  Input:                                                 │
│    pairing_id  = 2                                      │
│    command_type = "unlock"                               │
│    nonce       = "a1b2c3d4-e5f6-7890-abcd-ef1234567890" │
│    hmac_secret = "f04f5592428f7adbcbed99e9c8051cf2..."   │
│                                                         │
│  Message string:                                        │
│    "2:unlock:a1b2c3d4-e5f6-7890-abcd-ef1234567890"     │
│                                                         │
│  HMAC = HMAC-SHA256(message, hmac_secret)               │
│       = "7f3a9b2c..." (64 hex chars)                    │
│                                                         │
│  Server verification:                                   │
│    expected = HMAC-SHA256(same message, same secret)    │
│    hash_equals(expected, submitted)                     │
│    ─────────────────────────────────                    │
│    Constant-time comparison (prevents timing attacks)   │
└─────────────────────────────────────────────────────────┘
```

### Nonce Replay Protection

Every command nonce is stored in the database with a UNIQUE constraint:

```
Command #19:  nonce = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"  ✓ Stored
Command #20:  nonce = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"  ✗ 409 Conflict
                      ──────────────────────────────────────
                      Same nonce → rejected as replay attack
```

```
HTTP 409 Conflict
{ "error": "Duplicate nonce (possible replay attack)" }
```

**Nonce format:** UUID v4 (generated client-side). Any unique string works — the server only checks for duplicates.

### What Each Layer Prevents

```
Attack Scenario                          Blocked By
───────────────────────────────────────────────────────────
Stranger sends unlock command            JWT (no valid token)
Another user sends unlock command        Pairing check (not the keyholder)
Keyholder's token stolen, wrong pairing  HMAC (no secret for that pairing)
Man-in-the-middle modifies command       HMAC (signature won't match)
Captured command replayed later          Nonce (already used)
Script tries many pairing codes          Rate limit + lockout
Bot tries to brute-force login           Rate limit (5/min)
Mass enumeration of devices              JWT + pairing gate (no access)
───────────────────────────────────────────────────────────
```

## Rate Limiting

All endpoints are rate-limited by IP address. The server records every request and checks the count within a sliding window:

```
┌──────────────────────────┬─────────────────┬────────────┐
│ Endpoint                 │ Max Requests    │ Window     │
├──────────────────────────┼─────────────────┼────────────┤
│ /login                   │ 5               │ 60 seconds │
│ /register                │ 5               │ 60 seconds │
│ /pairing/accept          │ 10              │ 60 seconds │
│ /command/send            │ 30              │ 60 seconds │
│ All other endpoints      │ 60              │ 60 seconds │
└──────────────────────────┴─────────────────┴────────────┘
```

Rate limit entries older than 1 hour are automatically cleaned up on each request to prevent table bloat.

When rate-limited:
```
HTTP 429 Too Many Requests
Retry-After: 60

{ "error": "Too many requests", "retry_after": 60 }
```

## Timestamps

| Field | Format | Where | How it's used |
|-------|--------|-------|--------------|
| JWT `iat` | Unix epoch (seconds) | Token payload | Informational — when the token was issued |
| JWT `exp` | Unix epoch (seconds) | Token payload | Checked on every request: `exp < time()` → 401 |
| `code_expires_at` | `YYYY-MM-DD HH:MM:SS` (MySQL DATETIME) | `pairings` table | Checked during accept: `code_expires_at > NOW()` → 404 if expired |
| `attempted_at` | MySQL TIMESTAMP (auto) | `rate_limits`, `pairing_attempts` | Sliding window: `attempted_at > DATE_SUB(NOW(), INTERVAL N)` |
| `created_at` | MySQL TIMESTAMP (auto) | `commands`, `pairings` | Ordering only — commands polled in chronological order |
| `executed_at` | MySQL DATETIME | `commands` | Set to `NOW()` when wearer reports result |
| `last_status_at` | MySQL DATETIME | `devices` | Updated on each status push from wearer |

**All timestamps are server-generated** (MySQL `NOW()` or PHP `time()`). The client never provides timestamps — this prevents clock-skew attacks where an attacker manipulates timestamps to extend token or code validity.

## Security Comparison

```
┌────────────────────────┬─────────────────────┬───────────────────────────┐
│ Protection             │ QIUI Cloud          │ KeyMaster                 │
├────────────────────────┼─────────────────────┼───────────────────────────┤
│ User authentication    │ ✗ Shared Client ID  │ ✓ Per-user bcrypt + JWT   │
│ Password storage       │ ✗ N/A (no accounts) │ ✓ bcrypt hash (salted)    │
│ Token uniqueness       │ ✗ Same for everyone │ ✓ Unique per user/session │
│ Token expiry           │ ~ 12 hours          │ ✓ 30 days (checked)       │
│ Device ownership       │ ✗ None              │ ✓ user_id FK on devices   │
│ Pairing verification   │ ✗ Know MAC = own it │ ✓ 8-char code + secret    │
│ Pairing code entropy   │ ✗ N/A               │ ✓ 36^8 = 2.8 trillion     │
│ Pairing expiry         │ ✗ N/A               │ ✓ 10-minute window        │
│ Brute-force protection │ ✗ None              │ ✓ 5 attempts → lockout    │
│ Code invalidation      │ ✗ N/A               │ ✓ Tried codes deleted     │
│ Command signing        │ ✗ None              │ ✓ HMAC-SHA256 per command │
│ Replay protection      │ ✗ None              │ ✓ Unique nonces (UUID)    │
│ Timing attack defense  │ ✗ N/A               │ ✓ hash_equals()           │
│ Rate limiting          │ ✗ None              │ ✓ Per-endpoint per-IP     │
│ Device enumeration     │ ✗ Sequential IDs    │ ✓ JWT-gated, no listing   │
│ MQTT snooping          │ ✗ Shared creds      │ ✓ No MQTT (HTTP polling)  │
│ Transport encryption   │ ~ HTTPS only        │ ✓ HTTPS + HMAC signing    │
│ API encryption         │ ✗ Disabled (no-op)  │ ✓ Not needed (HMAC)       │
│ Mass attack cost       │ 30 lines of Python  │ ~5.3 million years/device │
└────────────────────────┴─────────────────────┴───────────────────────────┘
```

## Trust Model

```
┌─────────────────────────────────────────────────────────────┐
│                     WHO TRUSTS WHOM                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Wearer trusts:                                             │
│    - Server (stores credentials, brokers commands)          │
│    - Keyholder (controls lock/unlock via signed commands)   │
│                                                             │
│  Keyholder trusts:                                          │
│    - Server (delivers commands, reports status)             │
│                                                             │
│  Server trusts:                                             │
│    - JWT signature (user identity)                          │
│    - HMAC signature (command authenticity)                  │
│    - Nonce uniqueness (command freshness)                   │
│    - bcrypt (password verification)                         │
│    - Nothing from the client is trusted without validation  │
│                                                             │
│  Device trusts:                                             │
│    - BLE proximity (physical layer security)                │
│    - The wearer's phone (only thing that talks BLE)         │
│    - Does NOT trust the server (server never touches it)    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

The device itself has no internet connection and no awareness of the server. It only responds to BLE writes from the wearer's phone. This means even if the server is completely compromised, the attacker still cannot lock or unlock any device — they would need physical BLE proximity to each one.

## Remaining QIUI Cloud Dependency

KeyMaster's server is fully independent for authentication, pairing, command authorization, and status tracking. However, **the wearer's app still calls QIUI's cloud** for three things:

### What still goes through QIUI

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│  1. DEVICE DISCOVERY (one-time, during setup)                       │
│     queryDeviceInfo / addDeviceInfo                                 │
│     App sends MAC address → QIUI returns serial number + type ID    │
│                                                                     │
│  2. BLE COMMAND GENERATION (every command)                          │
│     getKeyPodUnlockCmd / buildCellMatePro4GUnLockCmd / etc.         │
│     App sends MAC + serial + type → QIUI returns hex bytes          │
│     App writes hex bytes to the BLE device characteristic           │
│                                                                     │
│  3. BLE RESPONSE DECRYPTION (every command response)                │
│     decryBluetoothCommand                                           │
│     App sends encrypted BLE notification → QIUI returns plaintext   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### The actual command flow (honest version)

```
Keyholder ──► KeyMaster Server ──► Wearer App ──► QIUI Cloud ──► Wearer App ──► BLE Device
              (JWT + HMAC)         (polls cmd)    (get hex)       (writes hex)
```

Steps 1-5 in the command flow diagram above are accurate. But between steps 3 and 4, there's a hidden detour:

```
  Wearer App                      QIUI Cloud                    Device
       │                               │                          │
       │  Received "unlock" from       │                          │
       │  our server. Now need the     │                          │
       │  actual BLE bytes.            │                          │
       │                               │                          │
       │  POST /device/keyPod/         │                          │
       │    getKeyPodUnlockCmd         │                          │
       │  {mac, serial, typeId}        │                          │
       │──────────────────────────────►│                          │
       │                               │                          │
       │◄──────────────────────────────│                          │
       │  {data: "AB12CD34EF56..."}    │                          │
       │                               │                          │
       │  Convert hex to bytes,                                   │
       │  write to BLE characteristic                             │
       │─────────────────────────────────────────────────────────►│
       │◄─────────────────────────────────────────────────────────│
       │  BLE notification (encrypted)                            │
       │                               │                          │
       │  POST /device/keyPod/         │                          │
       │    decryBluetoothCommand      │                          │
       │  {response hex}               │                          │
       │──────────────────────────────►│                          │
       │◄──────────────────────────────│                          │
       │  {decrypted status}           │                          │
       │                               │                          │
       ▼                               ▼                          ▼
```

### What this means

| Scenario | Impact |
|----------|--------|
| QIUI cloud goes down | Commands fail — wearer can't get BLE hex bytes |
| QIUI deprecates API | All commands stop working |
| QIUI changes API auth | App needs updating |
| QIUI logs our requests | They see which MACs are being commanded (but not by whom — our server handles identity) |

### What KeyMaster still protects even with this dependency

Even though QIUI generates the BLE bytes, they can't exploit this because:
- QIUI doesn't know who the keyholder is (our server handles pairing)
- QIUI can't send unsolicited commands (BLE requires physical proximity from the wearer's phone)
- QIUI can't forge commands through our server (HMAC signing)
- The wearer's phone decides when to request and write BLE bytes

The risk is **availability, not security**. QIUI can break our app by shutting down, but they can't use this dependency to attack our users.

### Plan: Phase 5 — Local Command Generation

The goal is to reverse-engineer the BLE command byte format so we can generate them locally, eliminating QIUI entirely. This requires understanding:
- The structure of the hex command bytes (headers, opcodes, checksums)
- Whether the "device token" is embedded in commands
- How BLE responses are encrypted (likely AES-128-CBC with the known key/IV)
- Per-device-type differences (KeyPod vs CellMate vs Metal)

Once complete, the architecture becomes fully independent:

```
Keyholder ──► KeyMaster Server ──► Wearer App ──► BLE Device
              (JWT + HMAC)         (polls cmd,
                                    generates hex
                                    locally)

              No QIUI dependency.
```
