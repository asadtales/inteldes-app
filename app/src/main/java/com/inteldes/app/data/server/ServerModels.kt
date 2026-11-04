package com.inteldes.app.data.server

import java.io.IOException

data class ServerUser(val id: String, val email: String, val name: String)
/** [kind]: `team` (shared archive) or `personal` (one user's separate archive). Only `/auth/me` carries it; the login answer does not. */
data class ServerOrg(val id: String, val name: String, val kind: String? = null)

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

/** Used out of limit. [limit] null = NO limit (a package without that number); it must stay nullable or Gson turns it into 0. */
data class AiUsage(val used: Int = 0, val limit: Int? = null) {
    /** Whole units left, or null when unlimited. */
    val left: Int? get() = limit?.let { (it - used).coerceAtLeast(0) }
}

/** [id] null = the system's default package. */
data class AiPlan(val id: String? = null, val name: String? = null)

data class AiPlatformLimit(val jobs: Int? = null, val asks: Int? = null, val minutes: Int? = null)

/**
 * Where this account's AI key comes from and what is left of it (GET /v1/settings, `aiAccess`).
 * [source] "platform" = the IntelDes key under a package (calendar-period quota, [resetsAt] = when the period turns);
 * "own" = the account's own key, no package, only a rolling 24-hour guard on [jobs] and [asks].
 */
data class AiAccess(
    val source: String = "own", val platformAvailable: Boolean = false, val plan: AiPlan? = null,
    val jobs: AiUsage = AiUsage(), val asks: AiUsage = AiUsage(), val minutes: AiUsage? = null,
    val resetsAt: Long? = null, val platformLimit: AiPlatformLimit? = null,
)

/** Which AI keys the server can actually use for this account ({deepgram, gemini, claude} readiness). */
data class AiReady(val deepgram: Boolean = false, val gemini: Boolean = false, val claude: Boolean = false, val openrouter: Boolean = false)

/** The model chosen for one provider: [normal], and [economy] for the saving mode (blank = the normal one / the default). */
data class AiTier(val normal: String? = null, val economy: String? = null)
data class AiModelChoice(val gemini: AiTier? = null, val claude: AiTier? = null, val openrouter: AiTier? = null)
data class AiCatalogEntry(val id: String = "", val label: String = "")
data class AiCatalog(val gemini: List<AiCatalogEntry>? = null, val claude: List<AiCatalogEntry>? = null, val openrouter: List<AiCatalogEntry>? = null)

/** The model that will really be used and where it comes from: "organisasi", "platform" or "env" (null = none chosen yet). */
data class AiEffective(val model: String? = null, val source: String? = null)
data class AiEffectivePair(val normal: AiEffective? = null, val economy: AiEffective? = null)

/** GET /v1/ai-models. [canChoose] is false while the IntelDes key is used: then the superadmin decides the models. */
data class AiModelsView(
    val keySource: String = "own", val canChoose: Boolean = false, val choice: AiModelChoice? = null,
    val platformDefaults: AiModelChoice? = null, val catalog: AiCatalog? = null, val effective: Map<String, AiEffectivePair>? = null,
)

/** Answer of the one-request test of a model (always HTTP 200; the verdict is in [ok] and [message]). */
data class AiCheck(val ok: Boolean = false, val message: String = "", val ms: Int? = null)

data class ServerSettingsView(val aiAccess: AiAccess? = null, val ai: AiReady? = null)

data class MeResult(val user: ServerUser, val org: ServerOrg, val role: String, val deviceId: String, val superadmin: Boolean = false)

/** What the web browser behind a QR looks like, shown on the approval screen. */
data class PairInspect(val kind: String, val requesterIp: String, val requesterUa: String, val createdAt: Long, val expiresAt: Long)

/**
 * An error answer from the server (`{ "error": { code, message } }`). [message] is already Indonesian
 * and fit to show to the user; [code] (e.g. `invalid_credentials`, `pair_expired`) is for logic.
 */
class ServerException(val status: Int, val code: String?, message: String) : IOException(message)

/** The signed-in account as the UI shows it (tokens are never part of this). */
data class Session(
    val name: String, val email: String, val orgName: String, val role: String, val deviceId: String,
    val orgKind: String = "team", val superadmin: Boolean = false,
) {
    val isPersonal: Boolean get() = orgKind == "personal"

    val roleLabel: String
        get() = when (role) {
            "admin" -> "Admin"
            "operator" -> "Operator"
            "pembaca" -> "Pembaca"
            else -> role
        }
}
