package org.odyssey.mod.chat

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import org.odyssey.mod.network.ChatSource
import org.odyssey.mod.network.RankColors
import org.odyssey.mod.network.RankPresentation
import org.odyssey.mod.network.ServerMessage
import java.util.Collections
import java.util.WeakHashMap

internal object BridgeChatRenderer {
    private const val WYNN_AQUA = 0x55FFFF
    private const val NEUTRAL = 0xA0A0A0
    private val continuationColors = Collections.synchronizedMap(WeakHashMap<Component, Int>())
    private var bridgeSequenceOpen = false

    fun render(message: ServerMessage.Chat) {
        Minecraft.getInstance().gui.chat.addMessage(componentForDisplay(message))
    }

    internal fun componentForDisplay(message: ServerMessage.Chat): Component {
        val isDiscord = message.source == ChatSource.DISCORD
        val rendered = component(message, isDiscord && bridgeSequenceOpen)
        bridgeSequenceOpen = isDiscord
        return rendered
    }

    fun component(message: ServerMessage.Chat): Component = component(message, false)

    private fun component(message: ServerMessage.Chat, continuation: Boolean): Component {
        val sourceColor = if (message.source == ChatSource.DISCORD) 0x5865F2 else WYNN_AQUA
        val source = when (message.source) {
            ChatSource.DISCORD -> RankPillFactory.discordSource(continuation)
            ChatSource.WYNN -> Component.empty()
                .append(RankPillFactory.wynnSource(requireNotNull(message.originGuild).prefix))
                .append(Component.literal(" "))
        }
        val role = message.author.role ?: RankPresentation(
            message.author.wynnRank ?: "UNRANKED",
            RankColors(NEUTRAL),
        )
        val result = Component.empty()
            .append(source)
            .append(RankPillFactory.rank(role))
            .append(Component.literal(" "))
            .append(
                RankPillFactory.gradientText(
                    message.author.displayName,
                    role.colors,
                ),
            )
            .append(Component.literal(": ").withStyle(Style.EMPTY.withColor(WYNN_AQUA)))
        result.append(Component.literal(message.content).withStyle(Style.EMPTY.withColor(WYNN_AQUA)))
        continuationColors[result] = sourceColor
        return result
    }

    fun observeDisplayed(component: Component): Boolean {
        val isBridgeMessage = continuationColors.containsKey(component)
        if (!isBridgeMessage) {
            bridgeSequenceOpen = false
        }
        return isBridgeMessage
    }

    fun continuationPrefix(component: Component): Component? =
        continuationColors[component]?.let { color ->
            RankPillFactory.rail(color)
        }

    fun reset() {
        continuationColors.clear()
        bridgeSequenceOpen = false
    }
}
