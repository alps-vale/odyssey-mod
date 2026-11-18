package org.odyssey.mod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.loader.api.Version
import net.fabricmc.loader.api.metadata.version.VersionPredicate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseMetadataTest {
    // Fabric Loader and the updater both see only the Java feature number, so the
    // release can require the Java 21 line but cannot check its security update.
    @Test fun `release metadata accepts only the Java 21 line`() {
        val metadata = Json.parseToJsonElement(javaClass.getResource("/fabric.mod.json")!!.readText()).jsonObject
        val java = VersionPredicate.parse(metadata.getValue("depends").jsonObject.getValue("java").jsonPrimitive.content)
        assertTrue(java.test(Version.parse("21")))
        listOf("20", "25", "27").forEach { assertFalse(java.test(Version.parse(it)), it) }
    }
}
