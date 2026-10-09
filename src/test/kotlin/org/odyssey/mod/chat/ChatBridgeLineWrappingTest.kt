package org.odyssey.mod.chat

import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ChatBridgeLineWrappingTest {
    @Test
    fun `continuations reserve rail width without consuming literal leading spaces`() {
        val initial = listOf(sequence("first"), sequence(" second"))
        var requestedWidth = 0

        val result = ChatBridgeLineWrapping.addContinuationPrefixes(
            initial,
            maxWidth = 100,
            prefixWidth = 12,
            wrapAtWidth = { width ->
                requestedWidth = width
                listOf(sequence("first"), sequence(" second"), sequence(" third"))
            },
            prefix = sequence("| "),
        )

        assertEquals(88, requestedWidth)
        assertEquals(listOf("first", "|  second", "|  third"), result.map(::textOf))
    }

    @Test
    fun `single visual line skips the second wrapping pass`() {
        val initial = listOf(sequence("short"))

        val result = ChatBridgeLineWrapping.addContinuationPrefixes(
            initial,
            maxWidth = 100,
            prefixWidth = 12,
            wrapAtWidth = { error("single line must not be wrapped again") },
            prefix = sequence("| "),
        )

        assertSame(initial, result)
    }

    private fun sequence(text: String): FormattedCharSequence =
        FormattedCharSequence.forward(text, Style.EMPTY)

    private fun textOf(sequence: FormattedCharSequence): String = buildString {
        sequence.accept { _, _, codePoint ->
            appendCodePoint(codePoint)
            true
        }
    }
}
