package org.odyssey.mod.chat

import net.minecraft.client.GuiMessage
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence

/** Reserves marker width before wrapping and prefixes every visual continuation line. */
internal object ChatBridgeLineWrapping {
    fun wrap(
        initialLines: List<FormattedCharSequence>,
        message: GuiMessage,
        font: Font,
        maxWidth: Int,
        continuationPrefix: Component,
    ): List<FormattedCharSequence> = addContinuationPrefixes(
        initialLines,
        maxWidth,
        font.width(continuationPrefix),
        { width -> message.splitLines(font, width) },
        continuationPrefix.visualOrderText,
    )

    fun addContinuationPrefixes(
        initialLines: List<FormattedCharSequence>,
        maxWidth: Int,
        prefixWidth: Int,
        wrapAtWidth: (Int) -> List<FormattedCharSequence>,
        prefix: FormattedCharSequence,
    ): List<FormattedCharSequence> {
        if (initialLines.size <= 1) return initialLines

        return wrapAtWidth(maxOf(1, maxWidth - prefixWidth)).mapIndexed { index, line ->
            if (index == 0) {
                line
            } else {
                FormattedCharSequence.composite(prefix, line)
            }
        }
    }

}
