/**
 * KeyMaster API - Retrofit Interface
 *
 * Defines all HTTP endpoints for the KeyMaster self-hosted server.
 * Use with Retrofit to create a type-safe API client.
 *
 * Setup example:
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor { chain ->
 *         chain.proceed(chain.request().newBuilder()
 *             .header("Authorization", "Bearer $jwtToken")
 *             .build())
 *     }
 *     .build()
 *
 * val api = Retrofit.Builder()
 *     .baseUrl("https://badcheese.com/keymaster/api/")
 *     .client(client)
 *     .addConverterFactory(GsonConverterFactory.create())
 *     .build()
 *     .create(KeyMasterApi::class.java)
 * ```
 */
package com.badcheese.keymaster.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface KeyMasterApi {

    // ---- Authentication (no JWT required) ----

    /** Register a new user. Returns a JWT token valid for 30 days. */
    @POST("register")
    suspend fun register(@Body request: AuthRequest): AuthResponse

    /** Log in an existing user. Returns a JWT token valid for 30 days. */
    @POST("login")
    suspend fun login(@Body request: AuthRequest): AuthResponse

    // ---- Device Management (JWT required) ----

    /** Register a BLE device on the server (called by wearer). */
    @POST("device/register")
    suspend fun registerDevice(@Body request: DeviceRegisterRequest): DeviceRegisterResponse

    // ---- Pairing (JWT required) ----

    /** Generate a one-time 8-char pairing code + HMAC secret (called by wearer). */
    @POST("pairing/create-code")
    suspend fun createPairingCode(@Body request: CreateCodeRequest): CreateCodeResponse

    /** Accept a pairing code to become the keyholder (called by keyholder). */
    @POST("pairing/accept")
    suspend fun acceptPairingCode(@Body request: AcceptCodeRequest): AcceptCodeResponse

    /** List all active pairings for the current user (as keyholder or wearer). */
    @GET("pairing/list")
    suspend fun listPairings(): PairingListResponse

    /** Revoke an active pairing (either party can revoke). */
    @POST("pairing/revoke")
    suspend fun revokePairing(@Body request: RevokeRequest): SuccessResponse

    // ---- Commands (JWT required) ----

    /**
     * Send a command to a paired device (called by keyholder).
     * Requires HMAC signature and unique nonce for security.
     */
    @POST("command/send")
    suspend fun sendCommand(@Body request: SendCommandRequest): SendCommandResponse

    /** Poll for pending commands (called by wearer's background service). */
    @GET("command/poll")
    suspend fun pollCommands(): PollCommandsResponse

    /** Report the result of executing a command (called by wearer). */
    @POST("command/result")
    suspend fun reportCommandResult(@Body request: CommandResultRequest): SuccessResponse

    // ---- Device Status (JWT required) ----

    /** Push device status (battery, lock state) to the server (called by wearer). */
    @POST("status/update")
    suspend fun updateStatus(@Body request: StatusUpdateRequest): SuccessResponse

    /** Get device status and recent command history for a pairing. */
    @GET("status/{pairingId}")
    suspend fun getStatus(@Path("pairingId") pairingId: Int): DeviceStatusResponse
}
