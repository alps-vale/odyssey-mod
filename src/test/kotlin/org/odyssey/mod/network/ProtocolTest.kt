package org.odyssey.mod.network

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ProtocolTest {
    private val clientFixtures = listOf(
        "client_observer_state.json",
        "client_guild_observation.json",
        "client_guild_observation_item.json",
    )
    private val guildEventFixtures = listOf(
        "member_joined",
        "member_left",
        "member_kicked",
        "member_invited",
        "member_uninvited",
        "rank_changed",
        "alliance_formed",
        "alliance_revoked",
        "bank_deposit",
        "bank_withdrawal",
        "member_rewarded",
        "tribute_scheduled",
        "resource_gifted",
        "territory_upgrades_changed",
        "territory_bonus_set",
        "territory_bonuses_changed",
        "territory_loadout_applied",

    ).map { "client_guild_event_$it.json" }
    private val serverFixtures = listOf(
        "server_welcome.json",
        "server_presentation_snapshot.json",
        "server_presentation_snapshot_complete.json",
        "server_observation_result.json",
        "server_chat_discord.json",
        "server_chat_wynn.json",
        "server_presentation_upsert.json",
        "server_presentation_remove.json",
        "server_error.json",
    )

    @Test
    fun `client fixtures round trip`() {
        val messages = clientFixtures.map(::fixture).map {
            val decoded = ProtocolCodec.decodeClient(ProtocolFrame.Text(it))
            assertJsonEquals(it, ProtocolCodec.encode(decoded))
            decoded
        }
        assertIs<ClientMessage.ObserverState>(messages[0])
        assertIs<ClientMessage.GuildObservation>(messages[1])
    }

    @Test
    fun `guild event fixtures round trip as typed client observations`() {
        val events = guildEventFixtures.map(::fixture).map {
            val decoded = ProtocolCodec.decodeClient(ProtocolFrame.Text(it))
            assertJsonEquals(it, ProtocolCodec.encode(decoded))
            assertFails { ProtocolCodec.decodeServer(ProtocolFrame.Text(it)) }
            assertIs<ClientMessage.GuildEventObservation>(decoded).event
        }
        assertEquals(
            listOf(
                GuildEvent.MemberJoined("Alice"),
                GuildEvent.MemberLeft("Wall of Cheese"),
                GuildEvent.MemberKicked("Bob", "Alice"),
                GuildEvent.MemberInvited("Bob", "Alice"),
                GuildEvent.MemberUninvited("Bob", "Wall of Cheese"),
                GuildEvent.RankChanged("Bob", "Alice", GuildRank.RECRUIT, GuildRank.CAPTAIN),
                GuildEvent.AllianceFormed("Alps", "Silk Road"),
                GuildEvent.AllianceRevoked("Bob", "Chiefs Of Corkus"),
                GuildEvent.BankDeposit("Bob", 2, "Light Paper [\u272B\u272B\u272B]"),
                GuildEvent.BankWithdrawal("Alice", 1, "Gelibord Teleportation Scroll [3/3]"),
                GuildEvent.MemberRewarded("Bob", "Alice", "1024 Emeralds"),
                GuildEvent.TributeScheduled("Bob", 67, GuildResource.EMERALDS, "Avicia"),
                GuildEvent.ResourceGifted("Avicia", 1, GuildResource.FISH, "Northbound"),
                GuildEvent.TerritoryUpgradesChanged("Bob", 4, "Volcanic Excavation"),
                GuildEvent.TerritoryBonusSet("Bob", "Mob Experience", 1, "Volcanic Excavation"),
                GuildEvent.TerritoryBonusesChanged("Bob", 2, "Volcanic Excavation"),
                GuildEvent.TerritoryLoadoutApplied("Bob", "Child Support", "Volcanic Excavation"),
            ),
            events,
        )
    }

    @Test
    fun `guild events reject unbounded hidden or unknown fields`() {
        val kicked = fixture("client_guild_event_member_kicked.json")
        val rank = fixture("client_guild_event_rank_changed.json")
        val alliance = fixture("client_guild_event_alliance_formed.json")
        val bank = fixture("client_guild_event_bank_deposit.json")
        val reward = fixture("client_guild_event_member_rewarded.json")
        val schedule = fixture("client_guild_event_tribute_scheduled.json")
        val gift = fixture("client_guild_event_resource_gifted.json")
        val upgrades = fixture("client_guild_event_territory_upgrades_changed.json")
        val bonuses = fixture("client_guild_event_territory_bonuses_changed.json")
        val bonus = fixture("client_guild_event_territory_bonus_set.json")
        val loadout = fixture("client_guild_event_territory_loadout_applied.json")
        listOf(
            kicked.replace("\"Bob\"", "\"\""),
            kicked.replace("\"Bob\"", "\" Bob\""),
            kicked.replace("\"Bob\"", "\"Bob  Smith\""),
            kicked.replace("\"Bob\"", "\"${"B".repeat(33)}\""),
            kicked.replace("\"Bob\"", "\"B\\u00f6b\""),
            kicked.replace("\"Bob\"", "\"Bob\\u00a7c\""),
            kicked.replace("member_kicked", "member_banned"),
            kicked.replace("\"player\":\"Alice\"", "\"player\":\"Alice\",\"reason\":\"x\""),
            kicked.replace("\"v\":2", "\"v\":1"),
            kicked.replace("22222222-2222-4222-8222-000000000003", "not-a-uuid"),
            rank.replace("\"captain\"", "\"recruit\""),
            rank.replace("\"captain\"", "\"admin\""),
            alliance.replace("Silk Road", "Silk Road 2"),
            bank.replace("\"count\":2", "\"count\":0"),
            bank.replace("\"count\":2", "\"count\":10000"),
            bank.replace("\"item\"", "\"access\":\"high_ranked\",\"item\""),
            bank.replace("Light Paper", " Light Paper"),
            bank.replace("Light Paper", "Light\\u200bPaper"),
            bank.replace("Light Paper", "Light \\ue000"),
            bank.replace("Light Paper", "Light\\nPaper"),
            bank.replace("Light Paper", "x".repeat(65)),
            reward.replace("1024 Emeralds", ""),
            schedule.replace("\"amount\":67", "\"amount\":0"),
            schedule.replace("\"amount\":67", "\"amount\":2147483648"),
            schedule.replace("\"amount\":67", "\"amount\":-1"),
            schedule.replace("\"emeralds\"", "\"diamonds\""),
            schedule.replace("\"Bob\"", "\"Bob\\u200b\""),
            gift.replace("Northbound", "Northbound 2"),
            upgrades.replace("\"count\":4", "\"count\":1"),
            bonuses.replace("\"count\":2", "\"count\":10000"),
            bonus.replace("\"level\":1", "\"level\":1000"),
            bonus.replace("Mob Experience", "Mob\\nExperience"),
            loadout.replace("Child Support", ""),
            loadout.replace("Volcanic Excavation", "Volcanic\\ue000Excavation"),
            loadout.replace("Child Support", "x".repeat(65)),
        ).forEach { frame -> assertFails(frame) { ProtocolCodec.decodeClient(ProtocolFrame.Text(frame)) } }
    }

    @Test
    fun `item previews permit bounded image frames without relaxing server frames`() {
        val reference = String(Character.toChars(0xf0001))
        val share = ItemShare(ItemShareKind.WYNNTILS, reference, "Test item", 0xaa00aa, "A".repeat(20_000))
        val message = ClientMessage.GuildObservation(2, "22222222-2222-4222-8222-222222222222", "Alice", reference, listOf(share))
        val text = ProtocolCodec.encode(message)
        assertEquals(message, ProtocolCodec.decodeClient(ProtocolFrame.Text(text)))
        assertFails { ProtocolCodec.encode(message.copy(content = "unrelated")) }
        assertFails { ProtocolCodec.encode(message.copy(itemShares = List(4) { share })) }
        assertFails { ProtocolCodec.encode(message.copy(itemShares = listOf(share, share))) }
        assertFails { ProtocolCodec.encode(message.copy(itemShares = listOf(share.copy(png = "A".repeat(90_000))))) }
        assertFails { ProtocolCodec.decodeServer(ProtocolFrame.Text(text)) }
    }

    @Test
    fun `same labelled native items require distinct nonoverlapping references`() {
        val first = ItemShare(ItemShareKind.WYNNCRAFT, "[Bow]", "Bow", 0xaa00aa)
        val second = first.copy(png = "AAAA")
        val message = ClientMessage.GuildObservation(2, "22222222-2222-4222-8222-222222222222", "Alice", "[Bow] [Bow]", listOf(first, second))
        assertEquals(message, ProtocolCodec.decodeClient(ProtocolFrame.Text(ProtocolCodec.encode(message))))
        assertFails { ProtocolCodec.encode(message.copy(content = "[Bow]")) }
        assertFails { ProtocolCodec.encode(message.copy(content = "aaa", itemShares = listOf(first.copy(encoded = "aa"), second.copy(encoded = "aa")))) }
        assertFails { ProtocolCodec.encode(message.copy(itemShares = listOf(first.copy(encoded = "")))) }
    }

    @Test
    fun `server fixtures round trip`() {
        val messages = serverFixtures.map(::fixture).map {
            val decoded = ProtocolCodec.decodeServer(ProtocolFrame.Text(it))
            assertJsonEquals(it, ProtocolCodec.encode(decoded))
            decoded
        }
        assertIs<ServerMessage.Welcome>(messages[0])
        assertIs<ServerMessage.PresentationSnapshot>(messages[1])
        assertIs<ServerMessage.PresentationSnapshot>(messages[2])
        assertIs<ServerMessage.ObservationResult>(messages[3])
        assertIs<ServerMessage.Chat>(messages[4])
        assertIs<ServerMessage.Chat>(messages[5])
        assertIs<ServerMessage.PresentationUpsert>(messages[6])
        assertIs<ServerMessage.PresentationRemove>(messages[7])
        assertIs<ServerMessage.Error>(messages[8])
    }

    @Test
    fun `rejects unknown versions event types binary and oversize frames`() {
        assertFails { ProtocolCodec.decodeClient(ProtocolFrame.Text("""{"v":3,"type":"observer_state","active":true}""")) }
        assertFails { ProtocolCodec.decodeClient(ProtocolFrame.Text("""{"v":2,"type":"future_event"}""")) }
        assertFails { ProtocolCodec.decodeClient(ProtocolFrame.Binary(byteArrayOf())) }
        assertFails {
            ProtocolCodec.decodeClient(
                ProtocolFrame.Text(
                    """{"v":2,"type":"guild_observation","id":"22222222-2222-4222-8222-222222222222","author_username":"Alice","content":"${"x".repeat(8_192)}"}""",
                ),
            )
        }
    }

    @Test
    fun `rejects content color and server owned field violations`() {
        val oversizedBody = "x".repeat(401)
        assertFails {
            ProtocolCodec.decodeClient(
                ProtocolFrame.Text(
                    """{"v":2,"type":"guild_observation","id":"22222222-2222-4222-8222-222222222222","author_username":"Alice","content":"$oversizedBody"}""",
                ),
            )
        }
        assertFails {
            ProtocolCodec.decodeServer(
                ProtocolFrame.Text(fixture("server_chat_wynn.json").replace("hello from Alps", oversizedBody)),
            )
        }
        assertFails {
            ProtocolCodec.decodeServer(
                ProtocolFrame.Text(fixture("server_chat_wynn.json").replace("hello from Alps", "one\\ntwo")),
            )
        }
        val tooManyLines = (1..9).joinToString("\\n") { "line" }
        assertFails {
            ProtocolCodec.decodeServer(
                ProtocolFrame.Text(
                    """{"v":2,"type":"chat","event_id":"33333333-3333-4333-8333-333333333333","source":"discord","author":{"discord_id":"1","display_name":"Alice"},"content":"$tooManyLines","sent_at":"2026-08-15T12:00:00Z"}""",
                ),
            )
        }
        assertFails {
            ProtocolCodec.decodeServer(
                ProtocolFrame.Text(fixture("server_presentation_upsert.json").replace("5793266", "16777216")),
            )
        }
        assertFails {
            ProtocolCodec.decodeClient(
                ProtocolFrame.Text(
                    """{"v":2,"type":"guild_observation","id":"22222222-2222-4222-8222-222222222222","author_username":"Alice","content":"hello","origin_guild":{"uuid":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","prefix":"Alps"}}""",
                ),
            )
        }
    }

    @Test
    fun `rejects invalid server identity and snapshot fields`() {
        val welcome = fixture("server_welcome.json")
        assertFails { ProtocolCodec.decodeServer(ProtocolFrame.Text(welcome.replace("\"Alice\"", "\"\""))) }
        assertFails { ProtocolCodec.decodeServer(ProtocolFrame.Text(welcome.replace("\"Alps\"", "\"\""))) }

        val presentation = fixture("server_presentation_snapshot.json")
        assertFails {
            ProtocolCodec.decodeServer(
                ProtocolFrame.Text(
                    presentation.replace(
                        "\"minecraft_username\":\"Alice\"",
                        "\"minecraft_username\":\"\"",
                    ),
                ),
            )
        }
        assertFails {
            ProtocolCodec.decodeServer(
                ProtocolFrame.Text(presentation.replace("\"complete\":false", "\"complete\":true")),
            )
        }
        assertFails {
            ProtocolCodec.decodeServer(
                ProtocolFrame.Text(
                    fixture("server_presentation_snapshot_complete.json")
                        .replace("\"complete\":true", "\"complete\":false"),
                ),
            )
        }

        val discord = fixture("server_chat_discord.json")
        assertFails {
            ProtocolCodec.decodeServer(
                ProtocolFrame.Text(
                    discord.replace(
                        "\"discord_id\":\"123456789012345678\"",
                        "\"discord_id\":\"0123456789012345678\"",
                    ),
                ),
            )
        }
        assertFails {
            ProtocolCodec.decodeServer(
                ProtocolFrame.Text(
                    discord.replace("\"discord_id\":\"123456789012345678\"", "\"discord_id\":\"abc\""),
                ),
            )
        }
    }

    @Test
    fun `rejects malformed fields that reach chat notifications or diagnostics`() {
        val discord = fixture("server_chat_discord.json")
        val welcome = fixture("server_welcome.json")
        val error = fixture("server_error.json")
        val invalidFrames = listOf(
            discord.replace(
                "\"display_name\":\"Alice\"",
                "\"display_name\":\"Alice\\n[System] forged\"",
            ),
            discord.replace(
                "\"display_name\":\"Alice\"",
                "\"display_name\":\"${"x".repeat(65)}\"",
            ),
            discord.replace(
                "33333333-3333-4333-8333-333333333333",
                "33333333-3333-4333-8333-33333333333A",
            ),
            discord.replace("\"label\":\"Pathfinder\"", "\"label\":\"Path\\nfinder\""),
            welcome.replace("\"username\":\"Alice\"", "\"username\":\"Alice!\""),
            welcome.replace("\"prefix\":\"Alps\"", "\"prefix\":\"Alps\\nVale\""),
            error.replace("\"code\":\"roster_unavailable\"", "\"code\":\"Roster unavailable\""),
            error.replace(
                "\"message\":\"Wynncraft roster is temporarily unavailable\"",
                "\"message\":\"Retry later\\n[INFO] forged\"",
            ),
        )

        invalidFrames.forEachIndexed { index, frame ->
            assertFails("invalid rendered field case $index") {
                ProtocolCodec.decodeServer(ProtocolFrame.Text(frame))
            }
        }
    }

    @Test
    fun `session response ignores retired presentation fields`() {
        val json = Json { ignoreUnknownKeys = true }
        val session = json.decodeFromString<OdysseySession>(
            """{"token":"secret","expires_at":"2026-08-16T12:00:00Z","minecraft_uuid":"11111111-1111-4111-8111-111111111111","minecraft_username":"Alice","guild":{"uuid":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","prefix":"Alps"}}""",
        )
        assertEquals("11111111-1111-4111-8111-111111111111", session.minecraftUuid)

        val encoded = json.encodeToString(OdysseySession.serializer(), session)
        assertFalse(encoded.contains("minecraft_username"))
        assertFalse(encoded.contains("\"guild\""))
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/protocol/v2/$name")).readText()

    private fun assertJsonEquals(expected: String, actual: String) {
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))
    }
}
