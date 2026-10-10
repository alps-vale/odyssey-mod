package org.odyssey.mod.item

import org.odyssey.mod.network.ItemShare
import org.odyssey.mod.network.ItemShareKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrderedItemPreviewsTest {
    private val metadata = ItemShare(ItemShareKind.WYNNCRAFT, "[Bow]", "Bow", 0xaa00aa)
    private val rendered = metadata.copy(png = "AAAA")

    @Test
    fun `ordinary chat cannot overtake a slow item render`() {
        val queue = OrderedItemPreviews()
        val sent = mutableListOf<Pair<String, List<ItemShare>>>()
        val finishItem = queue.begin(listOf(metadata)) { sent.add("item" to it) }
        queue.begin(emptyList()) { sent.add("plain" to it) }(emptyList())
        assertTrue(sent.isEmpty())
        finishItem(listOf(rendered))
        assertEquals(listOf("item" to listOf(rendered), "plain" to emptyList()), sent)
    }

    @Test
    fun `queue pressure sends metadata in order and ignores a late image`() {
        val queue = OrderedItemPreviews(limit = 2)
        val sent = mutableListOf<Pair<String, List<ItemShare>>>()
        val finishFirst = queue.begin(listOf(metadata)) { sent.add("first" to it) }
        val finishSecond = queue.begin(listOf(metadata)) { sent.add("second" to it) }
        val finishThird = queue.begin(emptyList()) { sent.add("third" to it) }
        assertEquals(listOf("first" to listOf(metadata)), sent)
        finishThird(emptyList())
        finishFirst(listOf(rendered))
        assertEquals(1, sent.size)
        finishSecond(listOf(rendered))
        assertEquals(listOf("first" to listOf(metadata), "second" to listOf(rendered), "third" to emptyList()), sent)
    }
}
