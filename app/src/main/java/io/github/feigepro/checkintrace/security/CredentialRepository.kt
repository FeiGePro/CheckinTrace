package io.github.feigepro.checkintrace.security

import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoCredentialBundle
import io.github.feigepro.checkintrace.provider.mihoyo.MihoyoDeviceIdentity
import io.github.feigepro.checkintrace.provider.skland.SklandCredentialBundle
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

    fun saveMihoyoDeviceIdentity(identity: MihoyoDeviceIdentity) {
        store.put(MIHOYO_DEVICE_KEY, json.encodeToString(identity))
    }

    fun loadMihoyoDeviceIdentity(): MihoyoDeviceIdentity? = store.get(MIHOYO_DEVICE_KEY)?.let { encoded ->
        runCatching { json.decodeFromString<MihoyoDeviceIdentity>(encoded) }.getOrNull()
    }

    fun saveSkland(accountKey: String, credential: SklandCredentialBundle) {
        store.put("skland:$accountKey", json.encodeToString(credential))
    }

    fun loadSkland(accountKey: String): SklandCredentialBundle? = store.get("skland:$accountKey")?.let { encoded ->
        runCatching { json.decodeFromString<SklandCredentialBundle>(encoded) }.getOrElse {
            // 兼容 v0.2.4 及更早版本只保存原始 access token 的数据。
            encoded.takeIf(String::isNotBlank)?.let(::SklandCredentialBundle)
        }
    }

    fun saveSklandToken(accountKey: String, token: String) {
        saveSkland(accountKey, SklandCredentialBundle(accessToken = token))
    }

    fun loadSklandToken(accountKey: String): String? = loadSkland(accountKey)?.accessToken

    fun removeMihoyo(accountKey: String) = store.remove("mihoyo:$accountKey")

    fun removeSkland(accountKey: String) = store.remove("skland:$accountKey")

    private companion object {
        const val MIHOYO_DEVICE_KEY = "mihoyo:device_identity"
    }
}
