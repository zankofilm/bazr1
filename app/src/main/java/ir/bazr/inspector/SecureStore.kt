package ir.bazr.inspector

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("bazr_inspector_secure", Context.MODE_PRIVATE)
    private val standardAlias = "bazr_standard_aes_v1"
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    var apiBaseUrl: String
        get() = prefs.getString("api_base", "") ?: ""
        set(v) { prefs.edit().putString("api_base", normalizeBase(v)).apply() }

    val deviceId: String
        get() {
            val old = prefs.getString("device_id", null)
            if (!old.isNullOrBlank()) return old
            val id = "android-${UUID.randomUUID()}"
            prefs.edit().putString("device_id", id).apply()
            return id
        }

    var cachedSyncJson: String?
        get() = prefs.getString("sync_cache", null)
        set(v) { prefs.edit().putString("sync_cache", v).apply() }

    var profileJson: String?
        get() = prefs.getString("profile", null)
        set(v) { prefs.edit().putString("profile", v).apply() }

    val biometricEnabled: Boolean get() = prefs.getBoolean("bio_enabled", false)
    val hasBiometricPayload: Boolean get() = prefs.contains("bio_cipher") && prefs.contains("bio_iv")

    fun setBiometricPayload(ciphertext: ByteArray, iv: ByteArray) {
        prefs.edit()
            .putString("bio_cipher", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString("bio_iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .putBoolean("bio_enabled", true)
            .remove("device_token")
            .apply()
    }

    fun biometricCiphertext(): ByteArray? = prefs.getString("bio_cipher", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    fun biometricIv(): ByteArray? = prefs.getString("bio_iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun disableBiometric() {
        prefs.edit().putBoolean("bio_enabled", false).remove("bio_cipher").remove("bio_iv").apply()
    }

    private fun standardKey(): SecretKey {
        (ks.getKey(standardAlias, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(standardAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    fun saveDeviceToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, standardKey())
        val out = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv + out, Base64.NO_WRAP)
        prefs.edit().putString("device_token", packed).putBoolean("bio_enabled", false).apply()
    }

    fun loadDeviceToken(): String? {
        val packed = prefs.getString("device_token", null) ?: return null
        return runCatching {
            val all = Base64.decode(packed, Base64.NO_WRAP)
            if (all.size <= 12) return null
            val iv = all.copyOfRange(0, 12)
            val data = all.copyOfRange(12, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, standardKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }

    fun clearAuth() {
        prefs.edit().remove("device_token").remove("bio_cipher").remove("bio_iv").putBoolean("bio_enabled", false).remove("profile").apply()
    }

    fun saveDraft(missionKey: String, draftJson: String) = prefs.edit().putString("draft_$missionKey", draftJson).apply()
    fun loadDraft(missionKey: String): String? = prefs.getString("draft_$missionKey", null)
    fun clearDraft(missionKey: String) = prefs.edit().remove("draft_$missionKey").apply()

    companion object {
        fun normalizeBase(input: String): String {
            var s = input.trim().trimEnd('/')
            if (s.isBlank()) return ""
            if (!s.startsWith("https://")) return s
            if (!s.endsWith("/api")) s += "/api"
            return "$s/"
        }
    }
}
