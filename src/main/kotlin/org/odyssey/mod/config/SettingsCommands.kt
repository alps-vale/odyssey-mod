package org.odyssey.mod.config

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.BoolArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component
import org.odyssey.mod.OdysseyDiagnostics

internal object SettingsCommands {
    fun tree(settings: OdysseySettings) = literal("config")
        .executes { context ->
            context.source.sendFeedback(Component.literal(OdysseySetting.entries.joinToString("\n") {
                "${it.key} = ${it.read(settings.current)}"
            }))
            Command.SINGLE_SUCCESS
        }.also { root ->
            OdysseySetting.entries.forEach { setting ->
                root.then(literal(setting.key)
                    .executes { context ->
                        context.source.sendFeedback(Component.literal("${setting.key} = ${setting.read(settings.current)}"))
                        Command.SINGLE_SUCCESS
                    }
                    .then(argument("value", BoolArgumentType.bool()).executes { context ->
                        set(context.source, settings, setting, BoolArgumentType.getBool(context, "value"))
                    }))
            }
        }

    fun set(source: FabricClientCommandSource, settings: OdysseySettings, setting: OdysseySetting, enabled: Boolean): Int {
        return try {
            settings.set(setting, enabled)
            source.sendFeedback(Component.literal("${setting.key} = $enabled"))
            Command.SINGLE_SUCCESS
        } catch (error: Exception) {
            OdysseyDiagnostics.logger.warn("[Odyssey Mod] Could not save setting {}", setting.key, error)
            source.sendError(Component.literal("${setting.key} = $enabled for this session, but couldn't save config.json. Try again."))
            0
        }
    }
}
