package org.odyssey.mod.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import net.minecraft.client.Minecraft
import org.odyssey.mod.OdysseyDiagnostics
import org.odyssey.mod.chat.OdysseyNotifications
import org.odyssey.mod.config.OdysseyConfig
import java.net.http.WebSocketHandshakeException
import java.time.Instant
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

internal enum class BridgeStage {
    AUTHENTICATING,
    CONNECTING,
    SYNCHRONIZING_PRESENTATION,
}

internal sealed interface BridgeStatus {
    data object Idle : BridgeStatus
    data class Progress(val stage: BridgeStage) : BridgeStatus
    data class Connected(
        val username: String,
        val guildPrefix: String,
        val rank: RankPresentation? = null,
    ) : BridgeStatus
    data class Retrying(val reason: String, val attempt: Int, val delayMillis: Long) : BridgeStatus
    data class Terminal(val code: String, val message: String) : BridgeStatus

    fun summary(): String = when (this) {
        Idle -> "idle"
        is Progress -> when (stage) {
            BridgeStage.AUTHENTICATING -> "authenticating"
            BridgeStage.CONNECTING -> "connecting"
            BridgeStage.SYNCHRONIZING_PRESENTATION -> "synchronizing presentation"
        }
        is Connected -> "connected as $username [$guildPrefix]"
        is Retrying -> "$reason; reconnecting"
        is Terminal -> "$code: $message"
    }
}

internal data class BridgeWarning(val code: String, val message: String)

internal data class LauncherIdentity(val uuid: String, val username: String)
internal interface GameAccess {
    fun launcherIdentity(): LauncherIdentity
    fun joinServer(serverId: String)
    fun execute(action: () -> Unit)
    fun updateStatus(status: BridgeStatus)
    fun showWarning(warning: BridgeWarning)
    fun renderChat(message: ServerMessage.Chat)
}

internal class MinecraftGameAccess(
    private val minecraft: Minecraft = Minecraft.getInstance(),
    private val chatRenderer: (ServerMessage.Chat) -> Unit,
) : GameAccess {
    override fun launcherIdentity(): LauncherIdentity = LauncherIdentity(
        minecraft.user.profileId.toString(),
        minecraft.user.name,
    )

    override fun joinServer(serverId: String) {
        val user = minecraft.user
        minecraft.services().sessionService().joinServer(user.profileId, user.accessToken, serverId)
    }

    override fun execute(action: () -> Unit) {
        minecraft.execute(action)
    }

    override fun updateStatus(status: BridgeStatus) {
        minecraft.execute {
            OdysseyDiagnostics.callback("status notification rendering") {
                val component = OdysseyNotifications.transition(status) ?: return@callback
                minecraft.player?.displayClientMessage(component, false)
            }
        }
    }

    override fun showWarning(warning: BridgeWarning) {
        minecraft.execute {
            OdysseyDiagnostics.callback("warning notification rendering") {
                val component = OdysseyNotifications.warning(warning)
                minecraft.player?.displayClientMessage(component, false)
            }
        }
    }

    override fun renderChat(message: ServerMessage.Chat) {
        OdysseyDiagnostics.callback("bridge chat rendering") {
            chatRenderer(message)
        }
    }
}

internal data class PresentationSnapshot(
    val revision: Long = 0,
    val entries: Map<String, PresentationEntry> = emptyMap(),
)

private data class PresentationTransfer(
    val revision: Long,
    val identityUuid: String,
    val identityUsername: String,
    val guildPrefix: String,
    val entries: LinkedHashMap<String, PresentationEntry> = LinkedHashMap(),
)

internal data class HandshakeFailure(
    val code: String,
    val message: String,
    val clearSession: Boolean,
)

internal fun handshakeFailure(statusCode: Int): HandshakeFailure? = when (statusCode) {
    401 -> HandshakeFailure(
        "token_invalid",
        "Odyssey session expired; run /odyssey reconnect",
        clearSession = true,
    )
    403 -> HandshakeFailure(
        "not_in_odyssey",
        "Current profile is not in Alps or Vale",
        clearSession = false,
    )
    426 -> HandshakeFailure(
        "protocol_invalid",
        "Backend protocol is incompatible",
        clearSession = false,
    )
    else -> null
}

internal object PresentationRepository {
    private val value = AtomicReference(PresentationSnapshot())

    fun snapshot(): PresentationSnapshot = value.get()

    fun replace(snapshot: PresentationSnapshot) {
        value.set(snapshot)
    }
}

internal class BridgeClient(
    private val transport: OdysseyTransport,
    private val game: GameAccess,
    private val config: OdysseyConfig,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SocketEvents {
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val queuedObservations = AtomicInteger()
    private val pending = PendingObservations(100)
    private val seenEvents = EventLru(1_024)
    private var socket: BridgeSocket? = null
    private var session: OdysseySession? = null
    private var launcherUuid: String? = null
    private var serverAddress: String? = null
    private var playable = false
    private var welcomed = false
    private var stoppedByTerminalError = false
    private var reconnectAttempt = 0
    private var reconnectScheduled = false
    private var reconnectGeneration = 0L
    private var reconnectJob: Job? = null
    private var connectionGeneration = 0L
    private var connectionJob: Job? = null
    private var onlineJob: Job? = null
    private val pendingSocketTerminations = IdentityHashMap<BridgeSocket, Command>()
    private val fragments = StringBuilder()
    private var presentationTransfer: PresentationTransfer? = null
    @Volatile
    private var currentStatus: BridgeStatus = BridgeStatus.Idle

    init {
        scope.launch { actorLoop() }
    }

    fun updateEnvironment(address: String?, playable: Boolean) {
        commands.trySend(Command.Environment(address, playable))
    }

    fun observe(authorUsername: String, content: String, itemShares: List<ItemShare> = emptyList()) {
        // Keep control messages reliable, but do not queue unbounded image bytes behind them.
        val shares = if (queuedObservations.incrementAndGet() > 16) itemShares.map { it.copy(png = null) } else itemShares
        if (commands.trySend(Command.Observe(authorUsername, content, shares)).isFailure) queuedObservations.decrementAndGet()
    }

    fun reconnect() {
        commands.trySend(Command.Reconnect)
    }

    fun status(): BridgeStatus = currentStatus

    fun online(callback: (Result<GuildOnlineSnapshot>) -> Unit) {
        commands.trySend(Command.Online(callback))
    }

    fun stop() {
        commands.trySend(Command.Stop)
    }

    override fun text(socket: BridgeSocket, fragment: String, last: Boolean) {
        commands.trySend(Command.Text(socket, fragment, last))
    }

    override fun binary(socket: BridgeSocket, bytes: ByteArray, last: Boolean) {
        commands.trySend(Command.Binary(socket))
    }

    override fun ping(socket: BridgeSocket, bytes: ByteArray) {
        commands.trySend(Command.Ping(socket, bytes))
    }

    override fun pong(socket: BridgeSocket, bytes: ByteArray) {
        commands.trySend(Command.Pong(socket))
    }

    override fun closed(socket: BridgeSocket, statusCode: Int, reason: String) {
        commands.trySend(Command.Closed(socket, statusCode, reason))
    }

    override fun failed(socket: BridgeSocket, error: Throwable) {
        commands.trySend(Command.Failed(socket, error))
    }

    private suspend fun actorLoop() {
        for (command in commands) {
            try {
                when (command) {
                    is Command.Environment -> environment(command)
                    is Command.Observe -> try {
                        observation(command)
                    } finally {
                        queuedObservations.decrementAndGet()
                    }
                    Command.Reconnect -> manualReconnect()
                    is Command.Online -> requestOnline(command)
                    is Command.OnlineCompleted -> {
                        if (command.socket === socket && welcomed && command.generation == connectionGeneration) {
                            onlineJob = null
                            game.execute { command.callback(command.result) }
                        }
                    }
                    is Command.ConnectionProgress -> connectionProgress(command)
                    is Command.ConnectionSucceeded -> connectionSucceeded(command)
                    is Command.ConnectionFailed -> connectionFailed(command)
                    is Command.Text -> text(command)
                    is Command.Binary -> protocolFailure(command.socket, "Binary frame received")
                    is Command.Ping -> if (command.socket === socket) {
                        reconnectAttempt = 0
                        submitOutbound(command.socket) { command.socket.pong(command.bytes) }
                        command.socket.requestNext()
                    }
                    is Command.Pong -> if (command.socket === socket) command.socket.requestNext()
                    is Command.Closed -> socketClosed(command)
                    is Command.Failed -> socketFailed(command)
                    is Command.Retry -> if (
                        reconnectScheduled &&
                        command.generation == reconnectGeneration
                    ) {
                        reconnectScheduled = false
                        reconnectJob = null
                        connectIfEligible()
                    }
                    Command.Stop -> {
                        disconnect("client_stopping", clearSession = true, clearPresentation = true)
                        scope.cancel()
                        return
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                session = null
                terminal(
                    "client_error",
                    "Unexpected Odyssey client failure while handling ${command.javaClass.simpleName}",
                    cause = error,
                )
            }
        }
    }

    private suspend fun environment(command: Command.Environment) {
        serverAddress = command.address
        playable = command.playable
        val identity = runCatching(game::launcherIdentity).getOrNull()
        if (identity != null && launcherUuid != null && launcherUuid != identity.uuid) {
            disconnect("launcher_profile_changed", clearSession = true, clearPresentation = true)
            invalidateIdentity()
            stoppedByTerminalError = false
        }
        if (!eligible()) {
            disconnect("left_wynncraft", clearSession = false, clearPresentation = true)
            OdysseyDiagnostics.logger.debug("[Odyssey Mod] Bridge idle")
            setStatus(BridgeStatus.Idle)
            return
        }
        if (socket == null && !stoppedByTerminalError) connectIfEligible()
        if (welcomed) send(ClientMessage.ObserverState(PROTOCOL_VERSION, playable))
    }

    private suspend fun observation(command: Command.Observe) {
        if (stoppedByTerminalError) return
        val message = ClientMessage.GuildObservation(
            PROTOCOL_VERSION,
            UUID.randomUUID().toString(),
            command.authorUsername,
            command.content,
            command.itemShares,
        )
        runCatching { ProtocolCodec.encode(message) }.getOrElse { error ->
            OdysseyDiagnostics.logger.error("[Odyssey Mod] Guild observation encoding failed", error)
            game.showWarning(
                BridgeWarning(
                    "observation_rejected",
                    "Odyssey could not send one observed guild message. Check the Minecraft log.",
                ),
            )
            return
        }
        pending.add(message)
        if (welcomed) send(message) else if (eligible() && !stoppedByTerminalError) connectIfEligible()
    }

    private fun requestOnline(command: Command.Online) {
        val current = socket
        val authenticated = session
        if (!welcomed || current == null || authenticated == null || !eligible()) {
            game.execute { command.callback(Result.failure(IllegalStateException("Connect Odyssey before viewing guild activity."))) }
            return
        }
        if (onlineJob != null) {
            game.execute { command.callback(Result.failure(IllegalStateException("Guild activity is already loading."))) }
            return
        }
        val generation = connectionGeneration
        onlineJob = scope.launch {
            val result = try {
                Result.success(transport.online(authenticated.token))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                Result.failure(IllegalStateException("Guild activity is unavailable. Try again shortly."))
            }
            commands.send(Command.OnlineCompleted(current, generation, command.callback, result))
        }
    }

    private suspend fun manualReconnect() {
        OdysseyDiagnostics.logger.info("[Odyssey Mod] Manual reconnect requested")
        stoppedByTerminalError = false
        reconnectAttempt = 0
        disconnect("manual_reconnect", clearSession = false)
        connectIfEligible()
    }

    private fun connectIfEligible() {
        if (!eligible() || socket != null || connectionJob != null || stoppedByTerminalError) return
        val generation = ++connectionGeneration
        val knownLauncherUuid = launcherUuid
        val knownSession = session
        connectionJob = scope.launch {
            var identity: LauncherIdentity? = null
            var authenticated: OdysseySession? = null
            var openedSocket: BridgeSocket? = null
            var handedOff = false
            try {
                val currentIdentity = game.launcherIdentity().also { identity = it }
                val currentSession = knownSession?.takeIf {
                    knownLauncherUuid == currentIdentity.uuid && it.expiry().isAfter(Instant.now())
                } ?: run {
                    commands.send(Command.ConnectionProgress(generation, BridgeStage.AUTHENTICATING))
                    authenticate(currentIdentity)
                }
                authenticated = currentSession
                commands.send(Command.ConnectionProgress(generation, BridgeStage.CONNECTING))
                OdysseyDiagnostics.logger.debug("[Odyssey Mod] Connecting bridge socket")
                val currentSocket = transport.openSocket(currentSession.token, this@BridgeClient)
                    .also { openedSocket = it }
                commands.send(
                    Command.ConnectionSucceeded(
                        generation,
                        currentIdentity,
                        currentSession,
                        currentSocket,
                    ),
                )
                handedOff = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                commands.send(Command.ConnectionFailed(generation, identity, authenticated, error))
            } finally {
                if (!handedOff) {
                    openedSocket?.let { runCatching { it.close(1000, "connection_cancelled") } }
                }
            }
        }
    }

    private suspend fun authenticate(identity: LauncherIdentity): OdysseySession {
        OdysseyDiagnostics.logger.debug("[Odyssey Mod] Authenticating bridge session")
        val challenge = transport.challenge()
        runInterruptible { game.joinServer(challenge.serverId) }
        val authenticated = transport.complete(challenge.challengeId, identity.username)
        if (!authenticated.minecraftUuid.equals(identity.uuid, ignoreCase = true)) {
            throw TransportException("identity_conflict", false, "Launcher and Mojang identities differ")
        }
        return authenticated
    }

    private fun connectionProgress(command: Command.ConnectionProgress) {
        if (command.generation != connectionGeneration || connectionJob == null) return
        setStatus(BridgeStatus.Progress(command.stage))
    }

    private suspend fun connectionSucceeded(command: Command.ConnectionSucceeded) {
        if (
            command.generation != connectionGeneration ||
            !eligible() ||
            stoppedByTerminalError ||
            socket != null
        ) {
            pendingSocketTerminations.remove(command.socket)
            submitOutbound(command.socket) { command.socket.close(1000, "connection_superseded") }
            return
        }
        connectionJob = null
        adoptLauncherIdentity(command.identity)
        session = command.session
        welcomed = false
        fragments.clear()
        presentationTransfer = null
        val termination = pendingSocketTerminations.remove(command.socket)
        pendingSocketTerminations.clear()
        socket = command.socket
        when (termination) {
            is Command.Closed -> socketClosed(termination)
            is Command.Failed -> socketFailed(termination)
            else -> command.socket.requestNext()
        }
    }

    private suspend fun connectionFailed(command: Command.ConnectionFailed) {
        if (command.generation != connectionGeneration) return
        connectionJob = null
        pendingSocketTerminations.clear()
        val identity = command.identity
        if (identity == null) {
            session = null
            terminal("client_error", "Unexpected Odyssey client failure while starting connection", cause = command.error)
            return
        }
        adoptLauncherIdentity(identity)
        command.session?.let { session = it }
        handleConnectionError(command.error)
    }

    private fun adoptLauncherIdentity(identity: LauncherIdentity) {
        if (launcherUuid != null && launcherUuid != identity.uuid) invalidateIdentity()
        launcherUuid = identity.uuid
    }

    private fun text(command: Command.Text) {
        if (command.socket !== socket) return
        fragments.append(command.fragment)
        if (fragments.toString().toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) {
            fragments.clear()
            protocolFailure(command.socket, "Frame exceeds 8 KiB")
            return
        }
        if (command.last) {
            val frame = fragments.toString()
            fragments.clear()
            val message = runCatching { ProtocolCodec.decodeServer(ProtocolFrame.Text(frame)) }
                .getOrElse { error ->
                    protocolFailure(command.socket, "Invalid protocol frame", cause = error)
                    return
                }
            handleServerMessage(message)
        }
        command.socket.requestNext()
    }

    private fun handleServerMessage(message: ServerMessage) {
        when (message) {
            is ServerMessage.Welcome -> {
                if (launcherUuid?.equals(message.identity.uuid, ignoreCase = true) != true) {
                    terminal("identity_conflict", "Backend welcomed a different profile")
                    return
                }
                welcomed = false
                presentationTransfer = PresentationTransfer(
                    revision = message.presentationRevision,
                    identityUuid = message.identity.uuid,
                    identityUsername = message.identity.username,
                    guildPrefix = message.guild.prefix,
                )
                OdysseyDiagnostics.logger.debug(
                    "[Odyssey Mod] Synchronizing presentation revision={}",
                    message.presentationRevision,
                )
                setStatus(BridgeStatus.Progress(BridgeStage.SYNCHRONIZING_PRESENTATION))
            }
            is ServerMessage.PresentationSnapshot -> receivePresentationSnapshot(message)
            is ServerMessage.ObservationResult -> {
                pending.acknowledge(message)
                if (message.status == ObservationStatus.REJECTED) {
                    OdysseyDiagnostics.logger.warn(
                        "[Odyssey Mod] Observation rejected id={} reason={}",
                        message.id,
                        message.reason,
                    )
                }
            }
            is ServerMessage.Chat -> if (seenEvents.add(message.eventId) && config.bridgeVisible) {
                game.execute { game.renderChat(message) }
            }
            is ServerMessage.PresentationUpsert -> updatePresentation(message.revision) { entries ->
                entries[message.presentation.minecraftUuid] = message.presentation
            }
            is ServerMessage.PresentationRemove -> updatePresentation(message.revision) { entries ->
                entries.remove(message.minecraftUuid)
            }
            is ServerMessage.Error -> {
                if (!message.retryable || message.code in TERMINAL_CODES) {
                    terminal(message.code, message.message)
                } else {
                    OdysseyDiagnostics.logger.warn(
                        "[Odyssey Mod] Retryable server error code={} message={}",
                        message.code,
                        message.message,
                    )
                    game.showWarning(BridgeWarning(message.code, message.message))
                }
            }
        }
    }

    private fun receivePresentationSnapshot(message: ServerMessage.PresentationSnapshot) {
        val transfer = presentationTransfer
        if (transfer == null || message.revision != transfer.revision) {
            presentationResync(
                "snapshot_without_matching_transfer",
                expectedRevision = transfer?.revision,
                receivedRevision = message.revision,
            )
            return
        }
        if (!message.complete) {
            val entry = message.entries.single()
            if (transfer.entries.containsKey(entry.minecraftUuid)) {
                presentationResync(
                    "duplicate_snapshot_entry",
                    expectedRevision = transfer.revision,
                    receivedRevision = message.revision,
                )
                return
            }
            if (transfer.entries.size >= MAX_PRESENTATION_ENTRIES) {
                presentationTooLarge()
                return
            }
            transfer.entries[entry.minecraftUuid] = entry
            return
        }
        completePresentation(transfer)
    }

    private fun completePresentation(transfer: PresentationTransfer) {
        PresentationRepository.replace(PresentationSnapshot(transfer.revision, transfer.entries.toMap()))
        presentationTransfer = null
        welcomed = true
        cancelScheduledReconnect()
        val identityRank = transfer.entries.values
            .firstOrNull { it.minecraftUuid.equals(transfer.identityUuid, ignoreCase = true) }
            ?.role
        setStatus(BridgeStatus.Connected(transfer.identityUsername, transfer.guildPrefix, identityRank))
        OdysseyDiagnostics.logger.info(
            "[Odyssey Mod] Bridge connected username={} guildPrefix={}",
            transfer.identityUsername,
            transfer.guildPrefix,
        )
        send(ClientMessage.ObserverState(PROTOCOL_VERSION, playable))
        pending.values().forEach(::send)
    }

    private fun updatePresentation(
        revision: Long,
        change: (MutableMap<String, PresentationEntry>) -> Unit,
    ) {
        if (!welcomed || presentationTransfer != null) {
            presentationResync(
                "delta_outside_completed_snapshot",
                receivedRevision = revision,
            )
            return
        }
        val current = PresentationRepository.snapshot()
        if (revision <= current.revision) return
        if (current.revision == Long.MAX_VALUE || revision != current.revision + 1) {
            presentationResync(
                "revision_gap",
                expectedRevision = current.revision.takeUnless { it == Long.MAX_VALUE }?.plus(1),
                receivedRevision = revision,
            )
            return
        }
        val entries = current.entries.toMutableMap()
        change(entries)
        if (entries.size > MAX_PRESENTATION_ENTRIES) {
            presentationTooLarge()
            return
        }
        PresentationRepository.replace(PresentationSnapshot(revision, entries))
    }

    private fun presentationTooLarge() {
        terminal(
            "presentation_too_large",
            "Odyssey presentation exceeds the supported entry limit",
            4406,
        )
    }

    private fun presentationResync(
        reason: String,
        expectedRevision: Long? = null,
        receivedRevision: Long? = null,
    ) {
        OdysseyDiagnostics.logger.warn(
            "[Odyssey Mod] Presentation resync reason={} expectedRevision={} receivedRevision={}",
            reason,
            expectedRevision,
            receivedRevision,
        )
        presentationTransfer = null
        welcomed = false
        val current = socket ?: return
        submitOutbound(current) { current.close(1013, "presentation_resync_required") }
        commands.trySend(Command.Closed(current, 1013, "presentation_resync_required"))
    }

    private suspend fun socketClosed(command: Command.Closed) {
        if (command.socket !== socket) {
            if (socket == null && connectionJob != null) {
                pendingSocketTerminations[command.socket] = command
            }
            return
        }
        cancelOnline()
        socket = null
        welcomed = false
        fragments.clear()
        presentationTransfer = null
        when (command.statusCode) {
            4401 -> {
                session = null
                terminal("token_invalid", "Odyssey session expired; run /odyssey reconnect")
            }
            4403 -> terminal("not_in_odyssey", "Current profile is not in Alps or Vale")
            4429 -> {
                pending.clear()
                scheduleReconnect(
                    "connection closed (4429)",
                    socketStatus = 4429,
                    socketReason = command.reason,
                )
            }
            4406 -> terminal("protocol_invalid", "Backend protocol is incompatible")
            else -> scheduleReconnect(
                "connection closed (${command.statusCode})",
                socketStatus = command.statusCode,
                socketReason = command.reason,
            )
        }
    }

    private suspend fun socketFailed(command: Command.Failed) {
        if (command.socket !== socket) {
            if (socket == null && connectionJob != null) {
                pendingSocketTerminations[command.socket] = command
            }
            return
        }
        submitOutbound(command.socket) { command.socket.close(1011, "transport_failed") }
        cancelOnline()
        socket = null
        welcomed = false
        fragments.clear()
        presentationTransfer = null
        handleConnectionError(command.error)
    }

    private suspend fun handleConnectionError(error: Throwable) {
        val causes = generateSequence(error) { it.cause }.toList()
        val handshake = causes.filterIsInstance<WebSocketHandshakeException>().firstOrNull()
        when {
            error is TransportException && (!error.retryable || error.code in TERMINAL_CODES) -> {
                session = null
                terminal(error.code, error.message ?: error.code, cause = error)
            }
            handshake != null -> {
                val failure = handshakeFailure(handshake.response.statusCode())
                if (failure == null) {
                    scheduleReconnect("WebSocket handshake failed", cause = error)
                } else {
                    if (failure.clearSession) session = null
                    terminal(failure.code, failure.message, cause = error)
                }
            }
            error is TransportException -> scheduleReconnect(
                "${error.code}: ${error.message ?: "Odyssey request failed"}",
                cause = error,
            )
            else -> scheduleReconnect("network error", cause = error)
        }
    }

    private suspend fun scheduleReconnect(
        reason: String,
        cause: Throwable? = null,
        socketStatus: Int? = null,
        socketReason: String? = null,
    ) {
        if (!eligible() || stoppedByTerminalError || reconnectScheduled) return
        reconnectScheduled = true
        val attempt = reconnectAttempt + 1
        val delayMillis = reconnectPolicy.delayMillis(reconnectAttempt)
        reconnectAttempt = attempt
        when {
            cause != null -> OdysseyDiagnostics.logger.warn(
                "[Odyssey Mod] Automatic retry attempt={} delayMillis={} reason={}",
                attempt,
                delayMillis,
                reason,
                cause,
            )
            socketStatus != null -> OdysseyDiagnostics.logger.warn(
                "[Odyssey Mod] Automatic retry attempt={} delayMillis={} reason={} socketStatus={} socketReason={}",
                attempt,
                delayMillis,
                reason,
                socketStatus,
                socketReason?.replace('\n', ' ')?.replace('\r', ' ')?.take(256),
            )
            else -> OdysseyDiagnostics.logger.warn(
                "[Odyssey Mod] Automatic retry attempt={} delayMillis={} reason={}",
                attempt,
                delayMillis,
                reason,
            )
        }
        setStatus(BridgeStatus.Retrying(reason, attempt, delayMillis))
        val generation = ++reconnectGeneration
        reconnectJob = scope.launch {
            delay(delayMillis)
            commands.send(Command.Retry(generation))
        }
    }

    private fun protocolFailure(
        socket: BridgeSocket,
        message: String,
        cause: Throwable? = null,
    ) {
        if (socket !== this.socket) return
        terminal("protocol_invalid", message, 4406, cause)
    }

    private fun terminal(
        code: String,
        message: String,
        closeStatus: Int = 1000,
        cause: Throwable? = null,
    ) {
        if (cause == null) {
            OdysseyDiagnostics.logger.error("[Odyssey Mod] Terminal bridge error code={} message={}", code, message)
        } else {
            OdysseyDiagnostics.logger.error(
                "[Odyssey Mod] Terminal bridge error code={} message={}",
                code,
                message,
                cause,
            )
        }
        stoppedByTerminalError = true
        cancelConnectionAttempt()
        cancelScheduledReconnect()
        socket?.let { current ->
            submitOutbound(current) { current.close(closeStatus, code) }
        }
        socket = null
        welcomed = false
        fragments.clear()
        pending.clear()
        clearPresentation()
        setStatus(BridgeStatus.Terminal(code, message))
    }

    private fun disconnect(reason: String, clearSession: Boolean, clearPresentation: Boolean = false) {
        cancelConnectionAttempt()
        cancelScheduledReconnect()
        reconnectAttempt = 0
        if (reason in NORMAL_DISCONNECT_REASONS) {
            OdysseyDiagnostics.logger.debug("[Odyssey Mod] Bridge disconnected reason={}", reason)
        }
        if (welcomed) send(ClientMessage.ObserverState(PROTOCOL_VERSION, false))
        socket?.let { current ->
            submitOutbound(current) { current.close(1000, reason) }
        }
        socket = null
        welcomed = false
        fragments.clear()
        presentationTransfer = null
        if (clearSession) session = null
        if (clearPresentation) clearPresentation()
    }

    private fun invalidateIdentity() {
        session = null
        launcherUuid = null
        pending.clear()
        seenEvents.clear()
        PresentationRepository.replace(PresentationSnapshot())
    }

    private fun clearPresentation() {
        presentationTransfer = null
        PresentationRepository.replace(PresentationSnapshot())
    }

    private fun send(message: ClientMessage) {
        val current = socket ?: return
        submitOutbound(current) { current.send(ProtocolCodec.encode(message)) }
    }

    private fun submitOutbound(socket: BridgeSocket, operation: () -> java.util.concurrent.CompletionStage<*>) {
        runCatching(operation)
            .onSuccess { stage ->
                stage.whenComplete { _, error ->
                    if (error != null) commands.trySend(Command.Failed(socket, error))
                }
            }
            .onFailure { commands.trySend(Command.Failed(socket, it)) }
    }

    private fun cancelConnectionAttempt() {
        cancelOnline()
        connectionGeneration++
        connectionJob?.cancel()
        pendingSocketTerminations.clear()
        connectionJob = null
    }

    private fun cancelOnline() {
        onlineJob?.cancel()
        onlineJob = null
    }

    private fun cancelScheduledReconnect() {
        reconnectScheduled = false
        reconnectGeneration++
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun eligible(): Boolean = config.autoConnect && isWynncraftAddress(serverAddress)

    private fun setStatus(value: BridgeStatus) {
        currentStatus = value
        game.updateStatus(value)
    }

    private sealed interface Command {
        data class Environment(val address: String?, val playable: Boolean) : Command
        data class Observe(val authorUsername: String, val content: String, val itemShares: List<ItemShare>) : Command
        data object Reconnect : Command
        data class Online(val callback: (Result<GuildOnlineSnapshot>) -> Unit) : Command
        data class OnlineCompleted(
            val socket: BridgeSocket,
            val generation: Long,
            val callback: (Result<GuildOnlineSnapshot>) -> Unit,
            val result: Result<GuildOnlineSnapshot>,
        ) : Command
        data class ConnectionProgress(val generation: Long, val stage: BridgeStage) : Command
        data class ConnectionSucceeded(
            val generation: Long,
            val identity: LauncherIdentity,
            val session: OdysseySession,
            val socket: BridgeSocket,
        ) : Command
        data class ConnectionFailed(
            val generation: Long,
            val identity: LauncherIdentity?,
            val session: OdysseySession?,
            val error: Throwable,
        ) : Command
        data class Text(val socket: BridgeSocket, val fragment: String, val last: Boolean) : Command
        data class Binary(val socket: BridgeSocket) : Command
        data class Ping(val socket: BridgeSocket, val bytes: ByteArray) : Command
        data class Pong(val socket: BridgeSocket) : Command
        data class Closed(val socket: BridgeSocket, val statusCode: Int, val reason: String) : Command
        data class Failed(val socket: BridgeSocket, val error: Throwable) : Command
        data class Retry(val generation: Long) : Command
        data object Stop : Command
    }

    companion object {
        const val WYNNCRAFT_ADDRESS = "play.wynncraft.com"

        internal fun isWynncraftAddress(address: String?): Boolean {
            val normalized = address?.trim()?.lowercase() ?: return false
            val host = when (normalized.count { it == ':' }) {
                0 -> normalized
                1 -> {
                    val port = normalized.substringAfterLast(':')
                    if (port.isEmpty() || !port.all(Char::isDigit)) return false
                    val portNumber = port.toIntOrNull() ?: return false
                    if (portNumber !in 1..65535) return false
                    normalized.substringBeforeLast(':')
                }
                else -> return false
            }
            val normalizedHost = host.removeSuffix(".")
            return normalizedHost.length <= 253 && normalizedHost.endsWith(".wynncraft.com") &&
                normalizedHost.split('.').all { label ->
                    label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
                        label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
                }
        }
        private val NORMAL_DISCONNECT_REASONS = setOf(
            "left_wynncraft",
            "manual_reconnect",
            "client_stopping",
        )
        private val TERMINAL_CODES = setOf(
            "identity_conflict",
            "identity_not_linked",
            "not_in_odyssey",
            "token_invalid",
            "protocol_invalid",
            "invalid_request",
        )
    }
}

internal class PendingObservations(private val capacity: Int, private val imageBudget: Int = 4 * 1024 * 1024) {
    private val entries = LinkedHashMap<String, ClientMessage.GuildObservation>()

    fun add(message: ClientMessage.GuildObservation) {
        entries[message.id] = message
        while (entries.size > capacity) entries.remove(entries.keys.first())
        var imageBytes = entries.values.sumOf { it.itemShares.sumOf { share -> share.png?.length ?: 0 } }
        for ((id, observation) in entries) {
            if (imageBytes <= imageBudget) break
            val bytes = observation.itemShares.sumOf { it.png?.length ?: 0 }
            if (bytes == 0) continue
            entries[id] = observation.copy(itemShares = observation.itemShares.map { it.copy(png = null) })
            imageBytes -= bytes
        }
    }

    fun acknowledge(result: ServerMessage.ObservationResult) {
        if (result.status != ObservationStatus.PENDING) entries.remove(result.id)
    }

    fun values(): List<ClientMessage.GuildObservation> = entries.values.toList()
    fun size(): Int = entries.size
    fun clear() = entries.clear()
}

internal class ReconnectPolicy(
    private val jitter: (Long) -> Long = { ceiling -> Random.nextLong(ceiling + 1) },
) {
    fun delayMillis(attempt: Int): Long {
        val capSeconds = CAPS[min(attempt, CAPS.lastIndex)]
        return jitter(capSeconds * 1_000).coerceIn(0, capSeconds * 1_000)
    }

    private companion object {
        val CAPS = longArrayOf(1, 2, 4, 8, 16, 32, 60)
    }
}

private class EventLru(private val capacity: Int) {
    private val entries = LinkedHashMap<String, Unit>()

    fun add(id: String): Boolean {
        if (entries.containsKey(id)) return false
        entries[id] = Unit
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return true
    }

    fun clear() = entries.clear()
}
