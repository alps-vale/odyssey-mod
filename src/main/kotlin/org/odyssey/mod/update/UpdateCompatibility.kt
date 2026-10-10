package org.odyssey.mod.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.loader.api.Version
import net.fabricmc.loader.api.metadata.version.VersionPredicate
import java.nio.file.Path
import java.util.zip.ZipFile

internal object UpdateCompatibility {
    fun newer(candidate: String, installed: String): Boolean =
        Version.parse(candidate) > Version.parse(installed)

    fun missing(manifest: UpdateManifest, installed: Map<String, String>): String? =
        manifest.requirements().entries.sortedBy { it.key }.firstOrNull { (id, requirement) ->
            installed[id]?.let { VersionPredicate.parse(requirement).test(Version.parse(it)) } != true
        }?.let { (id, requirement) -> "$id $requirement" }

    fun verifyMetadata(jar: Path, manifest: UpdateManifest) {
        ZipFile(jar.toFile()).use { zip ->
            val metadata = zip.getInputStream(zip.getEntry("fabric.mod.json")).use {
                Json.parseToJsonElement(it.readNBytes(UpdateManifest.MAX_MANIFEST).decodeToString()).jsonObject
            }
            require(metadata["id"]?.jsonPrimitive?.content == "odyssey") { "Wrong mod ID" }
            require(metadata["version"]?.jsonPrimitive?.content == manifest.version()) { "Wrong mod version" }
            require(metadata["environment"]?.jsonPrimitive?.content == "client") { "Wrong mod environment" }
            val dependencies = metadata.getValue("depends").jsonObject.mapValues { it.value.jsonPrimitive.content }
            require(dependencies == manifest.requirements()) { "Release compatibility metadata changed" }
        }
    }
}
