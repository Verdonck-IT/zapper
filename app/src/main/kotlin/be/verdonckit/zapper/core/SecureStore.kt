package be.verdonckit.zapper.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import be.verdonckit.zapper.api.Credentials
import be.verdonckit.zapper.api.KeyValueStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Versleutelde opslag op basis van een AES-sleutel in de Android Keystore.
 * De sleutel verlaat de secure hardware nooit; in SharedPreferences staat enkel ciphertext.
 */
class SecureStore(context: Context, private val name: String) {

    private val prefs = context.getSharedPreferences("secure_$name", Context.MODE_PRIVATE)
    private val alias = "zapper.$name"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    fun get(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun put(key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun remove(key: String) = prefs.edit().remove(key).apply()

    @Synchronized
    fun removePrefix(prefix: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    /** Afgeschermde KeyValueStore voor één provider. */
    fun namespace(prefix: String): KeyValueStore = object : KeyValueStore {
        override fun get(key: String) = this@SecureStore.get("$prefix.$key")
        override fun put(key: String, value: String) = this@SecureStore.put("$prefix.$key", value)
        override fun remove(key: String) = this@SecureStore.remove("$prefix.$key")
        override fun clear() = removePrefix("$prefix.")
    }
}

class CredentialStore(private val store: SecureStore) {
    fun get(providerId: String): Credentials? {
        val user = store.get("$providerId.user") ?: return null
        val pass = store.get("$providerId.pass") ?: return null
        return Credentials(user, pass)
    }

    fun put(providerId: String, credentials: Credentials) {
        store.put("$providerId.user", credentials.username)
        store.put("$providerId.pass", credentials.password)
    }

    fun clear(providerId: String) {
        store.remove("$providerId.user")
        store.remove("$providerId.pass")
    }
}
