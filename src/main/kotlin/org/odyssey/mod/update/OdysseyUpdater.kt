package org.odyssey.mod.update

import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.metadata.ModOrigin
import org.odyssey.mod.OdysseyDiagnostics
import org.odyssey.mod.config.OdysseyConfig
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal data class UpdateNotice(val text: String, val action: Action? = null, val warning: Boolean = false) {
    enum class Action { INSTALL, RELEASES }
}

/** All network/disk work runs off the game thread; notices are delivered by the caller. */
internal class OdysseyUpdater(
    private val version: String,
    private var config: OdysseyConfig,
    private val notify: (UpdateNotice) -> Unit,
) {
    private val loader = FabricLoader.getInstance()
    private val target = installedJar()
    private val cache = loader.configDir.resolve("odyssey-updates")
    private val transport = UpdateTransport()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Odyssey updates").apply { isDaemon = true }
    }
    private val queued = AtomicInteger()
    private var candidate: Candidate? = null
    private var rollbackPaused = false
    @Volatile private var automatic = config.autoUpdate
    @Volatile var status = "Updates have not been checked yet."
        private set

    fun start() = submit(false) {
        rollbackPaused = target?.let(UpdateApplier::wasRolledBack) ?: false
        if (rollbackPaused && automatic)
            notify(UpdateNotice("Automatic updates paused after a rollback. Use /odyssey update auto on to resume.",
                warning = true))
        if (pendingState() == UpdateApplier.PendingState.PENDING) {
            // Don't endlessly retry a failed transaction. A user can request another attempt.
            status = "The last update was not installed. Use /odyssey update install to retry."
            notify(UpdateNotice(status, warning = true))
        } else {
            target?.let { jar ->
                val receipt = UpdateApplier.directory(jar).resolve("installed.txt")
                if (Files.exists(receipt)) {
                    val installed = Files.readString(receipt).trim()
                    if (installed == version) notify(UpdateNotice("Odyssey $version is installed."))
                    Files.delete(receipt)
                }
            }
            checkRelease(false)
        }
    }

    fun check() = submit(true) { checkRelease(true) }

    fun install() = submit(true) {
        val jar = target ?: error("This instance needs a manual update.")
        val directory = UpdateApplier.directory(jar)
        if (pendingState() == UpdateApplier.PendingState.PENDING) {
            // This also covers recovery after Minecraft/helper was killed before installation.
            val manifest = UpdateManifest.verify(UpdateManifest.readLimited(directory.resolve("update.manifest"), UpdateManifest.MAX_MANIFEST),
                UpdateManifest.readLimited(directory.resolve("update.manifest.sig"), 64), UpdateManifest.releaseKey())
            require(UpdateCompatibility.missing(manifest, installedVersions()) == null)
            launchHelper(jar)
            stagedNotice(manifest.version())
        } else {
            if (candidate == null) checkRelease(true, installAutomatic = false)
            candidate?.let { stage(jar, it) }
                ?: notify(UpdateNotice("No compatible update is available."))
        }
    }

    fun setAutomatic(enabled: Boolean) {
        // Honour an opt-out immediately, including while a release check is fetching metadata.
        automatic = enabled
        submit(true, queue = true) {
            // Persist settings/rollback acknowledgement in order; never drop an opt-out as busy.
            if (enabled) {
                target?.let(UpdateApplier::acknowledgeRollback)
                rollbackPaused = false
            }
            config = config.copy(autoUpdate = enabled)
            OdysseyConfig.save(config)
            notify(UpdateNotice(if (enabled) "Automatic updates enabled. Updates install when Minecraft closes."
                else "Automatic updates disabled."))
            if (enabled && automatic) checkRelease(true)
        }
    }

    private fun checkRelease(manual: Boolean, installAutomatic: Boolean = true) {
        if (pendingState() == UpdateApplier.PendingState.PENDING) {
            if (manual) notify(UpdateNotice(status))
            return
        }
        Files.createDirectories(cache)
        val stamp = cache.resolve("checked.txt")
        val recent = runCatching {
            val age = Duration.between(Instant.parse(Files.readString(stamp)), Instant.now())
            !age.isNegative && age < Duration.ofHours(24)
        }.getOrDefault(false)
        val manifestPath = cache.resolve("update.manifest")
        val signaturePath = cache.resolve("update.manifest.sig")
        val bytes: ByteArray
        val signature: ByteArray
        if (!manual && recent && Files.exists(manifestPath) && Files.exists(signaturePath)) {
            bytes = UpdateManifest.readLimited(manifestPath, UpdateManifest.MAX_MANIFEST)
            signature = UpdateManifest.readLimited(signaturePath, 64)
        } else {
            // Remember failed attempts too. A manual check always bypasses the cache.
            if (!manual && recent) return
            UpdateApplier.atomicWrite(stamp, Instant.now().toString().toByteArray())
            val latest = UpdateManifest.RELEASES + "latest/download/"
            bytes = transport.bytes(URI.create(latest + "update.manifest"), UpdateManifest.MAX_MANIFEST)
            signature = transport.bytes(URI.create(latest + "update.manifest.sig"), 64)
        }
        val manifest = UpdateManifest.verify(bytes, signature, UpdateManifest.releaseKey())
        UpdateApplier.atomicWrite(manifestPath, bytes)
        UpdateApplier.atomicWrite(signaturePath, signature)
        candidate = null
        if (!UpdateCompatibility.newer(manifest.version(), version)) {
            status = "Odyssey is up to date."
            if (manual) notify(UpdateNotice(status))
            return
        }
        val missing = UpdateCompatibility.missing(manifest, installedVersions())
        if (missing != null) {
            status = "Odyssey ${manifest.version()} needs $missing. Update your instance first."
            if (manual) notify(UpdateNotice(status, UpdateNotice.Action.RELEASES, true))
            return
        }
        candidate = Candidate(manifest, bytes, signature)
        status = "Odyssey ${manifest.version()} is available."
        if (target == null) notify(UpdateNotice(status, UpdateNotice.Action.RELEASES))
        else if (automatic && !rollbackPaused && installAutomatic) stage(target, candidate!!, automaticInstall = true)
        else notify(UpdateNotice(status, UpdateNotice.Action.INSTALL))
    }

    private fun stage(jar: Path, release: Candidate, automaticInstall: Boolean = false) {
        val committed = UpdateStager.stage(jar, release.manifest, release.bytes, release.signature, transport,
            { UpdateCompatibility.verifyMetadata(it, release.manifest) },
            { !automaticInstall || automatic })
        if (!committed) {
            status = "Automatic update cancelled."
            notify(UpdateNotice(status))
            return
        }
        launchHelper(jar)
        stagedNotice(release.manifest.version())
    }

    private fun stagedNotice(releaseVersion: String) {
        status = "Odyssey $releaseVersion will install when Minecraft closes."
        notify(UpdateNotice(status))
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

    private fun pendingState(): UpdateApplier.PendingState = target?.let {
        UpdateApplier.reconcile(it, UpdateManifest.releaseKey())
    } ?: UpdateApplier.PendingState.NONE

    private fun installedJar(): Path? = runCatching {
        val origin = loader.getModContainer("odyssey").orElseThrow().origin
        require(origin.kind == ModOrigin.Kind.PATH && origin.paths.size == 1)
        val path = origin.paths.single().toRealPath()
        require(Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar"))
        require(path.parent == loader.gameDir.resolve("mods").toRealPath())
        path
    }.getOrNull()

    private fun installedVersions(): Map<String, String> = loader.allMods.associate {
        it.metadata.id to it.metadata.version.friendlyString
    } + ("java" to Runtime.version().feature().toString())

    private fun submit(manual: Boolean, queue: Boolean = false, task: () -> Unit) {
        if (queue) queued.incrementAndGet()
        else if (!queued.compareAndSet(0, 1)) {
            if (manual) notify(UpdateNotice("An update check is already running."))
            return
        }
        worker.execute {
            try { task() }
            catch (error: Exception) {
                OdysseyDiagnostics.logger.warn("[Odyssey Mod] Update operation failed", error)
                status = "Couldn't update Odyssey. Your installed JAR is unchanged; try again or update manually."
                if (manual) notify(UpdateNotice(status, UpdateNotice.Action.RELEASES, true))
            } finally { queued.decrementAndGet() }
        }
    }

    private data class Candidate(val manifest: UpdateManifest, val bytes: ByteArray, val signature: ByteArray)
}
