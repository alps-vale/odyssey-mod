package org.odyssey.mod.config

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsCommandsTest {
    @TempDir lateinit var root: Path

    @Test fun `commands complete keys and booleans and preserve other saved preferences`() {
        val path = root.resolve("config.json")
        val applied = mutableListOf<OdysseySetting>()
        var writes = 0
        val settings = OdysseySettings(OdysseyConfig(), {
            OdysseyConfig.save(it, path)
            writes++
        }) { setting, _ -> applied += setting }
        val feedback = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val source = source(feedback, errors)
        val dispatcher = dispatcher(settings)
        fun complete(text: String) = dispatcher.getCompletionSuggestions(dispatcher.parse(text, source))
            .get().list.map { it.text }

        assertEquals(listOf("autoConnect", "bridgeVisible", "checkUpdates", "discordRankOverrides"), complete("odyssey config "))
        assertEquals(listOf("false", "true"), complete("odyssey config bridgeVisible "))
        assertEquals(listOf("bridgeVisible"), complete("odyssey config bridge"))
        dispatcher.execute("odyssey config bridgeVisible false", source)
        dispatcher.execute("odyssey config discordRankOverrides false", source)
        dispatcher.execute("odyssey config checkUpdates false", source)
        assertEquals(OdysseyConfig(bridgeVisible = false, discordRankOverrides = false, checkUpdates = false), OdysseyConfig.load(path))
        dispatcher.execute("odyssey config checkUpdates false", source)
        assertEquals(3, writes, "unchanged settings do not rewrite disk or reapply runtime effects")
        assertEquals(listOf(OdysseySetting.BRIDGE_VISIBLE, OdysseySetting.DISCORD_RANK_OVERRIDES, OdysseySetting.CHECK_UPDATES), applied)
        dispatcher.execute("odyssey config bridgeVisible", source)
        assertEquals("bridgeVisible = false", feedback.last())
        dispatcher.execute("odyssey config", source)
        assertTrue(feedback.last().contains("autoConnect = true"))
        assertFailsWith<CommandSyntaxException> { dispatcher.execute("odyssey config autoConnect maybe", source) }
        assertTrue(errors.isEmpty())
    }

    @Test fun `failed persistence keeps session preferences and retries saving without duplicate effects`() {
        val path = root.resolve("config.json")
        var fail = true
        var activeChecks = true
        var applied = 0
        val settings = OdysseySettings(OdysseyConfig(), {
            if (fail) throw IOException("unwritable config")
            OdysseyConfig.save(it, path)
        }) { _, next -> activeChecks = next.checkUpdates; applied++ }
        val errors = mutableListOf<String>()
        val feedback = mutableListOf<String>()
        val source = source(feedback, errors)
        val dispatcher = dispatcher(settings)

        assertEquals(0, dispatcher.execute("odyssey config checkUpdates false", source))
        assertFalse(activeChecks, "an opt-out must not wait for a successful disk write")
        assertFalse(settings.current.checkUpdates)
        assertTrue(errors.single().contains("for this session"))
        fail = false
        assertEquals(1, dispatcher.execute("odyssey config checkUpdates false", source))
        assertFalse(OdysseyConfig.load(path).checkUpdates)
        assertEquals(1, applied)
        assertEquals("checkUpdates = false", feedback.single())
    }

    private fun dispatcher(settings: OdysseySettings) = CommandDispatcher<FabricClientCommandSource>().apply {
        register(literal("odyssey").then(SettingsCommands.tree(settings)))
    }

    private fun source(feedback: MutableList<String>, errors: MutableList<String>): FabricClientCommandSource =
        Proxy.newProxyInstance(FabricClientCommandSource::class.java.classLoader,
            arrayOf(FabricClientCommandSource::class.java)) { _, method, args ->
            when (method.name) {
                "sendFeedback" -> { feedback += (args!![0] as Component).string; null }
                "sendError" -> { errors += (args!![0] as Component).string; null }
                else -> error("Unexpected command source method: ${method.name}")
            }
        } as FabricClientCommandSource
}
