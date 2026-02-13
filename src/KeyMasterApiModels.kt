/**
 * KeyMaster API Data Models
 *
 * These data classes represent all request/response objects used by the KeyMaster
 * self-hosted server API. They are designed for use with Retrofit + Gson, but can
 * be adapted to any HTTP client and JSON library.
 *
 * Server API base URL: https://badcheese.com/keymaster/api/
 *
 * See README.md for full API documentation.
 */
package com.badcheese.keymaster.api

import com.google.gson.annotations.SerializedName

// ============================================================================
// Authentication
// ============================================================================

/**
 * Request body for POST /register and POST /login.
 *
 * @param username Unique username (3+ characters)
 * @param password Password (6+ characters)
 * @param role     Only used during registration: "keyholder" or "wearer"
 */
data class AuthRequest(
    val username: String,
    val password: String,
    val role: String? = null,
)

/**
 * Response from /register and /login on success.
 *
 * @param userId   Server-assigned user ID
 * @param username The authenticated username
 * @param role     "keyholder" or "wearer"
 * @param token    JWT bearer token (valid 30 days). Include in all subsequent
 *                 requests as: Authorization: Bearer <token>
 */
data class AuthResponse(
    @SerializedName("user_id") val userId: Int,
    val username: String,
    val role: String,
    val token: String,
)

// ============================================================================
// Device Registration
// ============================================================================

/**
 * Request body for POST /device/register.
 * Registers a BLE device so it can be paired with a keyholder.
 *
 * @param macAddress   Bluetooth MAC address (e.g., "AA:BB:CC:DD:EE:FF")
 * @param serialNumber Device serial number from QIUI API or BLE advertisement
 * @param typeId       Device type: 1 = KeyPod, 2 = CellMate, 3 = Metal
 * @param displayName  Optional friendly name for the device
 */
data class DeviceRegisterRequest(
    @SerializedName("mac_address") val macAddress: String,
    @SerializedName("serial_number") val serialNumber: String,
    @SerializedName("type_id") val typeId: Int,
    @SerializedName("display_name") val displayName: String?,
)

data class DeviceRegisterResponse(
    @SerializedName("device_id") val deviceId: Int,
    @SerializedName("mac_address") val macAddress: String,
    @SerializedName("serial_number") val serialNumber: String,
    @SerializedName("type_id") val typeId: Int,
)

// ============================================================================
// Pairing
// ============================================================================

/**
 * Request body for POST /pairing/create-code.
 * Called by the WEARER to generate a one-time pairing code.
 */
data class CreateCodeRequest(
    @SerializedName("device_id") val deviceId: Int,
)

/**
 * Response from /pairing/create-code.
 *
 * @param pairingId  Server-assigned pairing ID
 * @param code       8-character alphanumeric pairing code (A-Z, 0-9)
 * @param expiresAt  ISO timestamp when the code expires (10 minutes)
 * @param hmacSecret 64-character hex string used to sign commands.
 *                   The wearer must share this with the keyholder alongside the code.
 */
data class CreateCodeResponse(
    @SerializedName("pairing_id") val pairingId: Int,
    val code: String,
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("hmac_secret") val hmacSecret: String,
)

/**
 * Request body for POST /pairing/accept.
 * Called by the KEYHOLDER to accept a pairing code.
 */
data class AcceptCodeRequest(val code: String)

/**
 * Response from /pairing/accept.
 *
 * @param hmacSecret The HMAC secret for this pairing (same one the wearer received).
 *                   Store this securely - it's needed to sign every command.
 */
data class AcceptCodeResponse(
    @SerializedName("pairing_id") val pairingId: Int,
    @SerializedName("wearer_id") val wearerId: Int,
    @SerializedName("wearer_username") val wearerUsername: String,
    @SerializedName("device_id") val deviceId: Int,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("device_type_id") val deviceTypeId: Int,
    val status: String,
    @SerializedName("hmac_secret") val hmacSecret: String,
)

/**
 * Single pairing returned in the list response.
 *
 * @param hmacSecret Included for active pairings so the keyholder can sign commands.
 */
data class PairingInfo(
    val id: Int,
    @SerializedName("keyholder_id") val keyholderId: Int?,
    @SerializedName("wearer_id") val wearerId: Int,
    @SerializedName("device_id") val deviceId: Int,
    val status: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("wearer_username") val wearerUsername: String,
    @SerializedName("keyholder_username") val keyholderUsername: String?,
    @SerializedName("mac_address") val macAddress: String,
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("type_id") val typeId: Int,
    val battery: Int,
    @SerializedName("is_unlocked") val isUnlocked: Int,
    @SerializedName("last_status_at") val lastStatusAt: String?,
    @SerializedName("hmac_secret") val hmacSecret: String? = null,
)

data class PairingListResponse(val pairings: List<PairingInfo>)

data class RevokeRequest(@SerializedName("pairing_id") val pairingId: Int)

// ============================================================================
// Commands (Keyholder -> Wearer)
// ============================================================================

/**
 * Request body for POST /command/send.
 * Only the keyholder of an active pairing can send commands.
 *
 * Commands are protected by HMAC signing to prevent forgery:
 *   hmac = HMAC-SHA256("{pairing_id}:{command_type}:{nonce}", hmac_secret)
 *
 * @param pairingId   The pairing to send the command through
 * @param commandType One of: "unlock", "lock", "timed_unlock", "shock", "vibration", "stop_all"
 * @param params      Optional parameters (e.g., {"seconds": 30} for timed_unlock)
 * @param nonce       Unique string (UUID recommended) to prevent replay attacks
 * @param hmac        HMAC-SHA256 signature computed as described above
 */
data class SendCommandRequest(
    @SerializedName("pairing_id") val pairingId: Int,
    @SerializedName("command_type") val commandType: String,
    val params: Map<String, Any>? = null,
    val nonce: String,
    val hmac: String,
)

data class SendCommandResponse(
    @SerializedName("command_id") val commandId: Int,
    @SerializedName("command_type") val commandType: String,
    val status: String,
)

/**
 * Response from GET /command/poll.
 * Called by the WEARER to check for pending commands.
 */
data class PollCommandsResponse(val commands: List<PendingCommand>)

data class PendingCommand(
    val id: Int,
    @SerializedName("pairing_id") val pairingId: Int,
    @SerializedName("command_type") val commandType: String,
    val params: Map<String, Any>?,
    @SerializedName("created_at") val createdAt: String,
)

/**
 * Request body for POST /command/result.
 * Called by the WEARER after executing (or failing to execute) a command.
 */
data class CommandResultRequest(
    @SerializedName("command_id") val commandId: Int,
    val status: String, // "executed" or "failed"
    val result: Map<String, Any>? = null,
)

// ============================================================================
// Device Status
// ============================================================================

/**
 * Request body for POST /status/update.
 * Called by the WEARER to push device state to the server.
 */
data class StatusUpdateRequest(
    @SerializedName("device_id") val deviceId: Int,
    val battery: Int?,
    @SerializedName("is_unlocked") val isUnlocked: Boolean?,
)

data class DeviceStatusResponse(
    @SerializedName("device_id") val deviceId: Int,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("type_id") val typeId: Int,
    val battery: Int,
    @SerializedName("is_unlocked") val isUnlocked: Boolean,
    @SerializedName("last_status_at") val lastStatusAt: String?,
    @SerializedName("recent_commands") val recentCommands: List<RecentCommand>,
)

data class RecentCommand(
    val id: Int,
    @SerializedName("command_type") val commandType: String,
    val status: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("executed_at") val executedAt: String?,
)

// ============================================================================
// Generic Responses
// ============================================================================

data class SuccessResponse(val success: Boolean)
data class ErrorResponse(val error: String)
