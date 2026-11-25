package ir.irangold.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ir.irangold.core.KeyValueStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Imported configs (they contain server addresses and keys) are encrypted with an AES-256-GCM key that lives in the
 * Android Keystore and never leaves it. Nothing secret is stored in plain text, logged, or shipped inside the APK.
 */
class SecretStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, plain: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val blob = c.iv + ct
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val b64 = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun remove(name: String) { prefs.edit().remove(name).apply() }

    companion object { private const val ALIAS = "irangold.secrets.v1" }
}

/** Non-secret settings + the [KeyValueStore] used by core's network memory. */
class Prefs(context: Context) : KeyValueStore {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var killSwitch: Boolean
        get() = p.getBoolean("killSwitch", true)
        set(v) { p.edit().putBoolean("killSwitch", v).apply() }

    /** auto | xray | tor */
    var engineMode: String
        get() = p.getString("engineMode", "auto") ?: "auto"
        set(v) { p.edit().putString("engineMode", v).apply() }

    override fun get(key: String): String? = p.getString("kv.$key", null)
    override fun put(key: String, value: String) { p.edit().putString("kv.$key", value).apply() }
}
