package org.odyssey.mod.chat

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import org.odyssey.mod.network.RankColors
import org.odyssey.mod.network.RankPresentation
import org.odyssey.mod.network.PresentationRepository
import java.util.Optional

internal object GuildChatDecorator {
    @Volatile
    var enabled: Boolean = true

    private data class DisplayHeader(val original: Component, val parsed: ParsedGuildChat, val text: String)
    private var activeHeader: DisplayHeader? = null

    // Scope the validated server header to this delivery, before other mods rewrite its tree.
    fun withGuildMessage(original: Component, display: () -> Unit) {
        val previous = activeHeader
        activeHeader = if (enabled) GuildChatParser.parse(original)?.let {
            DisplayHeader(original, it, original.siblings.take(5).joinToString("") { part -> part.string })
        } else null
        try {
            display()
        } finally {
            activeHeader = previous
        }
    }

    fun decorate(component: Component): Component {
        if (BridgeChatRenderer.observeDisplayed(component)) return component
        if (!enabled) return component
        val direct = GuildChatParser.parse(component)
        val header = if (direct == null) activeHeader?.takeIf { component.string.startsWith(it.text) } else null
        val parsed = direct ?: header?.parsed ?: return component
        val matches = PresentationRepository.snapshot().entries.values
            .filter { it.minecraftUsername == parsed.authorUsername }
        if (matches.size != 1) return component
        val presentation = matches.single()
        if (header != null) return decorateRewritten(component, header, presentation.role)
        val decorated = component.copy()
        val siblings = decorated.siblings
        val originalName = siblings[parsed.nameIndex]
        siblings[parsed.rankIndex] = RankPillFactory.rank(presentation.role)
        siblings[parsed.nameIndex] = decorateName(originalName, parsed.visibleName, presentation.role.colors)
        return decorated
    }

    private fun decorateRewritten(component: Component, header: DisplayHeader, role: RankPresentation): Component {
        val siblings = header.original.siblings
        val rankStart = siblings.take(header.parsed.rankIndex).sumOf { it.string.length }
        val rankEnd = rankStart + siblings[header.parsed.rankIndex].string.length
        val nameStart = siblings.take(header.parsed.nameIndex).sumOf { it.string.length }
        return Component.empty()
            .appendRange(component, 0, rankStart)
            .append(RankPillFactory.rank(role))
            .appendRange(component, rankEnd, nameStart)
            .append(decorateName(siblings[header.parsed.nameIndex], header.parsed.visibleName, role.colors))
            .appendRange(component, header.text.length, Int.MAX_VALUE)
    }

    private fun MutableComponent.appendRange(source: Component, start: Int, end: Int): MutableComponent {
        var offset = 0
        source.visit(FormattedText.StyledContentConsumer<Unit> { style, text ->
            val from = (start - offset).coerceAtLeast(0)
            val to = (end - offset).coerceAtMost(text.length)
            if (from < to) append(Component.literal(text.substring(from, to)).withStyle(style))
            offset += text.length
            Optional.empty()
        }, Style.EMPTY)
        return this
    }

    private fun decorateName(original: Component, visibleName: String, colors: RankColors): Component {
        val decorated = Component.empty().withStyle(original.style)
        val visibleStyle = original.siblings.firstOrNull()?.style ?: original.style
        decorated.append(RankPillFactory.gradientText(visibleName, colors, visibleStyle))
        decorated.append(original.siblings.getOrNull(1)?.copy() ?: Component.literal(":"))
        return decorated
    }

}
