package org.odyssey.mod.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

import org.odyssey.mod.config.OdysseyConfig
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeClientTest {
    @Test
    fun `replays same observation ids reconnects transiently and stops terminally`() {
        val game = FakeGame()
        val transport = FakeTransport { game.identity }
        val client = BridgeClient(
            transport,
            game,
            OdysseyConfig(),
            ReconnectPolicy { 0 },
        )
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually { transport.sockets.size == 1 }
        client.observe("Alice", "hello")
        transport.sendWelcome(0)
        eventually { transport.sockets[0].sent.any { it.contains("guild_observation") } }
        val observation = transport.sockets[0].sent
            .mapNotNull { runCatching { ProtocolCodec.decodeClient(ProtocolFrame.Text(it)) }.getOrNull() }
            .filterIsInstance<ClientMessage.GuildObservation>()
            .single()

        transport.close(0, 1013, "temporary")
        eventually { transport.sockets.size == 2 }
        assertEquals(1, transport.challengeCalls.get(), "transient reconnect reuses in-memory bearer")
        assertEquals(1, PresentationRepository.snapshot().revision)
        transport.sendWelcome(1)
        eventually {
            transport.sockets[1].sent.any { encoded ->
                encoded.contains(observation.id) && encoded.contains("guild_observation")
            }
        }

        transport.send(
            1,
            ServerMessage.ObservationResult(
                PROTOCOL_VERSION,
                observation.id,
                ObservationStatus.ACCEPTED,
                "99999999-9999-4999-8999-999999999999",
            ),
        )
        transport.close(1, 1013, "temporary")
        eventually { transport.sockets.size == 3 }
        transport.sendWelcome(2)
        Thread.sleep(50)
        assertFalse(transport.sockets[2].sent.any { it.contains(observation.id) })

        game.identity = LauncherIdentity("22222222-2222-4222-8222-222222222222", "Bob")
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually { transport.challengeCalls.get() == 2 }
        eventually { transport.sockets.size == 4 }
        transport.sendWelcome(3)
        eventually { client.status() == BridgeStatus.Connected("Bob", "Alps") }

        val chat = ServerMessage.Chat(
            PROTOCOL_VERSION,
            "88888888-8888-4888-8888-888888888888",
            ChatSource.DISCORD,
            author = ChatAuthor(discordId = "10", displayName = "Discord Alice"),
            content = "chat body",
            sentAt = "2026-08-15T12:00:00Z",
        )
        transport.send(3, chat)
        eventually { game.rendered.size == 1 }
        assertTrue(game.executeCalls.get() > 0, "chat rendering is dispatched through game access")

        assertTrue(PresentationRepository.snapshot().revision > 0)
        transport.close(3, 4403, "not_in_odyssey")
        Thread.sleep(100)
        assertEquals(4, transport.sockets.size)
        eventually { PresentationRepository.snapshot() == PresentationSnapshot() }
        assertEquals(
            BridgeStatus.Terminal("not_in_odyssey", "Current profile is not in Alps or Vale"),
            client.status(),
        )
        client.stop()
    }

    @Test
    fun `reports typed lifecycle warnings and terminal state without overwriting connection`() {
        val game = FakeGame()
        val transport = FakeTransport { game.identity }
        val client = BridgeClient(transport, game, OdysseyConfig(), ReconnectPolicy { 0 })

        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually { transport.sockets.size == 1 }
        transport.sendWelcomeHeader(0, revision = 1)
        eventually {
            game.statuses.lastOrNull() ==
                BridgeStatus.Progress(BridgeStage.SYNCHRONIZING_PRESENTATION)
        }
        val rank = RankPresentation("Sentinel", RankColors(0x112233, 0x445566, 0x778899))
        transport.sendSnapshotEntry(
            0,
            revision = 1,
            PresentationEntry(game.identity.uuid, game.identity.username, rank),
        )
        transport.completeSnapshot(0, revision = 1)
        val connected = BridgeStatus.Connected("Alice", "Alps", rank)
        eventually { client.status() == connected }
        assertEquals(
            listOf(
                BridgeStatus.Progress(BridgeStage.AUTHENTICATING),
                BridgeStatus.Progress(BridgeStage.CONNECTING),
                BridgeStatus.Progress(BridgeStage.SYNCHRONIZING_PRESENTATION),
                connected,
            ),
            game.statuses.toList(),
        )

        client.observe("Alice", "")
        eventually { game.warnings.size == 1 }
        assertEquals(
            BridgeWarning(
                "observation_rejected",
                "Odyssey could not send one observed guild message. Check the Minecraft log.",
            ),
            game.warnings.single(),
        )
        assertEquals(connected, client.status())

        val retryableWarning = BridgeWarning("server_busy", "Try again later")
        transport.send(
            0,
            ServerMessage.Error(
                PROTOCOL_VERSION,
                retryableWarning.code,
                retryableWarning.message,
                retryable = true,
            ),
        )
        eventually { game.warnings.size == 2 }
        assertEquals(retryableWarning, game.warnings.last())
        assertEquals(connected, client.status())

        transport.send(
            0,
            ServerMessage.Error(
                PROTOCOL_VERSION,
                "invalid_request",
                "Invalid client request",
                retryable = false,
            ),
        )
        eventually {
            client.status() == BridgeStatus.Terminal("invalid_request", "Invalid client request")
        }
        client.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `retry backoff resets only after a stable server ping`() = runTest {
        val game = FakeGame()
        val transport = FakeTransport { game.identity }
        val bridgeScope = CoroutineScope(coroutineContext + SupervisorJob())
        val client = BridgeClient(
            transport,
            game,
            OdysseyConfig(),
            ReconnectPolicy { it },
            scope = bridgeScope,
        )
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        runCurrent()
        transport.sendWelcome(0)
        runCurrent()

        transport.close(0, 1013, "first")
        runCurrent()
        assertEquals(
            BridgeStatus.Retrying("connection closed (1013)", attempt = 1, delayMillis = 1_000),
            client.status(),
        )

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, transport.sockets.size)
        transport.sendWelcome(1)
        runCurrent()
        transport.close(1, 1013, "second")
        runCurrent()
        assertEquals(
            BridgeStatus.Retrying("connection closed (1013)", attempt = 2, delayMillis = 2_000),
            client.status(),
        )

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, transport.sockets.size)
        transport.sendWelcome(2)
        transport.ping(2)
        runCurrent()
        transport.close(2, 1013, "after ping")
        runCurrent()
        assertEquals(
            BridgeStatus.Retrying("connection closed (1013)", attempt = 1, delayMillis = 1_000),
            client.status(),
        )

        client.stop()
        runCurrent()
    }

    @Test
    fun `terminal socket codes preserve session policy until manual reconnect`() {
        data class Case(
            val closeCode: Int,
            val terminal: BridgeStatus.Terminal,
            val freshChallenge: Boolean,
        )

        val cases = listOf(
            Case(
                4401,
                BridgeStatus.Terminal("token_invalid", "Odyssey session expired; run /odyssey reconnect"),
                freshChallenge = true,
            ),
            Case(
                4403,
                BridgeStatus.Terminal("not_in_odyssey", "Current profile is not in Alps or Vale"),
                freshChallenge = false,
            ),
            Case(
                4406,
                BridgeStatus.Terminal("protocol_invalid", "Backend protocol is incompatible"),
                freshChallenge = false,
            ),
        )

        cases.forEach { case ->
            val game = FakeGame()
            val transport = FakeTransport { game.identity }
            val client = BridgeClient(transport, game, OdysseyConfig(), ReconnectPolicy { 0 })
            client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
            eventually { transport.sockets.size == 1 }
            transport.sendWelcome(0)
            eventually { client.status() == BridgeStatus.Connected("Alice", "Alps") }

            transport.close(0, case.closeCode, case.terminal.code)
            eventually { client.status() == case.terminal }
            Thread.sleep(50)
            assertEquals(1, transport.sockets.size, "terminal ${case.closeCode} must remain stopped")

            client.reconnect()
            eventually { transport.sockets.size == 2 }
            assertEquals(
                if (case.freshChallenge) 2 else 1,
                transport.challengeCalls.get(),
                "terminal ${case.closeCode} session policy",
            )
            client.stop()
        }
    }

    @Test
    fun `terminal state discards observations before manual reconnect`() {
        val game = FakeGame()
        val transport = FakeTransport { game.identity }
        val client = BridgeClient(transport, game, OdysseyConfig(), ReconnectPolicy { 0 })
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually { transport.sockets.size == 1 }
        transport.sendWelcome(0)
        eventually { client.status() == BridgeStatus.Connected("Alice", "Alps") }

        client.observe("Alice", "queued before terminal")
        eventually { transport.sockets[0].sent.count { it.contains("guild_observation") } == 1 }
        transport.close(0, 4403, "not_in_odyssey")
        eventually { client.status() is BridgeStatus.Terminal }
        client.observe("Alice", "seen while terminal")
        client.reconnect()
        eventually { transport.sockets.size == 2 }
        transport.sendWelcome(1)
        eventually { client.status() == BridgeStatus.Connected("Alice", "Alps") }
        Thread.sleep(50)

        assertFalse(
            transport.sockets[1].sent
                .mapNotNull { runCatching { ProtocolCodec.decodeClient(ProtocolFrame.Text(it)) }.getOrNull() }
                .any { it is ClientMessage.GuildObservation },
        )
        client.stop()
    }

    @Test
    fun `rate limited close drops backlog and reuses the session`() {
        val game = FakeGame()
        val transport = FakeTransport { game.identity }
        val client = BridgeClient(transport, game, OdysseyConfig(), ReconnectPolicy { 0 })
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually { transport.sockets.size == 1 }
        transport.sendWelcome(0)
        eventually { client.status() == BridgeStatus.Connected("Alice", "Alps") }

        client.observe("Alice", "first queued observation")
        client.observe("Alice", "second queued observation")
        eventually { transport.sockets[0].sent.count { it.contains("guild_observation") } == 2 }
        transport.send(
            0,
            ServerMessage.Error(
                PROTOCOL_VERSION,
                "rate_limited",
                "Observation rate limit exceeded",
                retryable = true,
            ),
        )
        transport.close(0, 4429, "rate_limited")
        eventually { transport.sockets.size == 2 }
        transport.sendWelcome(1)
        eventually { client.status() == BridgeStatus.Connected("Alice", "Alps") }
        Thread.sleep(50)

        assertEquals(1, transport.challengeCalls.get())
        assertFalse(
            transport.sockets[1].sent
                .mapNotNull { runCatching { ProtocolCodec.decodeClient(ProtocolFrame.Text(it)) }.getOrNull() }
                .any { it is ClientMessage.GuildObservation },
        )
        client.stop()
    }

    @Test
    fun `unexpected actor failure becomes recoverable terminal state`() {
        val game = FakeGame().also {
            it.identityFailure = IllegalStateException("launcher unavailable")
        }
        val transport = FakeTransport { game.identity }
        val client = BridgeClient(transport, game, OdysseyConfig(), ReconnectPolicy { 0 })

        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually {
            val status = client.status()
            status is BridgeStatus.Terminal && status.code == "client_error"
        }
        assertEquals(0, transport.challengeCalls.get())

        game.identityFailure = null
        client.reconnect()
        eventually { transport.challengeCalls.get() == 1 && transport.sockets.size == 1 }
        transport.sendWelcome(0)
        eventually { client.status() == BridgeStatus.Connected("Alice", "Alps") }
        client.stop()
    }

    @Test
    fun `leaving during socket open cannot resurrect the stale socket`() {
        val game = FakeGame()
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport { game.identity }.also {
            it.openSocketGate = gate
        }
        val client = BridgeClient(transport, game, OdysseyConfig(), ReconnectPolicy { 0 })

        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually { transport.openSocketCalls.get() == 1 }
        client.updateEnvironment(null, false)
        eventually { client.status() == BridgeStatus.Idle }
        assertTrue(transport.sockets.isEmpty())

        gate.complete(Unit)
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually { transport.sockets.size == 1 }
        client.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stop cancels a blocked socket open without waiting for it`() = runTest {
        val game = FakeGame()
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport { game.identity }.also {
            it.openSocketGate = gate
        }
        val bridgeJob = SupervisorJob()
        val bridgeScope = CoroutineScope(coroutineContext + bridgeJob)
        val client = BridgeClient(
            transport,
            game,
            OdysseyConfig(),
            ReconnectPolicy { 0 },
            scope = bridgeScope,
        )
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        runCurrent()
        assertEquals(1, transport.openSocketCalls.get())

        client.stop()
        runCurrent()

        assertTrue(bridgeJob.isCancelled)
        assertTrue(transport.sockets.isEmpty())
    }

    @Test
    fun `socket failure before connection handoff cannot install a dead socket`() {
        val game = FakeGame()
        val transport = FakeTransport { game.identity }.also {
            it.failureBeforeOpenReturns = IOException("failed during handoff")
        }
        val client = BridgeClient(transport, game, OdysseyConfig(), ReconnectPolicy { 0 })

        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually { transport.sockets.size == 2 }
        assertTrue(transport.sockets[0].closed)
        transport.sendWelcome(1)

        eventually { client.status() == BridgeStatus.Connected("Alice", "Alps") }
        client.stop()
    }

    @Test
    fun `socket failure discards an incomplete frame before reconnecting`() {
        val game = FakeGame()
        val transport = FakeTransport { game.identity }
        val client = BridgeClient(transport, game, OdysseyConfig(), ReconnectPolicy { 0 })
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually { transport.sockets.size == 1 }

        transport.fragment(0, "{", last = false)
        transport.fail(0, IOException("link dropped"))
        eventually { transport.sockets.size == 2 }
        transport.sendWelcome(1)

        eventually { client.status() == BridgeStatus.Connected("Alice", "Alps") }
        client.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `manual reconnect invalidates an older retry timer`() = runTest {
        val game = FakeGame()
        val transport = FakeTransport { game.identity }
        val bridgeScope = CoroutineScope(coroutineContext + SupervisorJob())
        val client = BridgeClient(
            transport,
            game,
            OdysseyConfig(),
            ReconnectPolicy { 1_000 },
            scope = bridgeScope,
        )
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        runCurrent()
        assertEquals(1, transport.sockets.size)

        transport.close(0, 1013, "first")
        runCurrent()
        advanceTimeBy(250)
        client.reconnect()
        runCurrent()
        assertEquals(2, transport.sockets.size)

        transport.close(1, 1013, "second")
        runCurrent()
        advanceTimeBy(750)
        runCurrent()
        assertEquals(2, transport.sockets.size, "the first connection's timer must be inert")

        advanceTimeBy(250)
        runCurrent()
        assertEquals(3, transport.sockets.size)
        client.stop()
        runCurrent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `leaving Wynncraft starts a fresh retry cycle after rejoining`() = runTest {
        val game = FakeGame()
        val transport = FakeTransport { game.identity }
        val bridgeScope = CoroutineScope(coroutineContext + SupervisorJob())
        val client = BridgeClient(
            transport,
            game,
            OdysseyConfig(),
            ReconnectPolicy { 1_000 },
            scope = bridgeScope,
        )
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        runCurrent()

        transport.close(0, 1013, "first")
        runCurrent()
        assertEquals(1, (client.status() as BridgeStatus.Retrying).attempt)

        client.updateEnvironment(null, false)
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        runCurrent()
        assertEquals(2, transport.sockets.size)

        transport.close(1, 1013, "second")
        runCurrent()
        val retry = client.status() as BridgeStatus.Retrying
        assertEquals(1, retry.attempt)
        assertEquals(1_000L, retry.delayMillis)

        client.stop()
        runCurrent()
    }

    @Test
    fun `pending buffer is bounded and keeps replacement ids`() {
        val pending = PendingObservations(100)
        repeat(101) { index ->
            pending.add(observation(index.toString()))
        }
        assertEquals(100, pending.size())
        assertFalse(pending.values().any { it.id == "0" })
        pending.add(observation("100", content = "replacement"))
        assertEquals(100, pending.size())
        assertEquals("replacement", pending.values().last { it.id == "100" }.content)
        pending.acknowledge(
            ServerMessage.ObservationResult(
                PROTOCOL_VERSION,
                "100",
                ObservationStatus.PENDING,
            ),
        )
        assertEquals(100, pending.size())
        pending.acknowledge(
            ServerMessage.ObservationResult(
                PROTOCOL_VERSION,
                "100",
                ObservationStatus.REJECTED,
                reason = "not_corroborated",
            ),
        )
        assertEquals(99, pending.size())
    }

    @Test
    fun `Wynncraft address matching accepts normalized subdomains with a strict domain boundary`() {
        listOf(
            "play.wynncraft.com",
            "PLAY.WYNNCRAFT.COM:25565",
            " play.wynncraft.com.:443 ",
            "lobby.wynncraft.com",
            "LOBBY.WYNNCRAFT.COM:25565",
            " lobby.wynncraft.com.:443 ",
            "eu.wynncraft.com",
            "AS.WYNNCRAFT.COM:25565",
            "node.play.wynncraft.com.",
            "node.lobby.wynncraft.com",
            "region-2.wynncraft.com",
        ).forEach { address -> assertTrue(BridgeClient.isWynncraftAddress(address), address) }
        listOf(
            null,
            "",
            "wynncraft.com",
            ".wynncraft.com",
            "node..wynncraft.com",
            "-node.wynncraft.com",
            "node-.wynncraft.com",
            "node_name.wynncraft.com",
            "https://play.wynncraft.com",
            "user@play.wynncraft.com",
            "${"a".repeat(64)}.wynncraft.com",
            "${"a".repeat(63)}.${"b".repeat(63)}.${"c".repeat(63)}.${"d".repeat(63)}.wynncraft.com",
            "wynncraft.com.evil.example",
            "notwynncraft.com",
            "play.wynncraft.com.evil.example:25565",
            "lobby.wynncraft.com.evil.example:25565",
            "play.wynncraft.com:",
            "play.wynncraft.com:0",
            "play.wynncraft.com:65536",
            "lobby.wynncraft.com:",
            "lobby.wynncraft.com:0",
            "lobby.wynncraft.com:65536",
        ).forEach { address -> assertFalse(BridgeClient.isWynncraftAddress(address), address) }
    }

    @Test
    fun `joining through the lobby connects and manual reconnect opens a new socket`() {
        val game = FakeGame()
        val transport = FakeTransport { game.identity }
        val client = BridgeClient(transport, game, OdysseyConfig(), ReconnectPolicy { 0 })
        try {
            client.updateEnvironment("lobby.wynncraft.com", true)
            eventually { transport.sockets.size == 1 }
            transport.sendWelcome(0)
            eventually { client.status() == BridgeStatus.Connected("Alice", "Alps") }

            client.reconnect()

            eventually { transport.sockets.size == 2 }
            assertTrue(transport.sockets[0].closed)
            transport.sendWelcome(1)
            eventually { client.status() == BridgeStatus.Connected("Alice", "Alps") }
            assertEquals(1, transport.challengeCalls.get(), "manual reconnect reuses the valid session")
        } finally {
            client.stop()
        }
    }

    @Test
    fun `leaving Wynncraft clears presentation state`() {
        val game = FakeGame()
        val transport = FakeTransport { game.identity }
        val client = BridgeClient(transport, game, OdysseyConfig(), ReconnectPolicy { 0 })
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually { transport.sockets.size == 1 }
        PresentationRepository.replace(PresentationSnapshot(7))

        client.updateEnvironment("minecraft.example", false)

        eventually {
            transport.sockets.single().closed &&
                PresentationRepository.snapshot() == PresentationSnapshot()
        }
        client.stop()
    }

    @Test
    fun `asynchronous send failure reconnects through the actor`() {
        val game = FakeGame()
        val transport = FakeTransport { game.identity }
        val client = BridgeClient(transport, game, OdysseyConfig(), ReconnectPolicy { 0 })
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually { transport.sockets.size == 1 }
        transport.sendWelcome(0)
        eventually { client.status() == BridgeStatus.Connected("Alice", "Alps") }
        transport.sockets[0].failNextSend = IllegalStateException("send failed")

        client.observe("Alice", "failure is observable")

        eventually { transport.sockets.size == 2 }
        client.stop()
    }


    @Test
    fun `presentation snapshot installs atomically and revision gaps reconnect`() {
        PresentationRepository.replace(PresentationSnapshot(3))
        val game = FakeGame()
        val transport = FakeTransport { game.identity }
        val client = BridgeClient(transport, game, OdysseyConfig(), ReconnectPolicy { 0 })
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually { transport.sockets.size == 1 }

        transport.sendWelcomeHeader(0, revision = 7)
        repeat(200) { index ->
            transport.sendSnapshotEntry(
                0,
                revision = 7,
                PresentationEntry(
                    "00000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}",
                    "User$index",
                    RankPresentation("Pathfinder", RankColors(0x5865f2)),
                ),
            )
        }
        eventually { transport.sockets[0].requests >= 203 }
        assertEquals(PresentationSnapshot(3), PresentationRepository.snapshot())

        transport.completeSnapshot(0, revision = 7)
        eventually { PresentationRepository.snapshot().entries.size == 200 }
        assertEquals(7, PresentationRepository.snapshot().revision)

        transport.send(
            0,
            ServerMessage.PresentationUpsert(
                PROTOCOL_VERSION,
                9,
                PresentationEntry(
                    "ffffffff-ffff-4fff-8fff-ffffffffffff",
                    "Gap",
                    RankPresentation("Pathfinder", RankColors(0x5865f2)),
                ),
            ),
        )
        eventually { transport.sockets.size == 2 }
        assertEquals(7, PresentationRepository.snapshot().revision)
        client.stop()
    }

    @Test
    fun `presentation snapshot entry limit becomes terminal without retry`() {
        PresentationRepository.replace(PresentationSnapshot(3))
        val game = FakeGame()
        val transport = FakeTransport { game.identity }
        val client = BridgeClient(transport, game, OdysseyConfig(), ReconnectPolicy { 0 })
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually { transport.sockets.size == 1 }
        transport.sendWelcomeHeader(0, revision = 7)

        repeat(MAX_PRESENTATION_ENTRIES + 1) { index ->
            transport.sendSnapshotEntry(0, revision = 7, presentation(index))
        }

        eventually(timeoutMillis = 10_000) {
            client.status() is BridgeStatus.Terminal &&
                (client.status() as BridgeStatus.Terminal).code == "presentation_too_large"
        }
        assertEquals(1, transport.sockets.size)
        assertEquals(PresentationSnapshot(), PresentationRepository.snapshot())
        client.stop()
    }

    @Test
    fun `presentation deltas enforce capacity while allowing replacements and removals`() {
        val game = FakeGame()
        val transport = FakeTransport { game.identity }
        val client = BridgeClient(transport, game, OdysseyConfig(), ReconnectPolicy { 0 })
        client.updateEnvironment(BridgeClient.WYNNCRAFT_ADDRESS, true)
        eventually { transport.sockets.size == 1 }
        transport.sendWelcome(0, revision = 7)
        eventually { client.status() is BridgeStatus.Connected }

        val entries = (0 until MAX_PRESENTATION_ENTRIES)
            .associate { index -> presentation(index).let { it.minecraftUuid to it } }
        PresentationRepository.replace(PresentationSnapshot(7, entries))
        val existing = presentation(0).copy(minecraftUsername = "Updated")
        transport.send(
            0,
            ServerMessage.PresentationUpsert(PROTOCOL_VERSION, revision = 8, existing),
        )
        eventually {
            PresentationRepository.snapshot().let {
                it.revision == 8L &&
                    it.entries.size == MAX_PRESENTATION_ENTRIES &&
                    it.entries.getValue(existing.minecraftUuid).minecraftUsername == "Updated"
            }
        }

        transport.send(
            0,
            ServerMessage.PresentationRemove(PROTOCOL_VERSION, revision = 9, existing.minecraftUuid),
        )
        eventually { PresentationRepository.snapshot().entries.size == MAX_PRESENTATION_ENTRIES - 1 }
        val replacement = presentation(MAX_PRESENTATION_ENTRIES)
        transport.send(
            0,
            ServerMessage.PresentationUpsert(PROTOCOL_VERSION, revision = 10, replacement),
        )
        eventually {
            PresentationRepository.snapshot().let {
                it.revision == 10L && it.entries.size == MAX_PRESENTATION_ENTRIES
            }
        }

        transport.send(
            0,
            ServerMessage.PresentationUpsert(
                PROTOCOL_VERSION,
                revision = 11,
                presentation(MAX_PRESENTATION_ENTRIES + 1),
            ),
        )
        eventually {
            client.status() is BridgeStatus.Terminal &&
                (client.status() as BridgeStatus.Terminal).code == "presentation_too_large"
        }
        assertEquals(PresentationSnapshot(), PresentationRepository.snapshot())
        client.stop()
    }

    @Test
    fun `handshake statuses retain distinct terminal diagnostics and session actions`() {
        assertEquals(
            HandshakeFailure(
                "token_invalid",
                "Odyssey session expired; run /odyssey reconnect",
                clearSession = true,
            ),
            handshakeFailure(401),
        )
        assertEquals(
            HandshakeFailure(
                "not_in_odyssey",
                "Current profile is not in Alps or Vale",
                clearSession = false,
            ),
            handshakeFailure(403),
        )
        assertEquals(
            HandshakeFailure(
                "protocol_invalid",
                "Backend protocol is incompatible",
                clearSession = false,
            ),
            handshakeFailure(426),
        )
        assertEquals(null, handshakeFailure(500))
    }

    @Test
    fun `reconnect jitter honors exponential cap`() {
        val policy = ReconnectPolicy { it }
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            (0..7).map(policy::delayMillis),
        )
    }

    private fun presentation(index: Int) = PresentationEntry(
        "00000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}",
        "User$index",
        RankPresentation("Pathfinder", RankColors(0x5865f2)),
    )

    private fun observation(id: String, content: String = "body") =
        ClientMessage.GuildObservation(PROTOCOL_VERSION, id, "Alice", content)
}

private class FakeTransport(private val identity: () -> LauncherIdentity) : OdysseyTransport {
    val challengeCalls = AtomicInteger()
    val sockets = CopyOnWriteArrayList<FakeSocket>()
    val openSocketCalls = AtomicInteger()
    var openSocketGate: CompletableDeferred<Unit>? = null
    var failureBeforeOpenReturns: Throwable? = null
    private lateinit var events: SocketEvents

    override suspend fun challenge(): MinecraftChallenge {
        challengeCalls.incrementAndGet()
        return MinecraftChallenge("challenge-${challengeCalls.get()}", "a".repeat(40), 60)
    }

    override suspend fun complete(challengeId: String, username: String): OdysseySession {
        val current = identity()
        return OdysseySession(
            "token-${challengeCalls.get()}",
            Instant.now().plusSeconds(3600).toString(),
            current.uuid,
        )
    }

    override suspend fun openSocket(token: String, events: SocketEvents): BridgeSocket {
        openSocketCalls.incrementAndGet()
        openSocketGate?.await()
        this.events = events
        return FakeSocket().also { socket ->
            sockets += socket
            failureBeforeOpenReturns?.also { failureBeforeOpenReturns = null }?.let {
                events.failed(socket, it)
            }
        }
    }

    fun sendWelcome(index: Int, revision: Long = 1, entries: List<PresentationEntry> = emptyList()) {
        sendWelcomeHeader(index, revision)
        entries.forEach { sendSnapshotEntry(index, revision, it) }
        completeSnapshot(index, revision)
    }


    fun sendWelcomeHeader(index: Int, revision: Long) {
        val current = identity()
        val welcome = ServerMessage.Welcome(
            PROTOCOL_VERSION,
            MinecraftIdentity(current.uuid, current.username),
            GuildRef("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Alps"),
            listOf("guild_observation", "presentation"),
            revision,
        )
        val encoded = ProtocolCodec.encode(welcome)
        val split = encoded.length / 2
        events.text(sockets[index], encoded.substring(0, split), false)
        events.text(sockets[index], encoded.substring(split), true)
    }

    fun sendSnapshotEntry(index: Int, revision: Long, entry: PresentationEntry) {
        send(
            index,
            ServerMessage.PresentationSnapshot(
                PROTOCOL_VERSION,
                revision,
                listOf(entry),
                complete = false,
            ),
        )
    }

    fun completeSnapshot(index: Int, revision: Long) {
        send(
            index,
            ServerMessage.PresentationSnapshot(
                PROTOCOL_VERSION,
                revision,
                emptyList(),
                complete = true,
            ),
        )
    }

    fun send(index: Int, message: ServerMessage) {
        events.text(sockets[index], ProtocolCodec.encode(message), true)
    }

    fun fragment(index: Int, fragment: String, last: Boolean) {
        events.text(sockets[index], fragment, last)
    }

    fun fail(index: Int, error: Throwable) {
        events.failed(sockets[index], error)
    }

    fun close(index: Int, code: Int, reason: String) {
        events.closed(sockets[index], code, reason)
    }

    fun ping(index: Int, bytes: ByteArray = byteArrayOf(1)) {
        events.ping(sockets[index], bytes)
    }
}

private class FakeSocket : BridgeSocket {
    val sent = CopyOnWriteArrayList<String>()
    var requests = 0
    var closed = false

    override fun requestNext() {
        requests++
    }

    var failNextSend: Throwable? = null

    override fun send(text: String): CompletionStage<Unit> {
        sent += text
        val failure = failNextSend.also { failNextSend = null }
        return if (failure == null) {
            CompletableFuture.completedFuture(Unit)
        } else {
            CompletableFuture.failedFuture(failure)
        }
    }

    override fun pong(bytes: ByteArray): CompletionStage<Unit> =
        CompletableFuture.completedFuture(Unit)

    override fun close(statusCode: Int, reason: String): CompletionStage<Unit> {
        closed = true
        return CompletableFuture.completedFuture(Unit)
    }
}

private class FakeGame : GameAccess {
    @Volatile
    var identity = LauncherIdentity("11111111-1111-4111-8111-111111111111", "Alice")
    @Volatile
    var identityFailure: Throwable? = null
    val executeCalls = AtomicInteger()
    val rendered = CopyOnWriteArrayList<ServerMessage.Chat>()
    val statuses = CopyOnWriteArrayList<BridgeStatus>()
    val warnings = CopyOnWriteArrayList<BridgeWarning>()

    override fun launcherIdentity(): LauncherIdentity {
        identityFailure?.let { throw it }
        return identity
    }
    override fun joinServer(serverId: String) = Unit
    override fun execute(action: () -> Unit) {
        executeCalls.incrementAndGet()
        action()
    }
    override fun updateStatus(status: BridgeStatus) {
        statuses += status
    }
    override fun showWarning(warning: BridgeWarning) {
        warnings += warning
    }
    override fun renderChat(message: ServerMessage.Chat) {
        rendered += message
    }
}

private fun eventually(timeoutMillis: Long = 2_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (!condition()) {
        if (System.nanoTime() >= deadline) error("condition not met within ${timeoutMillis}ms")
        Thread.sleep(5)
    }
}
