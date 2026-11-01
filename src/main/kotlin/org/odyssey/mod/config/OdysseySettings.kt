package org.odyssey.mod.config

internal enum class OdysseySetting(
    val key: String,
    val read: (OdysseyConfig) -> Boolean,
    val write: (OdysseyConfig, Boolean) -> OdysseyConfig,
) {
    AUTO_CONNECT("autoConnect", { it.autoConnect }, { config, value -> config.copy(autoConnect = value) }),
    BRIDGE_VISIBLE("bridgeVisible", { it.bridgeVisible }, { config, value -> config.copy(bridgeVisible = value) }),
    DISCORD_RANK_OVERRIDES("discordRankOverrides", { it.discordRankOverrides }, { config, value -> config.copy(discordRankOverrides = value) }),
    CHECK_UPDATES("checkUpdates", { it.checkUpdates }, { config, value -> config.copy(checkUpdates = value) }),
}

/** Client-thread owner of live settings. */
internal class OdysseySettings(
    initial: OdysseyConfig,
    private val save: (OdysseyConfig) -> Unit = OdysseyConfig::save,
    private val apply: (OdysseySetting, OdysseyConfig) -> Unit,
) {
    var current: OdysseyConfig = initial
        private set
    private var unsaved = false

    fun set(setting: OdysseySetting, enabled: Boolean) {
        val next = setting.write(current, enabled)
        if (next != current) {
            // An update-check opt-out takes effect even if the disk is unwritable.
            apply(setting, next)
            current = next
            unsaved = true
        }
        if (unsaved) {
            save(current)
            unsaved = false
        }
    }
}
