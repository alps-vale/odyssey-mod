package org.odyssey.mod.chat

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.contents.PlainTextContents
import org.odyssey.mod.network.MAX_GUILD_BODY_CODE_POINTS

internal data class ParsedGuildChat(
    val authorUsername: String,
    val visibleName: String,
    val content: String,
    val rankIndex: Int,
    val nameIndex: Int,
)

internal object GuildChatParser {
    private const val GUILD_COLOR = 0x55FFFF
    private const val NAME_COLOR = 0x00AAAA
    private const val MAX_VISIBLE_NAME_CODE_POINTS = 32
    private const val HARD_WRAP_BOUNDARY_CODE_POINTS = 32
    private val username = Regex("^[A-Za-z0-9_]{1,16}$")

    fun parse(component: Component): ParsedGuildChat? {
        val root = component.contents as? PlainTextContents ?: return null
        if (root.text().isNotEmpty() || component.style.color?.value != GUILD_COLOR) return null
        val siblings = component.siblings
        if (siblings.size < 6 || siblings.any { it.contents !is PlainTextContents }) return null
        if (!isPrivateGlyphRun(literal(siblings[0])) || siblings[0].siblings.isNotEmpty()) return null
        if (
            literal(siblings[1]) != " " || siblings[1].siblings.isNotEmpty() ||
            literal(siblings[3]) != " " || siblings[3].siblings.isNotEmpty()
        ) {
            return null
        }

        val rank = siblings[2]
        val rankLabel = rank.siblings.singleOrNull() ?: return null
        if (!isPrivateGlyphRun(literal(rank)) || !isPrivateGlyphRun(literal(rankLabel))) return null
        if (rankLabel.style.color?.value != 0 || rankLabel.siblings.isNotEmpty()) return null

        val name = parseName(siblings[4]) ?: return null
        val content = parseBody(siblings.subList(5, siblings.size)) ?: return null
        if (content.isEmpty() || content.codePointCount(0, content.length) > MAX_GUILD_BODY_CODE_POINTS) return null
        if (content.any(::unsafeCharacter)) return null
        return ParsedGuildChat(
            name.canonical,
            name.visible,
            content,
            rankIndex = 2,
            nameIndex = 4,
        )
    }

    private fun parseName(component: Component): ParsedName? {
        if (component.style.color?.value != NAME_COLOR) return null
        val direct = literal(component) ?: return null
        if (component.siblings.isEmpty()) {
            val visible = direct.removeSuffix(":")
            if (visible == direct || !username.matches(visible)) return null
            return ParsedName(visible, visible)
        }
        if (direct.isNotEmpty() || component.siblings.size != 2) return null
        val visibleComponent = component.siblings[0]
        val colon = component.siblings[1]
        val visible = literal(visibleComponent)?.takeIf(String::isNotEmpty) ?: return null
        if (visible.codePointCount(0, visible.length) > MAX_VISIBLE_NAME_CODE_POINTS) return null
        if (visibleComponent.siblings.isNotEmpty() || literal(colon) != ":" || colon.siblings.isNotEmpty()) return null
        val hover = visibleComponent.style.hoverEvent as? HoverEvent.ShowText ?: return null
        val prefix = "$visible's real name is "
        val canonical = hover.value.string.removePrefix(prefix)
        if (canonical == hover.value.string || !username.matches(canonical)) return null
        return ParsedName(canonical, visible)
    }

    private fun parseBody(components: List<Component>): String? {
        val literalBody = StringBuilder()
        components.forEach { if (!appendLiteralTree(it, literalBody)) return null }
        val lines = literalBody.toString().split('\n')
        val firstLine = lines.firstOrNull() ?: return null
        val content = StringBuilder(
            when {
                firstLine.startsWith(' ') -> firstLine.drop(1)
                firstLine.isEmpty() && lines.size > 1 -> ""
                else -> return null
            },
        )
        lines.drop(1).forEach { line ->
            val nextLine = stripContinuationMarker(line) ?: return null
            if (nextLine.isEmpty()) return null
            if (content.isNotEmpty() && !isLikelyHardWrap(content, nextLine)) content.append(' ')
            content.append(nextLine)
        }
        return content.toString()
    }

    private fun isLikelyHardWrap(previous: CharSequence, next: String): Boolean =
        trailingTokenCodePoints(previous) + leadingTokenCodePoints(next) >= HARD_WRAP_BOUNDARY_CODE_POINTS

    private fun trailingTokenCodePoints(value: CharSequence): Int {
        var offset = value.length
        var count = 0
        while (offset > 0) {
            val codePoint = Character.codePointBefore(value, offset)
            if (Character.isWhitespace(codePoint)) break
            offset -= Character.charCount(codePoint)
            count += 1
        }
        return count
    }

    private fun leadingTokenCodePoints(value: String): Int {
        var offset = 0
        var count = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (Character.isWhitespace(codePoint)) break
            offset += Character.charCount(codePoint)
            count += 1
        }
        return count
    }

    private fun appendLiteralTree(component: Component, destination: StringBuilder): Boolean {
        val contents = component.contents as? PlainTextContents ?: return false
        destination.append(contents.text())
        return component.siblings.all { appendLiteralTree(it, destination) }
    }

    private fun stripContinuationMarker(line: String): String? {
        var offset = 0
        var hasMarkerGlyph = false
        while (offset < line.length) {
            val codePoint = line.codePointAt(offset)
            if (!isPrivateGlyph(codePoint) && Character.getType(codePoint) != Character.FORMAT.toInt()) break
            hasMarkerGlyph = hasMarkerGlyph || isPrivateGlyph(codePoint)
            offset += Character.charCount(codePoint)
        }
        if (!hasMarkerGlyph || line.getOrNull(offset) != ' ') return null
        return line.substring(offset + 1)
    }

    private fun isPrivateGlyphRun(value: String?): Boolean =
        value?.isNotEmpty() == true && value.codePoints().allMatch(::isPrivateGlyph)

    private fun isPrivateGlyph(codePoint: Int): Boolean =
        codePoint in 0xE000..0xF8FF || codePoint in 0xC0000..0xDFFFF

    private data class ParsedName(val canonical: String, val visible: String)

    private fun literal(component: Component): String? =
        (component.contents as? PlainTextContents)?.text()

    private fun unsafeCharacter(character: Char): Boolean =
        character == '\n' || character == '\r' || character == '§' || character.isISOControl()
}
