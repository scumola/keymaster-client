/**
 * CommandSigner - HMAC Command Signing Utility
 *
 * Every command sent through the KeyMaster API must be signed with an HMAC-SHA256
 * signature to prevent forgery and replay attacks.
 *
 * The signing flow:
 * 1. When a pairing is created, the server generates a shared HMAC secret
 * 2. Both wearer and keyholder receive this secret during the pairing handshake
 * 3. When the keyholder sends a command, they sign it with:
 *    HMAC-SHA256("{pairing_id}:{command_type}:{nonce}", hmac_secret)
 * 4. A unique nonce (UUID) prevents replay attacks - each nonce can only be used once
 *
 * Usage:
 * ```kotlin
 * val signer = CommandSigner()
 * val (nonce, hmac) = signer.sign(pairingId = 42, commandType = "unlock", hmacSecret = "abc123...")
 * val request = SendCommandRequest(
 *     pairingId = 42,
 *     commandType = "unlock",
 *     nonce = nonce,
 *     hmac = hmac,
 * )
 * ```
 */
package com.badcheese.keymaster.api

import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class CommandSigner {

    data class SignedCommand(
        val nonce: String,
        val hmac: String,
    )

    /**
     * Generate a nonce and compute the HMAC signature for a command.
     *
     * @param pairingId   The ID of the pairing this command targets
     * @param commandType The command type (e.g., "unlock", "lock", "shock")
     * @param hmacSecret  The 64-char hex HMAC secret from the pairing
     * @return A [SignedCommand] containing the nonce and HMAC to include in the request
     */
    fun sign(pairingId: Int, commandType: String, hmacSecret: String): SignedCommand {
        val nonce = UUID.randomUUID().toString()
        val message = "$pairingId:$commandType:$nonce"
        val hmac = hmacSha256(message, hmacSecret)
        return SignedCommand(nonce, hmac)
    }

    /**
     * Compute HMAC-SHA256 and return as lowercase hex string.
     */
    private fun hmacSha256(message: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
