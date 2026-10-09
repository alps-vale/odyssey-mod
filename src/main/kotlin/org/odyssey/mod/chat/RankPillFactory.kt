package org.odyssey.mod.chat

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import org.odyssey.mod.network.RankColors
import org.odyssey.mod.network.RankPresentation
import java.util.Locale
import kotlin.math.roundToInt

internal object RankPillFactory {
    private const val LEFT = "\uE010\u2064"
    private const val RIGHT = "\uE011\u2064"
    private const val BACKGROUND = "\uE00F"
    private const val SMOOTH_BACKGROUND = "\uE005\uE020"
    private const val SMOOTH_CELL_END = "\uE010"
    private const val FOREGROUND = "\uE012"
    private const val DISCORD_MARK = '\uF8F2'
    private const val SYSTEM_MARK = '\uF8F4'
    private const val CONTINUATION_RAIL = '\uF8F3'
    private const val SOURCE_PULL = "\uF8FE\uF8FE\uF8FE\uF8FE"
    private const val MARKER_PAD = "\uF8FD\uF8FD"
    private const val RAIL_PAD = "\uF8FD\uF8FD\uF8FD\uF8FD\uF8FD\uF8FD\uF8FD\uF8FD\uF8FD\uF8FD"
    private const val DISCORD_BLURPLE = 0x5865F2
    private const val PILL_LABEL_COLOR = 0x1F2126
    private const val WYNN_AQUA = 0x55FFFF
    private const val PILL_CELL_WIDTH = 6
    private val pillFont =
        FontDescription.Resource(Identifier.fromNamespaceAndPath("odyssey", "pill"))
    private val discordFont =
        FontDescription.Resource(Identifier.fromNamespaceAndPath("odyssey", "discord_bridge"))

    fun rank(role: RankPresentation): MutableComponent = label(role.label, role.colors)

    fun label(label: String, colors: RankColors): MutableComponent =
        pill(boundedLabel(label), colors)

    fun discordSource(continuation: Boolean = false): MutableComponent =
        if (continuation) {
            rail(DISCORD_BLURPLE)
        } else {
            Component.literal(SOURCE_PULL + DISCORD_MARK + MARKER_PAD)
                .withStyle(Style.EMPTY.withFont(discordFont).withColor(0xFFFFFF))
        }

    fun systemSource(): MutableComponent =
        Component.literal(SOURCE_PULL + SYSTEM_MARK + MARKER_PAD)
            .withStyle(Style.EMPTY.withFont(discordFont).withColor(0xFFFFFF).withoutShadow())


    fun wynnSource(prefix: String): MutableComponent =
        Component.literal("[$prefix]").withStyle(Style.EMPTY.withColor(WYNN_AQUA))

    fun rail(color: Int): MutableComponent =
        Component.literal(SOURCE_PULL + CONTINUATION_RAIL + RAIL_PAD)
            .withStyle(Style.EMPTY.withFont(discordFont).withColor(color).withoutShadow())

    fun gradientText(text: String, colors: RankColors, baseStyle: Style = Style.EMPTY): MutableComponent {
        val result = Component.empty()
        val codePoints = text.codePoints().toArray()
        codePoints.forEachIndexed { index, codePoint ->
            val color = gradientColor(colors, index, codePoints.size)
            result.append(
                Component.literal(String(Character.toChars(codePoint)))
                    .withStyle(baseStyle.withColor(color)),
            )
        }
        return result
    }

    fun boundedLabel(label: String): String {
        val upper = label.uppercase(Locale.ROOT)
        val points = upper.codePoints().toArray()
        return String(points.take(16).toIntArray(), 0, minOf(points.size, 16))
    }


    private fun pill(label: String, colors: RankColors): MutableComponent {
        val codePoints = label.codePoints().toArray()
        val gradientPixels = codePoints.size * PILL_CELL_WIDTH
        val firstColor = gradientColor(colors, 0, gradientPixels)
        val result = Component.empty()
            .append(Component.literal(LEFT).withStyle(Style.EMPTY.withColor(firstColor)))
        codePoints.forEachIndexed { index, codePoint ->
            appendBackground(result, colors, index * PILL_CELL_WIDTH, gradientPixels)
            wynnGlyph(codePoint)?.let { glyph ->
                result.append(
                    Component.literal(FOREGROUND + glyph)
                        .withStyle(Style.EMPTY.withColor(PILL_LABEL_COLOR).withoutShadow()),
                )
            }
        }
        val lastColor = gradientColor(colors, (gradientPixels - 1).coerceAtLeast(0), gradientPixels)
        return result.append(Component.literal(RIGHT).withStyle(Style.EMPTY.withColor(lastColor)))
    }

    private fun appendBackground(
        result: MutableComponent,
        colors: RankColors,
        firstPixel: Int,
        totalPixels: Int,
    ) {
        if (colors.secondary == null) {
            result.append(
                Component.literal(BACKGROUND).withStyle(Style.EMPTY.withColor(colors.primary)),
            )
            return
        }
        repeat(PILL_CELL_WIDTH) { offset ->
            val color = gradientColor(colors, firstPixel + offset, totalPixels)
            result.append(
                Component.literal(SMOOTH_BACKGROUND)
                    .withStyle(Style.EMPTY.withFont(pillFont).withColor(color)),
            )
        }
        result.append(
            Component.literal(SMOOTH_CELL_END)
                .withStyle(Style.EMPTY.withFont(pillFont)),
        )
    }

    private fun wynnGlyph(codePoint: Int): String? {
        val lower = Character.toLowerCase(codePoint)
        val mapped = when (lower) {
            in 'a'.code..'z'.code -> 0xE040 + lower - 'a'.code
            in '0'.code..'9'.code -> 0xE060 + lower - '0'.code
            else -> return null
        }
        return String(Character.toChars(mapped))
    }

    private fun gradientColor(colors: RankColors, index: Int, count: Int): Int {
        val secondary = colors.secondary ?: return colors.primary
        if (count <= 1) return colors.primary
        val position = index.toDouble() / (count - 1)
        val tertiary = colors.tertiary
        return if (tertiary == null) {
            interpolate(colors.primary, secondary, position)
        } else if (position <= 0.5) {
            interpolate(colors.primary, secondary, position * 2)
        } else {
            interpolate(secondary, tertiary, (position - 0.5) * 2)
        }
    }

    private fun interpolate(start: Int, end: Int, amount: Double): Int {
        fun channel(shift: Int): Int {
            val from = start shr shift and 0xFF
            val to = end shr shift and 0xFF
            return (from + (to - from) * amount).roundToInt().coerceIn(0, 255)
        }
        return channel(16) shl 16 or (channel(8) shl 8) or channel(0)
    }
}
