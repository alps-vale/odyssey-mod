package org.odyssey.mod.item

import org.odyssey.mod.network.ItemShare

/** Client-thread completion queue: image readback must not reorder guild chat. */
internal class OrderedItemPreviews(private val limit: Int = 16) {
    private class Entry(val fallback: List<ItemShare>, val complete: (List<ItemShare>) -> Unit) {
        var previews: List<ItemShare>? = null
        var delivered = false
    }

    private val waiting = ArrayDeque<Entry>()

    init {
        require(limit > 0)
    }

    fun begin(fallback: List<ItemShare>, complete: (List<ItemShare>) -> Unit): (List<ItemShare>) -> Unit {
        if (waiting.size >= limit) {
            waiting.first().previews = waiting.first().fallback
            drain()
        }
        val entry = Entry(fallback, complete)
        waiting.addLast(entry)
        return { previews ->
            if (!entry.delivered) {
                entry.previews = previews
                drain()
            }
        }
    }

    private fun drain() {
        while (waiting.isNotEmpty()) {
            val entry = waiting.first()
            val previews = entry.previews ?: return
            waiting.removeFirst()
            entry.delivered = true
            entry.complete(previews)
        }
    }
}
