package org.odyssey.mod.chat

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.SharedConstants
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.Bootstrap
import org.odyssey.mod.network.GuildEvent
import org.odyssey.mod.network.GuildRank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Component layout follows Odyssey's captured guild packets (`guild_*.json`): an aqua root, the guild badge
 * or continuation prefix, a space, then the text; wrapped lines repeat the continuation prefix.
 * Wording comes from current-format evidence, with original names:
 * - join, leave, kick and rank: Wynntils 448ee3b `GuildModel` MEMBER_JOIN/MEMBER_LEFT/MEMBER_KICKED/MSG_RANK_CHANGED
 *   and their `TestRegex` cases; rank wraps from Sequoia-mod 4456d74 `GuildRankEventParserTest`.
 * - alliances: Sequoia-mod `ChatManagerTest` live-prefixed alliance messages.
 * - invites: the matching current-format patterns in ezlixp/guild-api `DiscordBridgeFeature` and EdenMod
 *   `GuildEventParser`; no packet capture was available.
 * - bank and rewards: Wynntils `ChatRedirectFeature` GuildBank/GuildReward patterns and the Wynntils chat-log
 *   capture and wrapped packets in Sequoia-mod `GuildBankTrackerTest`.
 */
class GuildEventParserTest {
    @Test
    fun `recognizes evidenced guild system templates`() {
        listOf(
            system("Alice has joined the guild, say hello!", prefix = CONTINUATION) to GuildEvent.MemberJoined("Alice"),
            system("Alice has left the guild") to GuildEvent.MemberLeft("Alice"),
            system("Wall of Cheese has left the guild") to GuildEvent.MemberLeft("Wall of Cheese"),
            system("Bob has kicked Alice from the guild") to GuildEvent.MemberKicked("Bob", "Alice"),
            system("Bob has invited Alice to the guild") to GuildEvent.MemberInvited("Bob", "Alice"),
            system("Bob has uninvited Wall of Cheese from the guild", prefix = CONTINUATION) to
                GuildEvent.MemberUninvited("Bob", "Wall of Cheese"),
            system(
                literal("Bob has set Alice guild rank from "),
                literal(" Recruit", DARK_AQUA),
                literal(" to "),
                literal("Strategist", DARK_AQUA),
            ) to GuildEvent.RankChanged("Bob", "Alice", GuildRank.RECRUIT, GuildRank.STRATEGIST),
            system("Bob has set Alice guild rank from Recruiter", "to Captain") to
                GuildEvent.RankChanged("Bob", "Alice", GuildRank.RECRUITER, GuildRank.CAPTAIN),
            system("Bob has set Alice guild rank from ", "Chief to Recruit") to
                GuildEvent.RankChanged("Bob", "Alice", GuildRank.CHIEF, GuildRank.RECRUIT),
            system("Alps formed an alliance with Silk Road") to GuildEvent.AllianceFormed("Alps", "Silk Road"),
            system("Silk Road revoked the alliance with Alps", prefix = CONTINUATION) to
                GuildEvent.AllianceRevoked("Silk Road", "Alps"),
            system("Bob revoked the alliance with Chiefs Of Corkus") to
                GuildEvent.AllianceRevoked("Bob", "Chiefs Of Corkus"),
            system(
                literal("Bob", DARK_AQUA),
                literal(" withdrew "),
                literal("1x Mural Shard [✫✫✫]", YELLOW),
                literal(" from the Guild Bank ("),
                literal("Everyone", DARK_AQUA),
                literal(")"),
            ) to GuildEvent.BankWithdrawal("Bob", 1, "Mural Shard [✫✫✫]"),
            system("Wall of Cheese deposited 1x Ahmsord Teleportation", "Scroll [3/3] to the Guild Bank (Everyone)") to
                GuildEvent.BankDeposit("Wall of Cheese", 1, "Ahmsord Teleportation Scroll [3/3]"),
            system("Bob withdrew 1x Remnant of the Ruined from the", "Guild Bank (Everyone)") to
                GuildEvent.BankWithdrawal("Bob", 1, "Remnant of the Ruined"),
            system(literal("Bob", DARK_AQUA), literal(" rewarded "), literal("1024 Emeralds", YELLOW), literal(" to Alice")) to
                GuildEvent.MemberRewarded("Bob", "Alice", "1024 Emeralds"),
            fixture("guild_event_rank_wrapped.json") to
                GuildEvent.RankChanged("Bob", "Alice", GuildRank.RECRUITER, GuildRank.CAPTAIN),
            fixture("guild_event_bank_wrapped.json") to GuildEvent.BankDeposit("Bob", 2, "Light Paper [✫✫✫]"),
        ).forEach { (message, expected) ->
            assertEquals(expected, GuildEventParser.parse(message), message.string)
            assertNull(GuildChatParser.parse(message), message.string)
        }
    }

    @Test
    fun `player chat and other channels never become system events`() {
        val spoof = fixture("guild_event_player_spoof.json")
        assertNotNull(GuildChatParser.parse(spoof))
        listOf(
            spoof,
            fixture("guild_system.json"),
            fixture("party_message.json"),
            fixture("direct_message.json"),
            fixture("guild_ordinary.json"),
            fixture("guild_nickname.json"),
            fixture("guild_multiline.json"),
            // Same text without the server's guild prefix, or in the party channel's yellow.
            Component.literal("Alice has left the guild").withColor(GUILD_AQUA),
            system("Alice has left the guild", color = YELLOW),
            system("Alice has left the guild", prefix = PARTY_BADGE, color = YELLOW),
            system("Alice has left the guild", prefix = CONTINUATION, color = YELLOW),
            system("Alice has left the guild", prefix = "󏿼"),
            // Continuation lines must carry the server's continuation prefix.
            system("Bob: hi").append("\nAlice has left the guild"),
            system("Alice has").append("\n has left the guild"),
            system("Alice has left the guild").append(Component.translatable("chat.type.text", "Bob", "x")),
        ).forEach { assertNull(GuildEventParser.parse(it), it.string) }
    }

    @Test
    fun `unverified or ambiguous wording fails closed`() {
        listOf(
            "Alice has left the guild.",
            "Alice has joined the guild",
            "You has set Alice guild rank from Recruit to Captain",
            "Bob has kicked you from the guild",
            "Bob has set Alice guild rank from Captain to Captain",
            "Bob has set Alice guild rank from Admin to Chief",
            "Bob has kicked Carol has kicked Alice from the guild",
            "Bob has kicked Alice from the guild!",
            "${"A".repeat(33)} has left the guild",
            "Alice  has left the guild extra",
            "Bob invited Alice to the guild.",
            "Bob has invited Alice to your guild!",
            "You have successfully uninvited Alice from the guild",
            "Bob has invited Alice to the guild.",
            "Bob scheduled 100 Ore per hour to Silk Road",
            "Bob stopped scheduling Ore to Silk Road",
            "Territory Gelibord is producing more resources than it can store!",
            "Alps formed an alliance with Silk Road 2",
            "Bob deposited 1x Item from the Guild Bank (Everyone)",
            "Bob withdrew 1x Item to the Guild Bank (Everyone)",
            "Bob deposited 0x Item to the Guild Bank (Everyone)",
            "Bob deposited 10000x Item to the Guild Bank (Everyone)",
            "Bob deposited 1x Item to the Guild Bank (Officers)",
            // The High Ranked tab is not every member's to see; keep it out of the shared channel.
            "Bob deposited 2x Light Paper [✫✫✫] to the Guild Bank (High Ranked)",
            "Bob withdrew 1x Item from the Guild Bank ( High Ranked)",
            "Bob deposited 1x Item to the Guild",
            "Bob deposited 1x  to the Guild Bank (Everyone)",
            "Bob rewarded 1024 Emeralds to Alice to Carol",
            "Bob rewarded ​ to Alice",
        ).forEach { assertNull(GuildEventParser.parse(system(it)), it) }
    }

    private fun system(
        first: String,
        vararg wrapped: String,
        prefix: String = GUILD_BADGE,
        color: Int = GUILD_AQUA,
    ): MutableComponent {
        val message = system(literal(first), prefix = prefix, color = color)
        wrapped.forEach { line ->
            message.append(
                Component.literal("\n").append(
                    Component.empty().withColor(GUILD_AQUA).append(CONTINUATION).append(" "),
                ),
            )
            message.append(line)
        }
        return message
    }

    private fun system(vararg body: Component, prefix: String = GUILD_BADGE, color: Int = GUILD_AQUA): MutableComponent {
        val message = Component.empty().withColor(color).append(prefix).append(" ")
        body.forEach(message::append)
        return message
    }

    private fun literal(text: String, color: Int? = null): Component =
        Component.literal(text).also { if (color != null) it.withColor(color) }

    private fun fixture(name: String): Component {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        val json = checkNotNull(javaClass.getResource("/chat/$name")).readText()
        return ComponentSerialization.CODEC
            .parse(JsonOps.INSTANCE, JsonParser.parseString(json))
            .getOrThrow()
    }

    private companion object {
        const val GUILD_BADGE = "󏿼󏿿󏿾"
        const val PARTY_BADGE = "󏿼󏿿󏿾"
        const val CONTINUATION = "󏿼󐀆"
        const val GUILD_AQUA = 0x55FFFF
        const val DARK_AQUA = 0x00AAAA
        const val YELLOW = 0xFFFF55
    }
}
