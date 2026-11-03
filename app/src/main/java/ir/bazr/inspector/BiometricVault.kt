package ir.bazr.inspector

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.util.concurrent.Executor
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class BiometricVault(
    private val activity: FragmentActivity,
    private val executor: Executor,
    private val store: SecureStore
) {
    private val alias = "bazr_biometric_aes_v1"
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun isAvailable(): Boolean {
        val result = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun key(): SecretKey {
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            b.setUserAuthenticationValidityDurationSeconds(-1)
        }
        kg.init(b.build())
        return kg.generateKey()
    }

    private fun info(title: String, subtitle: String) = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText("انصراف")
        .build()

    fun enable(token: String, onDone: () -> Unit, onError: (String) -> Unit) {
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher ?: return onError("رمزنگاری بیومتریک در دسترس نیست")
                    runCatching {
                        val encrypted = c.doFinal(token.toByteArray(Charsets.UTF_8))
                        store.setBiometricPayload(encrypted, c.iv)
                        onDone()
                    }.onFailure { onError(it.message ?: "فعال‌سازی ناموفق بود") }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errString.toString())
            })
            prompt.authenticate(info("فعال‌سازی ورود بیومتریک", "برای حفاظت از ورود بازرس احراز هویت کنید"), BiometricPrompt.CryptoObject(cipher))
        }.onFailure { onError(it.message ?: "بیومتریک در دسترس نیست") }
    }

    fun unlock(onToken: (String) -> Unit, onError: (String) -> Unit) {
        val encrypted = store.biometricCiphertext() ?: return onError("اطلاعات بیومتریک موجود نیست")
        val iv = store.biometricIv() ?: return onError("اطلاعات بیومتریک موجود نیست")
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher ?: return onError("بازکردن امن اطلاعات ممکن نشد")
                    runCatching { String(c.doFinal(encrypted), Charsets.UTF_8) }
                        .onSuccess(onToken)
                        .onFailure { onError("کلید بیومتریک تغییر کرده است؛ با رمز وارد شوید") }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errString.toString())
            })
            prompt.authenticate(info("ورود با اثر انگشت", "هویت خود را تأیید کنید"), BiometricPrompt.CryptoObject(cipher))
        }.onFailure {
            store.disableBiometric()
            onError("کلید بیومتریک معتبر نیست؛ با نام کاربری و رمز عبور وارد شوید")
        }
    }
}
