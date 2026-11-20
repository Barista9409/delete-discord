package com.example.discordcleaner.data

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
 * Discordトークンの保管庫。
 * - Android Keystore の AES-256 鍵(端末外に取り出せない)で AES-GCM 暗号化して SharedPreferences に保存
 * - allowBackup=false なのでバックアップにも載らない
 * - トークンは discord.com への通信以外に使わない。ログ・例外メッセージに含めないこと
 */
class TokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("token_vault", Context.MODE_PRIVATE)

    fun put(accountId: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + ct
        prefs.edit().putString(accountId, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(accountId: String): String? {
        val b64 = prefs.getString(accountId, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            String(cipher.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null // 鍵が失われた等。再ログインで復旧する
        }
    }

    fun remove(accountId: String) = prefs.edit().remove(accountId).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val ALIAS = "dc_token_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
