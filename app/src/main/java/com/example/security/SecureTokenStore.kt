package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AccountInfo
import com.example.model.SpaceAuthMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.secureAuthDataStore by preferencesDataStore(name = "spacedeck_secure_vault")

object AllowedHosts {
    fun isTrustedForCredentials(urlString: String): Boolean {
        return try {
            val uri = URI(urlString)
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            host == "huggingface.co" ||
                host.endsWith(".huggingface.co") ||
                host == "hf.co" ||
                host.endsWith(".hf.space")
        } catch (_: Exception) {
            false
        }
    }

    fun isAllowedNetworkTarget(urlString: String): Boolean {
        return try {
            val uri = URI(urlString)
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            // Block loopback, private, and link-local ranges
            if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" ||
                host.startsWith("10.") || host.startsWith("192.168.") ||
                host.startsWith("169.254.") || host.startsWith("172.") ||
                host == "::1" || host.endsWith(".local")
            ) {
                return false
            }
            isTrustedForCredentials(urlString) ||
                host.endsWith(".cloudfront.net") ||
                host.endsWith(".amazonaws.com") ||
                host.endsWith(".r2.cloudflarestorage.com")
        } catch (_: Exception) {
            false
        }
    }
}

object TokenRedactor {
    private val hfTokenRegex = Regex("""hf_[A-Za-z0-9]{10,}""")
    private val bearerRegex = Regex("""(?i)(Bearer\s+)[A-Za-z0-9._\-]+""")

    fun sanitize(input: String): String {
        return input
            .replace(hfTokenRegex, "hf_[REDACTED]")
            .replace(bearerRegex, "$1[REDACTED]")
    }
}

class SecureTokenStore(private val context: Context) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "SpaceDeckHfTokenAesKeyV1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        private val KEY_DEFAULT_TOKEN_ENC = stringPreferencesKey("default_token_enc")
        private val KEY_ANONYMOUS_MODE = booleanPreferencesKey("anonymous_mode")
        private val KEY_ACCOUNT_INFO = stringPreferencesKey("account_info_json")
        private val KEY_PER_SPACE_AUTH = stringPreferencesKey("per_space_auth_enc")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? {
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < 2) return null
            val ivLen = combined[0].toInt() and 0xFF
            if (combined.size <= 1 + ivLen) return null
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    val isAnonymousMode: Flow<Boolean> = context.secureAuthDataStore.data.map { prefs ->
        prefs[KEY_ANONYMOUS_MODE] ?: false
    }

    val hasDefaultToken: Flow<Boolean> = context.secureAuthDataStore.data.map { prefs ->
        !prefs[KEY_DEFAULT_TOKEN_ENC].isNullOrBlank()
    }

    val accountInfoFlow: Flow<AccountInfo?> = context.secureAuthDataStore.data.map { prefs ->
        prefs[KEY_ACCOUNT_INFO]?.let { decodeAccountInfo(it) }
    }

    suspend fun saveDefaultToken(rawToken: String) {
        val cleaned = rawToken.trim()
        if (cleaned.isEmpty()) {
            clearDefaultToken()
            return
        }
        val encrypted = encrypt(cleaned)
        context.secureAuthDataStore.edit { prefs ->
            prefs[KEY_DEFAULT_TOKEN_ENC] = encrypted
            prefs[KEY_ANONYMOUS_MODE] = false
        }
    }

    suspend fun getDefaultToken(): String? {
        val prefs = context.secureAuthDataStore.data.first()
        val enc = prefs[KEY_DEFAULT_TOKEN_ENC] ?: return null
        return decrypt(enc)
    }

    suspend fun clearDefaultToken() {
        context.secureAuthDataStore.edit { prefs ->
            prefs.remove(KEY_DEFAULT_TOKEN_ENC)
            prefs.remove(KEY_ACCOUNT_INFO)
        }
    }

    suspend fun setAnonymousMode(enabled: Boolean) {
        context.secureAuthDataStore.edit { prefs ->
            prefs[KEY_ANONYMOUS_MODE] = enabled
        }
    }

    suspend fun saveAccountInfo(info: AccountInfo?) {
        context.secureAuthDataStore.edit { prefs ->
            if (info == null) {
                prefs.remove(KEY_ACCOUNT_INFO)
            } else {
                prefs[KEY_ACCOUNT_INFO] = encodeAccountInfo(info)
            }
        }
    }

    suspend fun getSpaceAuthMode(spaceId: String): SpaceAuthMode {
        val map = loadPerSpaceAuthMap()
        val entry = map.optJSONObject(spaceId) ?: return SpaceAuthMode.DEFAULT_ACCOUNT
        return runCatching { SpaceAuthMode.valueOf(entry.optString("mode", "DEFAULT_ACCOUNT")) }
            .getOrDefault(SpaceAuthMode.DEFAULT_ACCOUNT)
    }

    suspend fun setSpaceAuthPreference(spaceId: String, mode: SpaceAuthMode, customToken: String? = null) {
        val map = loadPerSpaceAuthMap()
        if (mode == SpaceAuthMode.DEFAULT_ACCOUNT) {
            map.remove(spaceId)
        } else {
            val obj = JSONObject()
            obj.put("mode", mode.name)
            if (mode == SpaceAuthMode.CUSTOM_TOKEN && !customToken.isNullOrBlank()) {
                obj.put("token", customToken.trim())
            }
            map.put(spaceId, obj)
        }
        val encrypted = encrypt(map.toString())
        context.secureAuthDataStore.edit { prefs ->
            prefs[KEY_PER_SPACE_AUTH] = encrypted
        }
    }

    suspend fun getAllSpaceAuthModes(): Map<String, SpaceAuthMode> {
        val map = loadPerSpaceAuthMap()
        val result = mutableMapOf<String, SpaceAuthMode>()
        val keys = map.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val entry = map.optJSONObject(k)
            val mode = runCatching {
                SpaceAuthMode.valueOf(entry?.optString("mode", "DEFAULT_ACCOUNT") ?: "DEFAULT_ACCOUNT")
            }.getOrDefault(SpaceAuthMode.DEFAULT_ACCOUNT)
            result[k] = mode
        }
        return result
    }

    suspend fun resolveTokenForSpace(spaceId: String?, targetUrl: String? = null): String? {
        if (targetUrl != null && !AllowedHosts.isTrustedForCredentials(targetUrl)) {
            return null
        }
        if (spaceId != null) {
            val map = loadPerSpaceAuthMap()
            val entry = map.optJSONObject(spaceId)
            if (entry != null) {
                val mode = runCatching { SpaceAuthMode.valueOf(entry.optString("mode")) }
                    .getOrDefault(SpaceAuthMode.DEFAULT_ACCOUNT)
                when (mode) {
                    SpaceAuthMode.ANONYMOUS -> return null
                    SpaceAuthMode.CUSTOM_TOKEN -> return entry.optString("token").takeIf { it.isNotBlank() }
                    SpaceAuthMode.DEFAULT_ACCOUNT -> Unit
                }
            }
        }
        val prefs = context.secureAuthDataStore.data.first()
        if (prefs[KEY_ANONYMOUS_MODE] == true) return null
        val enc = prefs[KEY_DEFAULT_TOKEN_ENC] ?: return null
        return decrypt(enc)
    }

    private suspend fun loadPerSpaceAuthMap(): JSONObject {
        val prefs = context.secureAuthDataStore.data.first()
        val enc = prefs[KEY_PER_SPACE_AUTH] ?: return JSONObject()
        val dec = decrypt(enc) ?: return JSONObject()
        return runCatching { JSONObject(dec) }.getOrElse { JSONObject() }
    }

    private fun encodeAccountInfo(info: AccountInfo): String {
        return JSONObject().apply {
            put("username", info.username)
            put("fullName", info.fullName)
            put("avatarUrl", info.avatarUrl)
            put("isPro", info.isPro)
            put("accountType", info.accountType)
            put("orgs", JSONArray(info.orgs))
            put("verifiedAtMs", info.verifiedAtMs)
        }.toString()
    }

    private fun decodeAccountInfo(json: String): AccountInfo? {
        return runCatching {
            val o = JSONObject(json)
            val orgsArr = o.optJSONArray("orgs") ?: JSONArray()
            val orgs = (0 until orgsArr.length()).map { orgsArr.optString(it) }.filter { it.isNotBlank() }
            AccountInfo(
                username = o.getString("username"),
                fullName = o.optString("fullName", ""),
                avatarUrl = o.optString("avatarUrl", ""),
                isPro = o.optBoolean("isPro", false),
                accountType = o.optString("accountType", "user"),
                orgs = orgs,
                verifiedAtMs = o.optLong("verifiedAtMs", 0L)
            )
        }.getOrNull()
    }
}
