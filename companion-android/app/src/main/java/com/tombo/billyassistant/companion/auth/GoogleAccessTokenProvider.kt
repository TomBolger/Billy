package com.tombo.billyassistant.companion.auth

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

class GoogleAccessTokenProvider(
    private val context: Context,
    private val authStore: GoogleAuthStore = GoogleAuthStore(context),
) {
    fun getAccessToken(requiredScopes: Collection<String>): GoogleAccessTokenResult {
        if (requiredScopes.isEmpty()) {
            return GoogleAccessTokenResult.Failed("No Google API scopes were requested.")
        }

        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(requiredScopes.map { Scope(it) })
                .build()
            val result = Tasks.await(
                Identity.getAuthorizationClient(context).authorize(request),
                TOKEN_WAIT_SECONDS,
                TimeUnit.SECONDS,
            )
            if (result.hasResolution()) {
                return GoogleAccessTokenResult.NeedsUserGrant(requiredScopes.toList())
            }
            val token = result.accessToken
            if (token.isNullOrBlank()) {
                return GoogleAccessTokenResult.NeedsUserGrant(requiredScopes.toList())
            }
            authStore.saveGrant(requiredScopes, token)
            GoogleAccessTokenResult.Authorized(token)
        } catch (e: Exception) {
            if (!authStore.hasScopes(requiredScopes)) {
                GoogleAccessTokenResult.NeedsUserGrant(requiredScopes.toList())
            } else {
                GoogleAccessTokenResult.Failed("Google authorization failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        private const val TOKEN_WAIT_SECONDS = 10L
    }
}

sealed interface GoogleAccessTokenResult {
    data class Authorized(val accessToken: String) : GoogleAccessTokenResult
    data class NeedsUserGrant(val scopes: List<String>) : GoogleAccessTokenResult
    data class Failed(val reason: String) : GoogleAccessTokenResult
}
