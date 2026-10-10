package org.odyssey.mod.update

import org.junit.jupiter.api.io.TempDir
import org.odyssey.mod.config.OdysseyConfig
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OdysseyUpdaterTest {
    @TempDir lateinit var root: Path

    @Test fun `automatic updates default on unless explicitly disabled`() {
        val path = root.resolve("config/odyssey/config.json")
        assertTrue(OdysseyConfig.load(path).autoUpdate)
        assertTrue(OdysseyConfig.load(path).autoUpdate)
        Files.writeString(path, """{"autoConnect":false,"bridgeVisible":true}""")
        val legacy = OdysseyConfig.load(path)
        assertTrue(legacy.autoUpdate)
        assertFalse(legacy.autoConnect)
        assertTrue(OdysseyConfig.load(path).autoUpdate)
        OdysseyConfig.save(legacy.copy(autoUpdate = true), path)
        assertTrue(OdysseyConfig.load(path).autoUpdate)
        OdysseyConfig.save(legacy.copy(autoUpdate = false), path)
        assertFalse(OdysseyConfig.load(path).autoUpdate)
    }

    @Test fun `opt-out survives restart while the update worker is occupied`() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            worker.submit { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val path = root.resolve("config/odyssey/config.json")
            val config = OdysseyConfig(autoConnect = false, autoUpdate = true)
            OdysseyConfig.save(config, path)
            val notices = mutableListOf<UpdateNotice>()
            val updater = OdysseyUpdater("0.2.0", config, notices::add, worker) {
                OdysseyConfig.save(it, path)
            }

            updater.setAutomatic(false)

            assertEquals(config.copy(autoUpdate = false), OdysseyConfig.load(path))
            assertEquals("Automatic updates disabled.", notices.single().text)
            assertEquals(1L, release.count)
            updater.setAutomatic(true)
            updater.setAutomatic(false)
            release.countDown()
            worker.submit {}.get(2, TimeUnit.SECONDS)
            assertFalse(OdysseyConfig.load(path).autoUpdate)
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
