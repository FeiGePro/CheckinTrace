package com.signin.assistant.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
    fun clear()
}

/**
 * 使用 Android Keystore 中不可导出的 AES 密钥加密凭证。SharedPreferences 只保存
 * IV 与密文；卸载应用或清除数据后密钥与凭证会一起消失。
 */
class EncryptedCredentialStore(context: Context) : CredentialStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(key, payload).commit()) { "保存加密凭证失败" }
    }

    override fun get(key: String): String? {
        val payload = preferences.getString(key, null) ?: return null
        val parts = payload.split(':', limit = 2)
        if (parts.size != 2) return null
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "encrypted_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "sign_in_assistant_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

