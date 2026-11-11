package io.github.archonw.starswipe.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface Secrets {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
}

/** Geheimnisse AES/GCM-verschlüsselt mit einem Schlüssel im Android Keystore. */
class SecretStore(context: Context) : Secrets {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    override fun put(key: String, value: String) {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    override fun get(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
            String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    override fun remove(key: String) { prefs.edit().remove(key).apply() }

    private companion object {
        const val ALIAS = "starswipe-secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
