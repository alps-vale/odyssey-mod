package org.odyssey.mod.config

import org.odyssey.mod.OdysseyDiagnostics
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OdysseyConfigTest {
    @Test
    fun `missing config creates and returns defaults`() {
        val path = Files.createTempDirectory("odyssey-config").resolve("nested/odyssey.json")

        val config = OdysseyConfig.load(path)

        assertEquals(OdysseyConfig(), config)
        assertTrue(Files.isRegularFile(path))
        assertEquals(config, OdysseyConfig.load(path))
    }

    @Test
    fun `malformed existing config is replaced with defaults`() {
        val path = Files.createTempDirectory("odyssey-config").resolve("odyssey.json")
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
