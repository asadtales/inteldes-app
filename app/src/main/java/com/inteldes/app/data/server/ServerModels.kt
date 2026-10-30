package com.inteldes.app.data.server

import java.io.IOException

data class ServerUser(val id: String, val email: String, val name: String)
data class ServerOrg(val id: String, val name: String)

/** Response of `POST /v1/auth/login` and of `POST /v1/auth/pair/claim` (add_phone). */
data class LoginResult(
    val user: ServerUser,
    val org: ServerOrg,
    val role: String,
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Int,
)

/** Response of `POST /v1/auth/refresh`: the refresh token is a NEW one — the old one is dead. */
data class RefreshResult(val deviceId: String, val accessToken: String, val refreshToken: String, val expiresInSec: Int)

data class MeResult(val user: ServerUser, val org: ServerOrg, val role: String, val deviceId: String)

/** What the web browser behind a QR looks like, shown on the approval screen. */
data class PairInspect(val kind: String, val requesterIp: String, val requesterUa: String, val createdAt: Long, val expiresAt: Long)

/**
 * An error answer from the server (`{ "error": { code, message } }`). [message] is already Indonesian
 * and fit to show to the user; [code] (e.g. `invalid_credentials`, `pair_expired`) is for logic.
 */
class ServerException(val status: Int, val code: String?, message: String) : IOException(message)

/** The signed-in account as the UI shows it (tokens are never part of this). */
data class Session(val name: String, val email: String, val orgName: String, val role: String, val deviceId: String) {
    val roleLabel: String
        get() = when (role) {
            "admin" -> "Admin"
            "operator" -> "Operator"
            "pembaca" -> "Pembaca"
            else -> role
        }
}
