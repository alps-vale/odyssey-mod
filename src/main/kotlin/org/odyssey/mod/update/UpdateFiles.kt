package org.odyssey.mod.update

import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.metadata.ModOrigin
import org.odyssey.mod.config.OdysseyConfig
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

internal data class VerifiedUpdate(val manifest: UpdateManifest, val bytes: ByteArray, val signature: ByteArray)

/** Verified release metadata and the installed JAR, separate from notification/check scheduling. */
internal interface UpdateFiles {
    val canDownload: Boolean
    fun latest(manual: Boolean): VerifiedUpdate?
    fun pending(): VerifiedUpdate?
    fun consumeInstalledVersion(): String?
    fun missing(release: VerifiedUpdate): String?
    fun download(release: VerifiedUpdate, canCommit: () -> Boolean): Boolean
}

internal class FabricUpdateFiles : UpdateFiles {
    private val loader = FabricLoader.getInstance()
    private val target = installedJar()
    private val cache by lazy { UpdateCache(OdysseyConfig.directory.resolve("updates")) }
    private val transport = UpdateTransport()
    override val canDownload get() = target != null

    override fun latest(manual: Boolean): VerifiedUpdate? {
        val (bytes, signature) = cache.read(manual) {
            val latest = UpdateManifest.RELEASES + "latest/download/"
            transport.bytes(URI.create(latest + "update.manifest"), UpdateManifest.MAX_MANIFEST) to
                transport.bytes(URI.create(latest + "update.manifest.sig"), 64)
        } ?: return null
        val manifest = UpdateManifest.verify(bytes, signature, UpdateManifest.releaseKey())
        cache.save(bytes, signature)
        return VerifiedUpdate(manifest, bytes, signature)
    }

    override fun pending(): VerifiedUpdate? {
        val jar = target ?: return null
        if (UpdateApplier.reconcile(jar, UpdateManifest.releaseKey()) != UpdateApplier.PendingState.PENDING) return null
        val directory = UpdateApplier.directory(jar)
        val bytes = UpdateManifest.readLimited(directory.resolve("update.manifest"), UpdateManifest.MAX_MANIFEST)
        val signature = UpdateManifest.readLimited(directory.resolve("update.manifest.sig"), 64)
        return VerifiedUpdate(UpdateManifest.verify(bytes, signature, UpdateManifest.releaseKey()), bytes, signature)
    }

    override fun consumeInstalledVersion(): String? {
        val jar = target ?: return null
        val receipt = UpdateApplier.directory(jar).resolve("installed.txt")
        if (!Files.exists(receipt)) return null
        val version = Files.readString(receipt).trim()
        Files.delete(receipt)
        return version
    }

    override fun missing(release: VerifiedUpdate): String? = UpdateCompatibility.missing(release.manifest,
        loader.allMods.associate { it.metadata.id to it.metadata.version.friendlyString } +
            ("java" to Runtime.version().feature().toString()))

    override fun download(release: VerifiedUpdate, canCommit: () -> Boolean): Boolean {
        val jar = requireNotNull(target) { "This instance needs a manual update." }
        val pending = pending()
        if (pending == null) {
            if (!UpdateStager.stage(jar, release.manifest, release.bytes, release.signature, transport,
                    { UpdateCompatibility.verifyMetadata(it, release.manifest) }, canCommit)) return false
        } else require(pending.manifest == release.manifest && pending.bytes.contentEquals(release.bytes) &&
            pending.signature.contentEquals(release.signature)) { "The pending update changed. Check again." }
        if (!canCommit()) return false
        UpdateApplier.acknowledgeRollback(jar)
        launchHelper(jar)
        return true
    }

    private fun launchHelper(jar: Path) {
        val directory = UpdateApplier.directory(jar)
        UpdateApplier.validatePaths(jar, directory)
        val helper = directory.resolve("helper.jar")
        val bytes = javaClass.getResourceAsStream("/updates/odyssey-update-helper.jar").use {
            requireNotNull(it) { "Missing update helper" }.readNBytes(256 * 1024)
        }
        // Don't rewrite a helper that may still be open on Windows.
        if (!Files.exists(helper) || UpdateManifest.digest(helper) != UpdateManifest.digest(bytes))
            UpdateApplier.atomicWrite(helper, bytes)
        val process = ProcessHandle.current()
        val start = process.info().startInstant().orElseThrow()
        val java = Path.of(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        ProcessBuilder(java.toString(), "-jar", helper.toString(), jar.toString(),
            process.pid().toString(), start.toString(),
            UpdateApplier.load(directory.resolve("pending.properties")).getProperty("transactionId"))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("helper.log").toFile()))
            .redirectErrorStream(true).start()
    }

    private fun installedJar(): Path? = runCatching {
        val origin = loader.getModContainer("odyssey").orElseThrow().origin
        require(origin.kind == ModOrigin.Kind.PATH && origin.paths.size == 1)
        val path = origin.paths.single().toRealPath()
        require(Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar"))
        require(path.parent == loader.gameDir.resolve("mods").toRealPath())
        path
    }.getOrNull()
}
