package com.example.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

data class OAuthTokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresInSeconds: Long?,
    val scope: String
)

/**
 * Implements Hugging Face OAuth 2.0 + PKCE (S256) native mobile sign-in flow:
 * - Authorization: https://huggingface.co/oauth/authorize
 * - Token exchange: https://huggingface.co/oauth/token (PKCE code_verifier, no client_secret needed)
 * - Callback URI: spacedeck://oauth/callback
 */
class HfOAuthManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        const val REDIRECT_URI = "spacedeck://oauth/callback"
        const val AUTH_ENDPOINT = "https://huggingface.co/oauth/authorize"
        const val TOKEN_ENDPOINT = "https://huggingface.co/oauth/token"
        const val DEFAULT_SCOPES = "openid profile read-repos inference-api"
        private const val PREFS_NAME = "spacedeck_oauth_pkce_state"
        private const val KEY_VERIFIER = "code_verifier"
        private const val KEY_STATE = "csrf_state"
        private const val KEY_CLIENT_ID = "pending_client_id"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun buildAuthorizeIntent(clientId: String, scopes: String = DEFAULT_SCOPES): Intent {
        val cleanClientId = clientId.trim()
        require(cleanClientId.isNotEmpty()) { "Enter your Hugging Face OAuth App Client ID first." }

        val codeVerifier = generateRandomBase64Url(48)
        val codeChallenge = sha256Base64Url(codeVerifier)
        val state = generateRandomBase64Url(24)

        prefs.edit()
            .putString(KEY_VERIFIER, codeVerifier)
            .putString(KEY_STATE, state)
            .putString(KEY_CLIENT_ID, cleanClientId)
            .apply()

        val authUri = Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", cleanClientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", scopes)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        return Intent(Intent.ACTION_VIEW, authUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    suspend fun handleCallbackUri(uri: Uri): Result<OAuthTokenResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val error = uri.getQueryParameter("error")
            if (!error.isNullOrBlank()) {
                val desc = uri.getQueryParameter("error_description").orEmpty()
                clearPendingPkce()
                throw IllegalStateException("Hugging Face OAuth declined: $error ${desc.take(120)}".trim())
            }

            val code = uri.getQueryParameter("code")
                ?: throw IllegalStateException("OAuth callback missing authorization code.")
            val returnedState = uri.getQueryParameter("state")
                ?: throw IllegalStateException("OAuth callback missing CSRF state.")

            val expectedState = prefs.getString(KEY_STATE, null)
            val codeVerifier = prefs.getString(KEY_VERIFIER, null)
            val clientId = prefs.getString(KEY_CLIENT_ID, null)
            clearPendingPkce()

            if (expectedState.isNullOrBlank() || returnedState != expectedState) {
                throw SecurityException("OAuth state mismatch. Please retry Sign in with Hugging Face.")
            }
            if (codeVerifier.isNullOrBlank() || clientId.isNullOrBlank()) {
                throw IllegalStateException("OAuth PKCE session expired. Please retry sign-in.")
            }

            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", clientId)
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", codeVerifier)
                .build()

            val req = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .header("Accept", "application/json")
                .header("User-Agent", "SpaceDeck-Android/1.0")
                .post(form)
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("OAuth token exchange failed (HTTP ${resp.code}): ${TokenRedactor.sanitize(body.take(180))}")
                }
                val json = JSONObject(body)
                val accessToken = json.optString("access_token", "").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("OAuth token response missing access_token.")
                val expiresIn = if (json.has("expires_in") && !json.isNull("expires_in")) json.optLong("expires_in") else null
                OAuthTokenResponse(
                    accessToken = accessToken,
                    tokenType = json.optString("token_type", "Bearer"),
                    expiresInSeconds = expiresIn,
                    scope = json.optString("scope", "")
                )
            }
        }
    }

    private fun clearPendingPkce() {
        prefs.edit().clear().apply()
    }

    private fun generateRandomBase64Url(numBytes: Int): String {
        val bytes = ByteArray(numBytes)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
