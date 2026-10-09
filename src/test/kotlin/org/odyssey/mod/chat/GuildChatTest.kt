package org.odyssey.mod.chat

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.SharedConstants
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.server.Bootstrap
import org.odyssey.mod.network.ChatAuthor
import org.odyssey.mod.network.ChatSource
import org.odyssey.mod.network.GuildRef
import org.odyssey.mod.network.PresentationEntry
import org.odyssey.mod.network.PresentationRepository
import org.odyssey.mod.network.PresentationSnapshot
import org.odyssey.mod.network.PROTOCOL_VERSION
import org.odyssey.mod.network.RankColors
import org.odyssey.mod.network.RankPresentation
import org.odyssey.mod.network.ServerMessage
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GuildChatTest {
    @Test
    fun `captured guild ranks recover canonical author and literal body`() {
        listOf(
            "guild_ordinary.json" to Triple("HSPApplicant", "HSPApplicant", "probe 2"),
            "guild_repeated.json" to Triple("HSPApplicant", "HSPApplicant", "probe 1"),
            "guild_captain.json" to Triple("HSPApplicant", "HSPApplicant", "hi test"),
            "guild_nickname.json" to Triple("Colossal_Rat", "Wall of Cheese", "xD"),
            "guild_owner.json" to Triple("5hotgun", "5hotgun", "no that one too hard"),
            "guild_multiline.json" to Triple(
                "HSPApplicant",
                "HSPApplicant",
                "mind demoting me to captain real quick?",
            ),
            "guild_multiline_rich.json" to Triple(
                "HSPApplicant",
                "HSPApplicant",
                "[WynnExtras] [ProfSpeed] NA4, EU20, EU28, NA38, NA21, EU26",
            ),
        ).forEach { (name, expected) ->
            val parsed = GuildChatParser.parse(fixture(name))
            assertEquals(expected.first, parsed?.authorUsername, name)
            assertEquals(expected.second, parsed?.visibleName, name)
            assertEquals(expected.third, parsed?.content, name)
        }
    }

    @Test
    fun `ambiguous non-guild changed and malformed fixtures fail closed`() {
        listOf(
            "guild_system.json",
            "party_message.json",
            "direct_message.json",
            "guild_malformed.json",
            "guild_changed_color.json",
            "guild_ambiguous.json",
        ).forEach { name -> assertNull(GuildChatParser.parse(fixture(name)), name) }
    }

    @Test
    fun `guild body rejects non-literal descendants`() {
        val message = fixture("guild_ordinary.json").copy()
        message.siblings[5] = message.siblings[5].copy()
            .append(Component.translatable("chat.type.text", "Alice", "forged"))

        assertNull(GuildChatParser.parse(message))
    }

    @Test
    fun `separately styled hard-wrapped links retain their exact content`() {
        val message = fixture("guild_multiline.json").copy()
        val originalBody = message.siblings[5]
        val url = "https://discord.com/channels/564411360314654722/1538171939712016414"
        val firstFragment = "https://discord.com/channels/5644"
        val secondFragment = "11360314654722/1538171939712016414"
        val linkStyle = originalBody.style.withClickEvent(ClickEvent.OpenUrl(URI.create(url)))
        message.siblings[5] = Component.literal(" ").withStyle(originalBody.style)
        message.siblings.add(Component.literal(firstFragment).withStyle(linkStyle))
        message.siblings.add(originalBody.siblings[0].copy())
        message.siblings.add(Component.literal(secondFragment).withStyle(linkStyle))

        assertEquals(url, GuildChatParser.parse(message)?.content)
    }

    @Test
    fun `visual reset clears continuation bookkeeping and Discord sequence`() {
        BridgeChatRenderer.reset()
        val rendered = BridgeChatRenderer.component(
            ServerMessage.Chat(
                PROTOCOL_VERSION,
                "88888888-8888-4888-8888-888888888888",
                ChatSource.WYNN,
                GuildRef("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Alps"),
                ChatAuthor(minecraftUuid = "11111111-1111-4111-8111-111111111111", displayName = "Alice"),
                "body",
                "2026-08-15T12:00:00Z",
            ),
        )
        val discord = ServerMessage.Chat(
            PROTOCOL_VERSION,
            "99999999-9999-4999-8999-999999999999",
            ChatSource.DISCORD,
            author = ChatAuthor(discordId = "123456789012345678", displayName = "Discord Alice"),
            content = "body",
            sentAt = "2026-08-15T12:00:00Z",
        )
        assertTrue('\uF8F2' in BridgeChatRenderer.componentForDisplay(discord).string)
        assertTrue('\uF8F2' !in BridgeChatRenderer.componentForDisplay(discord).string)
        assertNotNull(BridgeChatRenderer.continuationPrefix(rendered))

        BridgeChatRenderer.reset()

        assertNull(BridgeChatRenderer.continuationPrefix(rendered))
        assertTrue('\uF8F2' in BridgeChatRenderer.componentForDisplay(discord).string)
    }

    @Test
    fun `decoration changes only exact linked guild identity`() {
        val original = fixture("guild_ordinary.json")
        PresentationRepository.replace(PresentationSnapshot())
        assertSame(original, GuildChatDecorator.decorate(original))
        val party = fixture("party_message.json")
        assertSame(party, GuildChatDecorator.decorate(party))

        val colors = RankColors(0x112233, 0x445566, 0x778899)
        PresentationRepository.replace(
            PresentationSnapshot(
                1,
                mapOf(
                    "11111111-1111-4111-8111-111111111111" to PresentationEntry(
                        "11111111-1111-4111-8111-111111111111",
                        "HSPApplicant",
                        RankPresentation("Pathfinder", colors),
                    ),
                ),
            ),
        )
        val decorated = GuildChatDecorator.decorate(original)
        assertEquals(
            original.siblings.take(2).joinToString("") { it.string },
            decorated.siblings.take(2).joinToString("") { it.string },
        )
        assertNotSame(original, decorated)
        assertSame(original.siblings[5], decorated.siblings[5], "literal body component is retained")
        assertEquals(
            RankPillFactory.rank(RankPresentation("Pathfinder", colors)).string,
            decorated.siblings[2].string,
        )
        assertEquals("HSPApplicant:", decorated.siblings[4].string)

        val wrappedAtHeader = fixture("guild_multiline.json").copy()
        val wrappedBody = wrappedAtHeader.siblings[5]
        wrappedAtHeader.siblings[5] = Component.empty()
            .withStyle(wrappedBody.style)
            .append(wrappedBody.siblings[0].copy())
            .append(wrappedBody.siblings[1].copy())
        assertEquals("quick?", GuildChatParser.parse(wrappedAtHeader)?.content)
        assertEquals(
            RankPillFactory.rank(RankPresentation("Pathfinder", colors)).string,
            GuildChatDecorator.decorate(wrappedAtHeader).siblings[2].string,
        )

        val nickname = fixture("guild_nickname.json")
        val originalHover = nickname.siblings[4].siblings[0].style.hoverEvent
        PresentationRepository.replace(
            PresentationSnapshot(
                2,
                mapOf(
                    "22222222-2222-4222-8222-222222222222" to PresentationEntry(
                        "22222222-2222-4222-8222-222222222222",
                        "Colossal_Rat",
                        RankPresentation("Navigator", colors),
                    ),
                ),
            ),
        )
        val decoratedNickname = GuildChatDecorator.decorate(nickname)
        val decoratedGlyphs = decoratedNickname.siblings[4].siblings[0].siblings
        assertTrue(decoratedGlyphs.isNotEmpty())
        assertTrue(decoratedGlyphs.all { it.style.hoverEvent == originalHover })

    }

    @Test
    fun `rank pills use Wynn labels smooth gradients and compact source markers`() {
        val pill = RankPillFactory.rank(RankPresentation("Ab 1", RankColors(0xFFFFFF)))
        assertEquals(
            "\uE010\u2064" +
                "\uE00F\uE012\uE040" +
                "\uE00F\uE012\uE041" +
                "\uE00F" +
                "\uE00F\uE012\uE061" +
                "\uE011\u2064",
            pill.string,
        )
        assertEquals(0xFFFFFF, pill.siblings[0].style.color?.value)
        assertEquals(0x1F2126, pill.siblings[2].style.color?.value)
        assertEquals(0, pill.siblings[2].style.shadowColor)
        val darkPill = RankPillFactory.rank(RankPresentation("A", RankColors(0x112233)))
        assertEquals(0x1F2126, darkPill.siblings[2].style.color?.value)
        val gradientPill = RankPillFactory.rank(
            RankPresentation("ABC", RankColors(0x000000, 0xFF0000, 0xFFFFFF)),
        )
        val slices = gradientPill.siblings.filter { it.string == "\uE005\uE020" }
        assertEquals(18, slices.size)
        assertEquals(3, gradientPill.siblings.count { it.string == "\uE010" })
        assertEquals(0x000000, slices.first().style.color?.value)
        assertEquals(0xFFFFFF, slices.last().style.color?.value)
        assertTrue(slices.map { it.style.color?.value }.distinct().size > 3)
        assertTrue(slices.all { it.style.shadowColor == null })
        val rendered = BridgeChatRenderer.component(
            ServerMessage.Chat(
                PROTOCOL_VERSION,
                "88888888-8888-4888-8888-888888888888",
                ChatSource.WYNN,
                GuildRef("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Alps"),
                ChatAuthor(
                    minecraftUuid = "11111111-1111-4111-8111-111111111111",
                    displayName = "Alice",
                    wynnRank = "OWNER",
                ),
                "first line\nsecond line",
                "2026-08-15T12:00:00Z",
            ),
        )
        assertEquals(1, rendered.string.count { it == '\n' })
        assertTrue(
            assertNotNull(BridgeChatRenderer.continuationPrefix(rendered))
                .string.codePoints()
                .anyMatch { it >= 0xE000 },
        )
        val discord = BridgeChatRenderer.component(
            ServerMessage.Chat(
                PROTOCOL_VERSION,
                "99999999-9999-4999-8999-999999999999",
                ChatSource.DISCORD,
                null,
                ChatAuthor(
                    discordId = "123456789012345678",
                    displayName = "Sentinel Notedes",
                    role = RankPresentation("Sentinel", RankColors(0x112233, 0x445566, 0x778899)),
                ),
                "test",
                "2026-08-15T12:00:00Z",
            ),
        )
        assertTrue(discord.string.contains('\uF8F2'))
        assertTrue(!discord.string.contains('\uE004'))
        assertTrue("DISCORD" !in discord.string)
        assertEquals(
            "\uF8FE\uF8FE\uF8FE\uF8FE\uF8F2\uF8FD\uF8FD",
            RankPillFactory.discordSource().string,
        )
        assertEquals(
            "\uF8FE\uF8FE\uF8FE\uF8FE\uF8F3" +
                "\uF8FD\uF8FD\uF8FD\uF8FD\uF8FD\uF8FD\uF8FD\uF8FD\uF8FD\uF8FD",
            RankPillFactory.discordSource(continuation = true).string,
        )
    }

    private fun fixture(name: String): Component {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        val json = checkNotNull(javaClass.getResource("/chat/$name")).readText()
        return ComponentSerialization.CODEC
            .parse(JsonOps.INSTANCE, JsonParser.parseString(json))
            .getOrThrow()
    }
}
