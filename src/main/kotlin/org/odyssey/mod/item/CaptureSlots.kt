package org.odyssey.mod.item

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Runs at most [limit] captures at once and lets at most [waitingLimit] more wait, in arrival order.
 *
 * A capture keeps its slot until it calls `release`, which happens only when its rendering,
 * readback and encoding work has finished, not when its caller stops waiting. Call [capture] and
 * `release` from one thread; tooltip captures use the render thread.
 */
internal class CaptureSlots(private val limit: Int, private val waitingLimit: Int) {
    private var running = 0
    private val waiting = ArrayDeque<(release: () -> Unit) -> Unit>()

    init {
        require(limit > 0 && waitingLimit >= 0)
    }

    /**
     * Starts [render] in a free slot, or queues it while every slot is busy. [render] completes the
     * result and must call `release` once its work has finished.
     *
     * The result completes with `null` at once when the queue is full too, so abandoned callers
     * cannot accumulate retained work, or after [timeoutMillis] without an image. A queued capture
     * whose caller has already given up never renders.
     */
    fun <T> capture(
        timeoutMillis: Long,
        render: (result: CompletableFuture<T?>, release: () -> Unit) -> Unit,
    ): CompletableFuture<T?> {
        val result = CompletableFuture<T?>()
        if (running >= limit && waiting.size >= waitingLimit) {
            result.complete(null)
            return result
        }
        waiting.addLast { release -> if (result.isDone) release() else render(result, release) }
        startWaiting()
        return result.completeOnTimeout(null, timeoutMillis, TimeUnit.MILLISECONDS)
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
