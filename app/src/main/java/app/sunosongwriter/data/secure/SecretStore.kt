package app.sunosongwriter.data.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts and decrypts short strings. */
interface StringCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String
}

/**
 * AES-256-GCM with a key held in the Android Keystore (spec 5.1). The key never leaves the Keystore,
 * so ciphertext restored onto another device can't be decrypted.
 */
class KeystoreCipher(private val alias: String = "backend-api-keys") : StringCipher {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String {
        val sealed = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = sealed.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        return String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * API keys, stored only as ciphertext in their own DataStore file (`datastore/secrets.preferences_pb`),
 * which the backup rules exclude. Keys are never logged or exported.
 */
class SecretStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: StringCipher,
) {
    suspend fun put(name: String, value: String) {
        val encrypted = cipher.encrypt(value)
        dataStore.edit { it[stringPreferencesKey(name)] = encrypted }
    }

    /** Null when missing, or when it can't be decrypted (e.g. restored from another device). */
    suspend fun get(name: String): String? {
        val encrypted = dataStore.data.first()[stringPreferencesKey(name)] ?: return null
        return runCatching { cipher.decrypt(encrypted) }.getOrNull()
    }

    suspend fun remove(name: String) {
        dataStore.edit { it.remove(stringPreferencesKey(name)) }
    }

    companion object {
        const val FILE_NAME = "secrets"

        fun backendKey(backendId: Long) = "backend:$backendId"

        /** What lists show instead of the key: its last four characters, if the key is long enough to spare them. */
        fun hintFor(key: String): String? = key.trim().takeIf { it.length >= 16 }?.takeLast(4)
    }
}
