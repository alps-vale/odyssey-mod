package org.odyssey.mod.item

import org.odyssey.mod.network.MAX_ITEM_SHARES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CaptureSlotsTest {
    @Test
    fun `two three-item messages never render more than three tooltips at once`() {
        val slots = CaptureSlots(MAX_ITEM_SHARES)
        val releases = ArrayDeque<() -> Unit>()
        var active = 0
        var peak = 0
        var started = 0
        repeat(2 * MAX_ITEM_SHARES) {
            slots.submit { release ->
                started += 1
                active += 1
                peak = maxOf(peak, active)
                releases.addLast { active -= 1; release() }
            }
        }
        assertEquals(MAX_ITEM_SHARES, started)
        while (releases.isNotEmpty()) releases.removeFirst()()
        assertEquals(2 * MAX_ITEM_SHARES, started)
        assertEquals(MAX_ITEM_SHARES, peak)
    }

    @Test
    fun `an abandoned capture keeps its slot until its work releases it`() {
        val slots = CaptureSlots(1)
        var release: (() -> Unit)? = null
        val started = mutableListOf<String>()
        slots.submit { started += "timed out but still reading back"; release = it }
        // A waiting capture whose caller already gave up gives its slot straight back.
        slots.submit { started += "abandoned while waiting"; it() }
        slots.submit { started += "next message"; it() }
        assertEquals(listOf("timed out but still reading back"), started)
        checkNotNull(release)()
        assertEquals(
            listOf("timed out but still reading back", "abandoned while waiting", "next message"),
            started,
        )
    }

    @Test
    fun `failed or repeated releases never strand or overfill slots`() {
        val slots = CaptureSlots(1)
        assertFailsWith<IllegalStateException> { slots.submit { error("render failed") } }
        var release: (() -> Unit)? = null
        var started = 0
        slots.submit { started += 1; release = it }
        slots.submit { started += 1 }
        slots.submit { started += 1 }
        assertEquals(1, started)
        checkNotNull(release)()
        release!!()
        assertEquals(2, started)
    }
}
