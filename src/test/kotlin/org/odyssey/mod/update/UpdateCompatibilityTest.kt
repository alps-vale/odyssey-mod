package org.odyssey.mod.update

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCompatibilityTest {
    @Test fun `uses Fabric semantic versions and installed dependency predicates`() {
        assertTrue(UpdateCompatibility.newer("0.10.0", "0.9.0"))
        assertTrue(UpdateCompatibility.newer("0.2.0", "0.2.0-SNAPSHOT"))
        assertFalse(UpdateCompatibility.newer("0.1.0", "0.2.0"))
        assertFalse(UpdateCompatibility.newer("0.2.0", "0.2.0"))
        val manifest = UpdateManifest("0.2.0", URI.create("https://example.com"), 1, "", "",
            mapOf("minecraft" to "1.21.11", "java" to ">=21", "fabricloader" to ">=0.19.3"))
        val versions = mapOf("minecraft" to "1.21.11", "java" to "21", "fabricloader" to "0.19.3")
        assertNull(UpdateCompatibility.missing(manifest, versions))
        assertEquals("minecraft 1.21.11", UpdateCompatibility.missing(manifest, versions + ("minecraft" to "1.21.10")))
        assertNull(UpdateCompatibility.missing(manifest, versions + ("java" to "25")))
        assertEquals("java >=21", UpdateCompatibility.missing(manifest, versions + ("java" to "20")))
        assertEquals("fabricloader >=0.19.3", UpdateCompatibility.missing(manifest, versions - "fabricloader"))
    }
}
