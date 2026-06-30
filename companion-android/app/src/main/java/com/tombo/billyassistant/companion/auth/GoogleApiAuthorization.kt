package com.tombo.billyassistant.companion.auth

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

class GoogleApiAuthorization(private val activity: Activity) {
    private val authorizationClient = Identity.getAuthorizationClient(activity)

    fun requestAccess(
        scopes: Collection<String>,
        onResult: (GoogleApiAuthorizationResult) -> Unit,
    ) {
        val normalizedScopes = scopes.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (normalizedScopes.isEmpty()) {
            onResult(GoogleApiAuthorizationResult.Failed("No Google OAuth scopes were requested."))
            return
        }

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(normalizedScopes.map(::Scope))
            .build()

        authorizationClient.authorize(request)
            .addOnSuccessListener { result -> onResult(result.toGoogleApiAuthorizationResult()) }
            .addOnFailureListener { error ->
                onResult(GoogleApiAuthorizationResult.Failed(error.shortMessage()))
            }
    }

    fun completeAccessRequest(data: Intent?): GoogleApiAuthorizationResult {
        if (data == null) {
            return GoogleApiAuthorizationResult.Failed("No Google authorization result was returned.")
        }
        return try {
            authorizationClient.getAuthorizationResultFromIntent(data).toGoogleApiAuthorizationResult()
        } catch (e: ApiException) {
            GoogleApiAuthorizationResult.Failed(e.shortMessage())
        } catch (e: RuntimeException) {
            GoogleApiAuthorizationResult.Failed(e.shortMessage())
        }
    }

    private fun AuthorizationResult.toGoogleApiAuthorizationResult(): GoogleApiAuthorizationResult {
        if (hasResolution()) {
            val pendingIntent = pendingIntent
                ?: return GoogleApiAuthorizationResult.Failed("Google authorization returned no consent UI.")
            return GoogleApiAuthorizationResult.NeedsUserConsent(pendingIntent)
        }

        return GoogleApiAuthorizationResult.Authorized(
            accessToken = accessToken,
            grantedScopes = grantedScopes.orEmpty(),
        )
    }
}

sealed interface GoogleApiAuthorizationResult {
    data class Authorized(
        val accessToken: String?,
        val grantedScopes: List<String>,
    ) : GoogleApiAuthorizationResult

    data class NeedsUserConsent(val pendingIntent: PendingIntent) : GoogleApiAuthorizationResult
    data class Failed(val reason: String) : GoogleApiAuthorizationResult
}

private fun Throwable.shortMessage(): String {
    return message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
