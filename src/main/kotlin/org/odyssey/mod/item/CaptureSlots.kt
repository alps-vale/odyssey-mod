package org.odyssey.mod.item

/**
 * Runs at most [limit] captures at once and starts waiting ones in arrival order.
 *
 * A capture keeps its slot until it calls `release`, which happens only when its rendering,
 * readback and encoding work has finished, not when its caller stops waiting. Call [submit] and
 * `release` from one thread; tooltip captures use the render thread.
 */
internal class CaptureSlots(private val limit: Int) {
    private var running = 0
    private val waiting = ArrayDeque<(release: () -> Unit) -> Unit>()

    init {
        require(limit > 0)
    }

    fun submit(capture: (release: () -> Unit) -> Unit) {
        waiting.addLast(capture)
        startWaiting()
    }

    private fun startWaiting() {
        while (running < limit) {
            val capture = waiting.removeFirstOrNull() ?: return
            running += 1
            var released = false
            val release = {
                if (!released) {
                    released = true
                    running -= 1
                    startWaiting()
                }
            }
            try {
                capture(release)
            } catch (error: Throwable) {
                release()
                throw error
            }
        }
    }
}
