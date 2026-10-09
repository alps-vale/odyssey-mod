package org.odyssey.mod

import com.mojang.brigadier.Command
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import org.odyssey.mod.config.OdysseyConfig
import org.odyssey.mod.chat.BridgeChatRenderer
import org.odyssey.mod.chat.OdysseyNotifications
import org.odyssey.mod.chat.GuildChatDecorator
import org.odyssey.mod.network.BackendOrigin
import org.odyssey.mod.network.BridgeClient
import org.odyssey.mod.network.JavaOdysseyTransport
import org.odyssey.mod.network.MinecraftGameAccess
import org.odyssey.mod.update.OdysseyUpdater
import org.odyssey.mod.update.UpdateNotice

object OdysseyMod : ClientModInitializer {
    private lateinit var bridge: BridgeClient
    private var lastAddress: String? = null
    private var lastPlayable = false
    private lateinit var updater: OdysseyUpdater
    private val updateNotices = ArrayDeque<UpdateNotice>()

    override fun onInitializeClient() = OdysseyDiagnostics.callback("client initialization") {
        val origin = BackendOrigin.configured()
        val version = FabricLoader.getInstance()
            .getModContainer("odyssey")
            .orElseThrow()
            .metadata
            .version
            .friendlyString
        val config = OdysseyConfig.load()
        updater = OdysseyUpdater(version, config) { notice ->
            Minecraft.getInstance().execute { updateNotices.addLast(notice) }
        }
        updater.start()
        GuildChatDecorator.enabled = config.discordRankOverrides
        bridge = BridgeClient(
            JavaOdysseyTransport(origin, version),
            MinecraftGameAccess(chatRenderer = BridgeChatRenderer::render),
            config,
        )
        ClientTickEvents.END_CLIENT_TICK.register { minecraft ->
            OdysseyDiagnostics.callback("client tick") {
                tick(minecraft)
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            OdysseyDiagnostics.callback("client disconnect") {
                resetVisualState()
                lastAddress = null
                lastPlayable = false
                bridge.updateEnvironment(null, false)
            }
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            OdysseyDiagnostics.callback("client stopping") {
                bridge.stop()
            }
        }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            OdysseyDiagnostics.callback("client command registration") {
                dispatcher.register(
                    literal("odyssey")
                        .then(
                            literal("update")
                                .executes { context ->
                                    context.source.sendFeedback(
                                        OdysseyNotifications.update(UpdateNotice(updater.status), commandUsesPill()),
                                    )
                                    Command.SINGLE_SUCCESS
                                }
                                .then(literal("check").executes { updater.check(); Command.SINGLE_SUCCESS })
                                .then(literal("install").executes { updater.install(); Command.SINGLE_SUCCESS })
                                .then(
                                    literal("auto")
                                        .then(literal("on").executes { updater.setAutomatic(true); Command.SINGLE_SUCCESS })
                                        .then(literal("off").executes { updater.setAutomatic(false); Command.SINGLE_SUCCESS }),
                                ),
                        )
                        .then(
                            literal("status").executes { context ->
                                OdysseyDiagnostics.callback("status command") {
                                    context.source.sendFeedback(
                                        OdysseyNotifications.statusReport(bridge.status(), commandUsesPill()),
                                    )
                                    Command.SINGLE_SUCCESS
                                }
                            },
                        )
                        .then(
                            literal("reconnect").executes { context ->
                                OdysseyDiagnostics.callback("reconnect command") {
                                    bridge.reconnect()
                                    context.source.sendFeedback(
                                        OdysseyNotifications.reconnectRequested(commandUsesPill()),
                                    )
                                    Command.SINGLE_SUCCESS
                                }
                            },
                        ),
                )
            }
        }
        OdysseyDiagnostics.logger.info("[Odyssey Mod] Initialized version={} backend={}", version, origin.http)
    }

    fun observeGuildMessage(authorUsername: String, content: String) {
        if (::bridge.isInitialized) bridge.observe(authorUsername, content)
    }

    private fun tick(minecraft: Minecraft) {
        val address = minecraft.currentServer?.ip ?: lastAddress
        val playable = minecraft.level != null && minecraft.player != null
        if (playable) {
            while (updateNotices.isNotEmpty()) {
                minecraft.gui.chat.addMessage(
                    OdysseyNotifications.update(updateNotices.removeFirst(), commandUsesPill()),
                )
            }
        }
        if (address != lastAddress || playable != lastPlayable) {
            if (address != lastAddress) resetVisualState()
            lastAddress = address
            lastPlayable = playable
            OdysseyDiagnostics.logger.debug(
                "[Odyssey Mod] Bridge environment address={} playable={}",
                address,
                playable,
            )
            bridge.updateEnvironment(address, playable)
        }
    }

    private fun resetVisualState() {
        BridgeChatRenderer.reset()
    }

    private fun commandUsesPill(): Boolean =
        BridgeClient.isWynncraftAddress(Minecraft.getInstance().currentServer?.ip)

}
