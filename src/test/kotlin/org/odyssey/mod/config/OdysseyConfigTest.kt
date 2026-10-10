package org.odyssey.mod.config

import org.odyssey.mod.OdysseyDiagnostics
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OdysseyConfigTest {
    @TempDir lateinit var root: Path

    @Test
    fun `missing config creates and returns defaults`() {
        val directory = root.resolve("config/odyssey")
        val path = directory.resolve("config.json")

        val config = OdysseyConfig.load(path)

        assertEquals(OdysseyConfig(), config)
        assertTrue(Files.isRegularFile(path))
        assertEquals(config, OdysseyConfig.load(path))
    }

    @Test
    fun `malformed existing config is replaced with defaults`() {
        val path = root.resolve("config.json")
        Files.writeString(path, "{not-json")

        val config = OdysseyConfig.load(path)

        assertEquals(OdysseyConfig(), config)
        assertEquals(config, Json.decodeFromString<OdysseyConfig>(Files.readString(path)))
        assertEquals(config, OdysseyConfig.load(path))
    }

    @Test
    fun `diagnostics use stable Odyssey Mod logger name`() {
        assertEquals("Odyssey Mod", OdysseyDiagnostics.logger.name)
    }
}
