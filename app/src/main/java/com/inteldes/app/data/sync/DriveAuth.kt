package com.inteldes.app.data.sync

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Google sign-in for Drive via the Identity Authorization API. Each device/user authorises with
 * their own Google account; the app only asks for `drive.file` (files this app created).
 *
 * The first authorisation needs a consent screen ([request] + the PendingIntent in the result,
 * launched from the Settings screen). After that, [silentToken] returns an access token without UI,
 * which is what background sync uses.
 */
class DriveAuth(private val context: Context) {
    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE))).build()

    /** Null when the user still has to grant access (needs an Activity), otherwise a fresh token. */
    suspend fun silentToken(): String? {
        val result = Identity.getAuthorizationClient(context).authorize(request()).await()
        return if (result.hasResolution()) null else result.accessToken
    }

    /** For [DriveClient]: a token or an [AuthException] telling the user to reconnect. */
    suspend fun tokenOrThrow(): String =
        silentToken() ?: throw AuthException("Akses Google Drive belum diberikan. Hubungkan di Pengaturan.")

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
