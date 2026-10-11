package org.odyssey.mod.update

import org.junit.jupiter.api.io.TempDir
import org.odyssey.mod.config.OdysseyConfig
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OdysseyUpdaterTest {
    @TempDir lateinit var root: Path

    @Test fun `checks default on and obsolete install settings cannot reset other preferences`() {
        val path = root.resolve("config/odyssey/config.json")
        assertTrue(OdysseyConfig.load(path).checkUpdates)
        for (oldSetting in listOf(true, false)) {
            Files.writeString(path, """{"autoConnect":false,"bridgeVisible":false,"autoUpdate":$oldSetting}""")
            val config = OdysseyConfig.load(path)
            assertTrue(config.checkUpdates)
            assertFalse(config.autoConnect)
            assertFalse(config.bridgeVisible)
            OdysseyConfig.save(config.copy(checkUpdates = false), path)
            assertFalse(OdysseyConfig.load(path).checkUpdates)
            assertFalse("autoUpdate" in Files.readString(path))
        }
    }

    @Test fun `startup and periodic checks offer once per version without downloading`() = session { s ->
        s.updater.start()
        s.drain()
        assertEquals(listOf(UpdateNotice.Action.Download("0.3.4")), s.notices.map { it.action })
        assertEquals(0, s.files.downloads.size)
        assertTrue(s.worker.timers.last().minutes in 25..35)

        s.poll()
        assertEquals(1, s.notices.size)
        s.files.release = release("0.3.5")
        s.poll()
        assertEquals(UpdateNotice.Action.Download("0.3.5"), s.notices.last().action)
        assertEquals(2, s.notices.size)
        assertEquals(3, s.files.checks.size)
        s.files.release = release("0.3.4")
        s.poll()
        assertEquals(2, s.notices.size, "Restoring a previous latest release must not repeat its prompt")
        assertTrue(s.files.downloads.isEmpty())
    }

    @Test fun `an explicit click downloads only its offered version`() = session { s ->
        s.updater.start()
        s.drain()
        s.updater.download("0.3.4")
        s.drain()
        assertEquals(listOf("0.3.4"), s.files.downloads)
        assertEquals("Odyssey 0.3.4 will install when Minecraft closes.", s.updater.status)
    }

    @Test fun `a stale chat button cannot download a newer candidate`() = session { s ->
        s.updater.start()
        s.drain()
        s.files.release = release("0.3.5")
        s.poll()
        s.updater.download("0.3.4")
        s.drain()
        assertTrue(s.files.downloads.isEmpty())
        assertTrue("offer has changed" in s.notices.last().text)
        s.updater.download("0.3.5")
        s.drain()
        assertEquals(listOf("0.3.5"), s.files.downloads)
    }

    @Test fun `manual downloads still work with automatic checks disabled`() = session(false) { s ->
        s.updater.start()
        s.drain()
        assertTrue(s.files.checks.isEmpty())
        assertTrue(s.worker.timers.isEmpty())
        s.updater.download()
        s.drain()
        assertEquals(listOf(true), s.files.checks)
        assertEquals(listOf("0.3.4"), s.files.downloads)
    }

    @Test fun `incompatible releases and manual instances never download`() = session { s ->
        s.files.incompatible = "Java 25"
        s.updater.check()
        s.drain()
        assertEquals(UpdateNotice.Action.Releases, s.notices.last().action)
        s.updater.download()
        s.drain()
        assertTrue(s.files.downloads.isEmpty())
        s.files.incompatible = null
        s.files.canDownload = false
        s.updater.check()
        s.drain()
        assertEquals(UpdateNotice.Action.Releases, s.notices.last().action)
        s.updater.download("0.3.4")
        s.drain()
        assertTrue(s.files.downloads.isEmpty())
    }

    @Test fun `a pending update is retried only after explicit approval`() = session { s ->
        s.files.pendingRelease = release("0.3.4")
        s.updater.start()
        s.drain()
        assertEquals(UpdateNotice.Action.Download("0.3.4"), s.notices.single().action)
        s.poll()
        assertEquals(1, s.notices.size)
        assertTrue(s.files.downloads.isEmpty())
        s.updater.download("0.3.5")
        s.drain()
        assertTrue(s.files.downloads.isEmpty())
        s.updater.download("0.3.4")
        s.drain()
        assertEquals(listOf("0.3.4"), s.files.downloads)
    }

    @Test fun `failed background checks back off quietly and recover`() = session { s ->
        s.files.unavailable = true
        s.updater.start()
        s.drain()
        assertTrue(s.worker.timers.last().minutes in 50..70)
        assertTrue(s.notices.isEmpty())
        repeat(4) { s.poll() }
        assertEquals(240, s.worker.timers.last().minutes)
        s.files.unavailable = false
        s.poll()
        assertTrue(s.worker.timers.last().minutes in 25..35)
        assertEquals(UpdateNotice.Action.Download("0.3.4"), s.notices.single().action)
        assertTrue(s.files.downloads.isEmpty())
    }

    @Test fun `opt-out persists immediately while the worker is occupied`() = session { s ->
        val entered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val path = root.resolve("config/odyssey/config.json")
        try {
            s.worker.execute { entered.countDown(); releaseWorker.await(5, TimeUnit.SECONDS) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            s.updater.setChecks(true)
            s.updater.setChecks(false)
            assertFalse(OdysseyConfig.load(path).checkUpdates)
            assertEquals(1L, releaseWorker.count)
            releaseWorker.countDown()
            s.drain()
            assertTrue(s.files.checks.isEmpty())
            assertTrue(s.files.downloads.isEmpty())
            assertTrue(s.worker.timers.isEmpty())
        } finally { releaseWorker.countDown() }
    }

    @Test fun `disabling checks and closing the client cancel scheduled work`() = session { s ->
        s.updater.start()
        s.drain()
        val timer = s.worker.timers.last()
        s.updater.setChecks(false)
        assertTrue(timer.future.isCancelled)
        s.updater.setChecks(true)
        s.drain()
        assertTrue(s.files.downloads.isEmpty())
        val enabledTimer = s.worker.timers.last()
        s.updater.stop()
        assertTrue(enabledTimer.future.isCancelled)
        assertTrue(s.worker.isShutdown)
        val checks = s.files.checks.size
        s.updater.check()
        s.updater.download()
        assertEquals(checks, s.files.checks.size)
        assertTrue(s.files.downloads.isEmpty())
    }

    @Test fun `opting out while metadata is arriving suppresses the automatic prompt`() = session { s ->
        val entered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        s.files.beforeLatest = { entered.countDown(); releaseWorker.await(5, TimeUnit.SECONDS) }
        try {
            s.updater.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            s.updater.setChecks(false)
            releaseWorker.countDown()
            s.drain()
            assertTrue(s.notices.none { it.action is UpdateNotice.Action.Download })
            assertTrue(s.files.downloads.isEmpty())
            assertTrue(s.worker.timers.isEmpty())
        } finally { releaseWorker.countDown() }
    }

    private fun session(enabled: Boolean = true, test: (Session) -> Unit) {
        val path = root.resolve("config/odyssey/config.json")
        val worker = ControlledWorker()
        val files = FakeFiles()
        val notices = CopyOnWriteArrayList<UpdateNotice>()
        val updater = OdysseyUpdater("0.3.3", OdysseyConfig(checkUpdates = enabled), notices::add,
            files, worker) { OdysseyConfig.save(it, path) }
        try { test(Session(updater, files, worker, notices)) }
        finally { updater.stop(); assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    private data class Session(val updater: OdysseyUpdater, val files: FakeFiles,
                               val worker: ControlledWorker, val notices: List<UpdateNotice>) {
        fun drain() { repeat(2) { worker.submit {}.get(2, TimeUnit.SECONDS) } }
        fun poll() {
            val timer = worker.timers.last()
            assertFalse(timer.future.isCancelled)
            worker.execute(timer.task)
            drain()
        }
    }

    private class FakeFiles : UpdateFiles {
        override var canDownload = true
        var release = release("0.3.4")
        var pendingRelease: VerifiedUpdate? = null
        var incompatible: String? = null
        var unavailable = false
        var beforeLatest: () -> Unit = {}
        val checks = mutableListOf<Boolean>()
        val downloads = mutableListOf<String>()
        override fun latest(manual: Boolean): VerifiedUpdate {
            checks.add(manual)
            beforeLatest()
            if (unavailable) throw IOException("Fixture release host unavailable")
            return release
        }
        override fun pending() = pendingRelease
        override fun consumeInstalledVersion(): String? = null
        override fun missing(release: VerifiedUpdate) = incompatible
        override fun download(release: VerifiedUpdate, canCommit: () -> Boolean): Boolean {
            if (!canCommit()) return false
            downloads.add(release.manifest.version())
            return true
        }
    }

    /** Run real executor jobs, but capture long timers so tests can advance without sleeping. */
    private class ControlledWorker : ScheduledThreadPoolExecutor(1) {
        data class Timer(val task: Runnable, val minutes: Int, val future: ScheduledFuture<Unit>)
        val timers = CopyOnWriteArrayList<Timer>()
        override fun schedule(task: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            if (delay == 0L) return super.schedule(task, delay, unit)
            val sourceUnit = unit
            val future = object : FutureTask<Unit>(Callable { }), ScheduledFuture<Unit> {
                override fun getDelay(unit: TimeUnit): Long = unit.convert(delay, sourceUnit)
                override fun compareTo(other: Delayed): Int = 0
            }
            timers.add(Timer(task, TimeUnit.MINUTES.convert(delay, unit).toInt(), future))
            return future
        }
    }

    companion object {
        private fun release(version: String) = VerifiedUpdate(UpdateManifest(version,
            URI.create(UpdateManifest.RELEASES + "download/v$version/odyssey-mod.jar"), 1,
            "0".repeat(64), "0".repeat(64), emptyMap()), byteArrayOf(), byteArrayOf())
    }
}
