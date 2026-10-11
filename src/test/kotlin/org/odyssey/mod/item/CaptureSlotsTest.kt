package org.odyssey.mod.item

import org.odyssey.mod.network.MAX_ITEM_SHARES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureSlotsTest {
    @Test
    fun `two three-item messages never render more than three tooltips at once`() {
        val slots = CaptureSlots(limit = MAX_ITEM_SHARES, waitingLimit = MAX_ITEM_SHARES)
        val finishes = ArrayDeque<() -> Unit>()
        var active = 0
        var peak = 0
        val results = List(2 * MAX_ITEM_SHARES) { index ->
            slots.capture<String>(LONG_TIMEOUT) { result, release ->
                active += 1
                peak = maxOf(peak, active)
                finishes.addLast {
                    result.complete("png-$index")
                    active -= 1
                    release()
                }
            }
        }
        while (finishes.isNotEmpty()) finishes.removeFirst()()
        assertEquals(MAX_ITEM_SHARES, peak)
        assertEquals(List(2 * MAX_ITEM_SHARES) { "png-$it" }, results.map { it.join() })
    }

    @Test
    fun `a timed-out capture keeps its slot until its work releases it`() {
        val slots = CaptureSlots(limit = 1, waitingLimit = 1)
        var release: (() -> Unit)? = null
        val readingBack = slots.capture<String>(SHORT_TIMEOUT) { _, done -> release = done }
        var abandonedRendered = false
        val waiting = slots.capture<String>(SHORT_TIMEOUT) { _, _ -> abandonedRendered = true }
        assertNull(readingBack.join())
        assertNull(waiting.join())
        assertNull(slots.capture<String>(LONG_TIMEOUT) { _, _ -> error("slot is still owned") }.join())
        checkNotNull(release)()
        assertFalse(abandonedRendered)
        assertEquals("png", slots.capture<String>(LONG_TIMEOUT) { result, done -> result.complete("png"); done() }.join())
    }

    @Test
    fun `abandoned callers cannot grow waiting work while readbacks stay stuck`() {
        val slots = CaptureSlots(limit = MAX_ITEM_SHARES, waitingLimit = MAX_ITEM_SHARES)
        val stuckReadbacks = mutableListOf<() -> Unit>()
        var rendered = 0
        val abandoned = List(2 * MAX_ITEM_SHARES) {
            slots.capture<String>(SHORT_TIMEOUT) { _, release ->
                rendered += 1
                stuckReadbacks += release
            }
        }
        abandoned.forEach { assertNull(it.join()) }
        // Every later message falls back at once instead of queueing behind stuck readbacks.
        val later = List(20 * MAX_ITEM_SHARES) { slots.capture<String>(LONG_TIMEOUT) { _, _ -> rendered += 1 } }
        assertTrue(later.all { it.isDone && it.join() == null })
        assertEquals(MAX_ITEM_SHARES, rendered)

        stuckReadbacks.forEach { it() }
        assertEquals(MAX_ITEM_SHARES, rendered, "abandoned and refused captures never render")
        val recovered = List(MAX_ITEM_SHARES) { index ->
            slots.capture<String>(LONG_TIMEOUT) { result, release ->
                result.complete("png-$index")
                release()
            }
        }
        assertEquals(List(MAX_ITEM_SHARES) { "png-$it" }, recovered.map { it.join() })
    }

    @Test
    fun `failed or repeated releases never strand or overfill slots`() {
        val slots = CaptureSlots(limit = 1, waitingLimit = 2)
        assertFailsWith<IllegalStateException> { slots.capture<String>(LONG_TIMEOUT) { _, _ -> error("render failed") } }
        var release: (() -> Unit)? = null
        var started = 0
        slots.capture<String>(LONG_TIMEOUT) { _, done -> started += 1; release = done }
        slots.capture<String>(LONG_TIMEOUT) { _, _ -> started += 1 }
        slots.capture<String>(LONG_TIMEOUT) { _, _ -> started += 1 }
        assertEquals(1, started)
        checkNotNull(release)()
        release!!()
        assertEquals(2, started)
    }

    private companion object {
        const val SHORT_TIMEOUT = 20L
        const val LONG_TIMEOUT = 60_000L
    }
}
