package org.odyssey.mod.update

import org.odyssey.mod.OdysseyDiagnostics
import org.odyssey.mod.config.OdysseyConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

internal data class UpdateNotice(val text: String, val action: Action? = null, val warning: Boolean = false) {
    sealed interface Action {
        data class Download(val version: String) : Action
        data object Releases : Action
    }
}

/** Checks only offer releases. Downloading always requires an explicit user action. */
internal class OdysseyUpdater(
    private val version: String,
    private var config: OdysseyConfig,
    private val notify: (UpdateNotice) -> Unit,
    private val files: UpdateFiles = FabricUpdateFiles(),
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Odyssey updates").apply { isDaemon = true }
    },
    private val saveConfig: (OdysseyConfig) -> Unit = OdysseyConfig::save,
) {
    private val queued = AtomicInteger()
    private val scheduling = Any()
    private var nextCheck: ScheduledFuture<*>? = null
    private var failures = 0
    private var candidate: VerifiedUpdate? = null
    private val announced = mutableSetOf<String>()
    @Volatile private var checksEnabled = config.checkUpdates
    @Volatile private var closed = false
    @Volatile var status = "Updates have not been checked yet."
        private set

    fun start() = submit(false, automaticRun = true) {
        val pending = files.pending()
        if (pending != null) {
            status = "The last update was not installed. Click Download to retry."
            notify(UpdateNotice(status, UpdateNotice.Action.Download(pending.manifest.version()), true))
        } else {
            if (files.consumeInstalledVersion() == version)
                notify(UpdateNotice("Odyssey $version is installed."))
            if (checksEnabled) checkRelease(false)
        }
    }

    fun check() = submit(true) { checkRelease(true) }

    /** A chat button includes its offered version, so stale buttons cannot select another release. */
    fun download(expectedVersion: String? = null) = submit(true) {
        if (!files.canDownload) {
            notify(UpdateNotice("This instance needs a manual update.", UpdateNotice.Action.Releases))
            return@submit
        }
        val pending = files.pending()
        if (pending == null && candidate == null) checkRelease(true)
        val release = pending ?: candidate
        if (release == null) {
            notify(UpdateNotice("No compatible update is available."))
            return@submit
        }
        if (expectedVersion != null && release.manifest.version() != expectedVersion) {
            notify(UpdateNotice("That update offer has changed. Run /odyssey update check for the current version."))
            return@submit
        }
        val missing = files.missing(release)
        if (missing != null) {
            notify(UpdateNotice("Odyssey ${release.manifest.version()} needs $missing. Update your instance first.",
                UpdateNotice.Action.Releases, true))
            return@submit
        }
        notify(UpdateNotice("Downloading Odyssey ${release.manifest.version()}…"))
        if (files.download(release) { !closed }) {
            status = "Odyssey ${release.manifest.version()} will install when Minecraft closes."
            notify(UpdateNotice(status))
        }
    }

    fun setChecks(enabled: Boolean) {
        // Persist an opt-out even while the background worker is occupied.
        if (!enabled) checksEnabled = false
        synchronized(scheduling) { nextCheck?.cancel(false); nextCheck = null }
        val next = config.copy(checkUpdates = enabled)
        try { saveConfig(next) }
        catch (error: Exception) {
            OdysseyDiagnostics.logger.warn("[Odyssey Mod] Could not save update preference", error)
            notify(UpdateNotice(if (enabled) "Couldn't save the update setting. Try again."
                else "Update checks are disabled for this session, but the setting couldn't be saved. Try again.",
                warning = true))
            if (checksEnabled) scheduleNext()
            return
        }
        config = next
        checksEnabled = enabled
        notify(UpdateNotice(if (enabled) "Automatic update checks enabled. Downloads still need your approval."
            else "Automatic update checks disabled."))
        if (enabled) submit(false, queue = true, automaticRun = true) {
            if (checksEnabled) checkRelease(false)
        }
    }

    fun stop() {
        synchronized(scheduling) {
            closed = true
            nextCheck?.cancel(false)
            nextCheck = null
            worker.shutdownNow()
        }
    }

    private fun checkRelease(manual: Boolean) {
        val pending = files.pending()
        if (pending != null) {
            if (manual) notify(UpdateNotice("Odyssey ${pending.manifest.version()} is already downloaded. Click Download to retry installation.",
                UpdateNotice.Action.Download(pending.manifest.version())))
            return
        }
        val release = files.latest(manual) ?: return
        candidate = null
        if (!UpdateCompatibility.newer(release.manifest.version(), version)) {
            status = "Odyssey is up to date."
            if (manual) notify(UpdateNotice(status))
            return
        }
        val missing = files.missing(release)
        if (missing != null) {
            status = "Odyssey ${release.manifest.version()} needs $missing. Update your instance first."
            if (manual) notify(UpdateNotice(status, UpdateNotice.Action.Releases, true))
            return
        }
        candidate = release
        status = "Odyssey ${release.manifest.version()} is available."
        if (!manual && (!checksEnabled || release.manifest.version() in announced)) return
        if (closed) return
        announced.add(release.manifest.version())
        notify(UpdateNotice(status, if (files.canDownload) UpdateNotice.Action.Download(release.manifest.version())
            else UpdateNotice.Action.Releases))
    }

    private fun scheduleNext() = synchronized(scheduling) {
        nextCheck?.cancel(false)
        nextCheck = null
        if (!checksEnabled || closed) return@synchronized
        // Sleep between requests, add jitter, and back off failures up to four hours.
        val minutes = (Random.nextLong(25, 36) * (1L shl failures.coerceAtMost(4))).coerceAtMost(240)
        nextCheck = worker.schedule({
            submit(false, automaticRun = true) { if (checksEnabled) checkRelease(false) }
        }, minutes, TimeUnit.MINUTES)
    }

    private fun submit(manual: Boolean, queue: Boolean = false, automaticRun: Boolean = false, task: () -> Unit) {
        synchronized(scheduling) {
            if (closed) return
            if (queue) queued.incrementAndGet()
            else if (!queued.compareAndSet(0, 1)) {
                if (manual) notify(UpdateNotice("An update operation is already running."))
                if (automaticRun) scheduleNext()
                return
            }
            worker.execute {
                try {
                    if (!closed) task()
                    if (automaticRun) failures = 0
                } catch (error: Exception) {
                    if (automaticRun) failures++
                    OdysseyDiagnostics.logger.warn("[Odyssey Mod] Update operation failed", error)
                    status = "Couldn't check or download the update. Your installed JAR is unchanged; try again."
                    if (manual && !closed) notify(UpdateNotice(status, UpdateNotice.Action.Releases, true))
                } finally {
                    queued.decrementAndGet()
                    if (automaticRun) scheduleNext()
                }
            }
        }
    }
}
