package org.odyssey.mod.update

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCacheTest {
    @TempDir lateinit var root: Path

    @Test fun `a periodic check can discover a release without waiting a day`() {
        var now = Instant.parse("2026-10-11T12:00:00Z")
        val cache = UpdateCache(root) { now }
        val first = cache.read(false) { byteArrayOf(1) to byteArrayOf(2) }!!
        cache.save(first.first, first.second)
        now = now.plus(24, ChronoUnit.MINUTES)
        assertContentEquals(byteArrayOf(1), cache.read(false) { error("Reuse recent metadata") }!!.first)
        now = now.plus(1, ChronoUnit.MINUTES)
        assertContentEquals(byteArrayOf(3), cache.read(false) { byteArrayOf(3) to byteArrayOf(4) }!!.first)
    }

    @Test fun `failed refresh cannot mark an older complete cache as fresh after restart`() {
        val cache = UpdateCache(root)
        cache.save(byteArrayOf(1), byteArrayOf(2))
        Files.writeString(root.resolve("checked.txt"), Instant.now().minus(25, ChronoUnit.HOURS).toString())

        assertFailsWith<IOException> { cache.read(false) { throw IOException("Unavailable") } }

        assertFalse(Files.exists(root.resolve("update.manifest")))
        assertFalse(Files.exists(root.resolve("update.manifest.sig")))
        assertTrue(Files.isRegularFile(root.resolve("checked.txt")))
        val restarted = UpdateCache(root)
        assertNull(restarted.read(false) { error("A recent failed attempt must retain its backoff") })
        val refreshed = restarted.read(true) { byteArrayOf(3) to byteArrayOf(4) }!!
        restarted.save(refreshed.first, refreshed.second)
        val cached = UpdateCache(root).read(false) { error("Use the verified recent cache") }!!
        assertContentEquals(byteArrayOf(3), cached.first)
        assertContentEquals(byteArrayOf(4), cached.second)
    }
}
