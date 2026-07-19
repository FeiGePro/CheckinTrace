package io.github.feigepro.checkintrace.data

enum class ProviderType {
    MIHOYO,
    SKLAND,
}

data class GameDefinition(
    val id: String,
    val displayName: String,
    val provider: ProviderType,
    val appCode: String,
    val enabledByDefault: Boolean = false,
)

object GameCatalog {
    val builtIn: List<GameDefinition> = listOf(
        GameDefinition("mihoyo.genshin", "原神", ProviderType.MIHOYO, "hk4e_cn", true),
        GameDefinition("mihoyo.starrail", "崩坏：星穹铁道", ProviderType.MIHOYO, "hkrpg_cn", true),
        GameDefinition("mihoyo.zzz", "绝区零", ProviderType.MIHOYO, "nap_cn", true),
        GameDefinition("mihoyo.honkai3", "崩坏3", ProviderType.MIHOYO, "bh3_cn"),
        GameDefinition("mihoyo.tears", "未定事件簿", ProviderType.MIHOYO, "nxx_cn"),
        GameDefinition("mihoyo.honkai2", "崩坏学园2", ProviderType.MIHOYO, "bh2_cn"),
        GameDefinition("skland.arknights", "明日方舟", ProviderType.SKLAND, "arknights", true),
        GameDefinition("skland.endfield", "明日方舟：终末地", ProviderType.SKLAND, "endfield", true),
    )
}
