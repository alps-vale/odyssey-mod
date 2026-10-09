package org.odyssey.mod.chat

import net.minecraft.network.chat.Component
import org.odyssey.mod.network.RankColors
import org.odyssey.mod.network.PresentationRepository

internal object GuildChatDecorator {
    @Volatile
    var enabled: Boolean = true

    fun decorate(component: Component): Component {
        if (BridgeChatRenderer.observeDisplayed(component)) return component
        val parsed = GuildChatParser.parse(component) ?: return component
        if (!enabled) return component
        val matches = PresentationRepository.snapshot().entries.values
            .filter { it.minecraftUsername == parsed.authorUsername }
        if (matches.size != 1) return component
        val presentation = matches.single()
        val decorated = component.copy()
        val siblings = decorated.siblings
        val originalName = siblings[parsed.nameIndex]
        siblings[parsed.rankIndex] = RankPillFactory.rank(presentation.role)
        siblings[parsed.nameIndex] = decorateName(originalName, parsed.visibleName, presentation.role.colors)
        return decorated
    }

    private fun decorateName(original: Component, visibleName: String, colors: RankColors): Component {
        val decorated = Component.empty().withStyle(original.style)
        val visibleStyle = original.siblings.firstOrNull()?.style ?: original.style
        decorated.append(RankPillFactory.gradientText(visibleName, colors, visibleStyle))
        decorated.append(original.siblings.getOrNull(1)?.copy() ?: Component.literal(":"))
        return decorated
    }

}
