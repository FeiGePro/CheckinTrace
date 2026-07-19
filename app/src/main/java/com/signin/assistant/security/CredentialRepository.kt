package com.signin.assistant.security

import com.signin.assistant.provider.mihoyo.MihoyoCredentialBundle
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CredentialRepository(
    private val store: CredentialStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun saveMihoyo(accountKey: String, credential: MihoyoCredentialBundle) {
        store.put("mihoyo:$accountKey", json.encodeToString(credential))
    }

    fun loadMihoyo(accountKey: String): MihoyoCredentialBundle? =
        store.get("mihoyo:$accountKey")?.let { encoded ->
            runCatching { json.decodeFromString<MihoyoCredentialBundle>(encoded) }.getOrNull()
        }

    fun saveSklandToken(accountKey: String, token: String) {
        store.put("skland:$accountKey", token)
    }

    fun loadSklandToken(accountKey: String): String? = store.get("skland:$accountKey")

    fun removeMihoyo(accountKey: String) = store.remove("mihoyo:$accountKey")

    fun removeSkland(accountKey: String) = store.remove("skland:$accountKey")
}
