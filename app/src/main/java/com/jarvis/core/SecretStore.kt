package com.jarvis.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * User-entered API keys (ElevenLabs, traffic providers), encrypted with an AES-GCM key that lives in the Android
 * Keystore. Keys never go into BuildConfig, logs, or Git. Honest limit: a key used directly by an app on the phone
 * can still be extracted by someone who controls the device, so use keys you can revoke and restrict.
 */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun get(name: String): String? = try {
        val stored = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8).takeIf(String::isNotBlank)
    } catch (_: Exception) {
        null // a missing or invalidated keystore key means the secret must be entered again
    }

    fun put(name: String, value: String?) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun has(name: String): Boolean = get(name) != null

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val ELEVENLABS_API_KEY = "elevenlabs_api_key"
        const val GOOGLE_ROUTES_API_KEY = "google_routes_api_key"
        const val MAPBOX_ACCESS_TOKEN = "mapbox_access_token"
        private const val ALIAS = "jarvis_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
