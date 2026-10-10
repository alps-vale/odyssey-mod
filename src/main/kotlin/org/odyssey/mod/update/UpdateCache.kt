package org.odyssey.mod.update

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** Failed refreshes retain the attempt backoff, never an older release's cached bytes. */
internal class UpdateCache(directory: Path) {
    private val directory = directory
    private val stamp = directory.resolve("checked.txt")
    private val manifest = directory.resolve("update.manifest")
    private val signature = directory.resolve("update.manifest.sig")

    fun read(manual: Boolean, download: () -> Pair<ByteArray, ByteArray>): Pair<ByteArray, ByteArray>? {
        Files.createDirectories(directory)
        val recent = runCatching {
            val age = Duration.between(Instant.parse(Files.readString(stamp)), Instant.now())
            !age.isNegative && age < Duration.ofHours(24)
        }.getOrDefault(false)
        if (!manual && recent) {
            if (!Files.exists(manifest) || !Files.exists(signature)) return null
            return UpdateManifest.readLimited(manifest, UpdateManifest.MAX_MANIFEST) to
                UpdateManifest.readLimited(signature, 64)
        }
        // Clear both before recording the attempt, including when download/verification later fails.
        Files.deleteIfExists(manifest)
        Files.deleteIfExists(signature)
        UpdateApplier.atomicWrite(stamp, Instant.now().toString().toByteArray())
        return download()
    }

    /** The caller verifies the signature before caching these bytes. */
    fun save(bytes: ByteArray, signed: ByteArray) {
        UpdateApplier.atomicWrite(manifest, bytes)
        UpdateApplier.atomicWrite(signature, signed)
    }
}
