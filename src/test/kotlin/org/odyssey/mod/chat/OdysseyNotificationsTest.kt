package org.odyssey.mod.chat

import net.minecraft.SharedConstants
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.Bootstrap
import org.odyssey.mod.network.BridgeStage
import org.odyssey.mod.network.BridgeStatus
import org.odyssey.mod.network.BridgeWarning
import org.odyssey.mod.network.GuildOnlineSnapshot
import org.odyssey.mod.network.OnlineMember
import org.odyssey.mod.network.GuildRef
import org.odyssey.mod.network.RankColors
import org.odyssey.mod.network.RankPresentation
import org.odyssey.mod.update.UpdateNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OdysseyNotificationsTest {
    @Test
    fun `online report uses Odyssey styling and distinguishes hidden status from missing connections`() {
        bootstrapMinecraft()
        val snapshot = GuildOnlineSnapshot("2026-10-10T12:00:00Z", listOf(
            OnlineMember("Alice", GuildRef("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Alps"), true, "WC1", emptyList()),
            OnlineMember("Vera", GuildRef("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "Vale"), null, null, listOf("0.3.2")),
        ))
        val lines = OdysseyNotifications.onlineReport(snapshot, 1, true)
        lines.forEach(::assertOdysseyPill)
        assertTrue("Wynncraft as of 12:00 UTC" in lines[0].string)
        assertTrue("online · WC1 · Odyssey not connected" in lines[1].string)
        assertTrue("Wynncraft status hidden · Odyssey 0.3.2" in lines[2].string)
        val fallback = OdysseyNotifications.onlineReport(snapshot, 1, false)
        assertTrue(fallback.all { "[Odyssey]" in it.string })
        assertTrue(fallback.all { line -> line.string.codePoints().noneMatch { it in 0xE000..0xE0FF } })
        assertTrue("Choose a page" in OdysseyNotifications.onlineReport(snapshot, 2, false).single().string)
    }

    @Test fun `update actions reuse Odyssey system source pill palette and literal fallback`() {
        bootstrapMinecraft()
        val message = OdysseyNotifications.update(UpdateNotice("Odyssey 0.2.0 is available.", UpdateNotice.Action.INSTALL), true)
        assertOdysseyPill(message)
        assertEquals(0xD7DEE8, body(message, "Odyssey 0.2.0 is available.").style.color?.value)
        val action = message.siblings.last()
        assertEquals("[Update]", action.string)
        assertEquals(0x45C9C4, action.style.color?.value)
        assertTrue(action.style.isUnderlined)
        assertEquals("/odyssey update install", assertIs<ClickEvent.RunCommand>(action.style.clickEvent).command())
        assertEquals("Install when Minecraft closes", assertIs<HoverEvent.ShowText>(action.style.hoverEvent).value().string)
        val fallback = OdysseyNotifications.update(UpdateNotice("Update your instance first.", UpdateNotice.Action.RELEASES, true), false)
        assertTrue("[Odyssey]" in fallback.string)
        assertTrue(fallback.string.codePoints().noneMatch { it in 0xE000..0xE0FF })
        assertEquals(0xFFAA00, body(fallback, "Update your instance first.").style.color?.value)
        assertIs<ClickEvent.OpenUrl>(fallback.siblings.last().style.clickEvent)
    }
    @Test
    fun `lifecycle notices use quiet chat milestones with first class identity styling`() {
        bootstrapMinecraft()
        listOf(
            BridgeStage.AUTHENTICATING,
            BridgeStage.CONNECTING,
            BridgeStage.SYNCHRONIZING_PRESENTATION,
        ).forEach { stage ->
            assertNull(OdysseyNotifications.transition(BridgeStatus.Progress(stage)))
        }
        assertNull(OdysseyNotifications.transition(BridgeStatus.Idle))
        assertNull(
            OdysseyNotifications.transition(
                BridgeStatus.Retrying("network error", attempt = 2, delayMillis = 500),
            ),
        )

        val retry = assertNotNull(
            OdysseyNotifications.transition(
                BridgeStatus.Retrying("network error", attempt = 1, delayMillis = 250),
            ),
        )
        assertEquals(
            0xFFAA00,
            body(retry, "Connection interrupted. Reconnecting…").style.color?.value,
        )
        assertOdysseyPill(retry)

        val rank = RankPresentation("Sentinel", RankColors(0x112233, 0x445566, 0x778899))
        val connected = assertNotNull(
            OdysseyNotifications.transition(BridgeStatus.Connected("Alice", "Alps", rank)),
        )
        val connectedBody = connected.siblings[3]
        assertEquals(" ", connectedBody.siblings[0].string)
        assertEquals(0xD7DEE8, connectedBody.siblings[0].style.color?.value)
        assertEquals(RankPillFactory.rank(rank).string, connectedBody.siblings[1].string)
        val username = connectedBody.siblings[3]
        assertEquals("Alice", username.string)
        assertEquals(0xD7DEE8, username.style.color?.value)
        val guild = connectedBody.siblings[5]
        assertEquals(
            RankPillFactory.label("Alps", RankColors(0x55FFFF, 0x45C9C4)).string,
            guild.string,
        )
        assertEquals(0x55FFFF, guild.siblings.first().style.color?.value)
        assertEquals(0x45C9C4, guild.siblings.last().style.color?.value)
        assertEquals(6, connectedBody.siblings.size)
        assertTrue("Connected as" !in connected.string)
        assertTrue(!connected.string.endsWith("."))
        assertOdysseyPill(connected)

        val warning = OdysseyNotifications.warning(BridgeWarning("server_busy", "Try again later."))
        assertEquals(0xFFAA00, body(warning, "Try again later.").style.color?.value)
        assertOdysseyPill(warning)
    }

    @Test
    fun `terminal notices centralize recovery copy and actions`() {
        bootstrapMinecraft()
        data class Case(
            val code: String,
            val sourceMessage: String,
            val body: String,
            val reconnect: Boolean,
        )

        val cases = listOf(
            Case("token_invalid", "expired", "Your Odyssey session expired.", reconnect = true),
            Case(
                "identity_not_linked",
                "not linked",
                "Link your profile with /link in the Odyssey Discord server, then reconnect.",
                reconnect = true,
            ),
            Case(
                "not_in_odyssey",
                "not in guild",
                "This profile is not currently in Alps or Vale. Join an Odyssey guild, then reconnect.",
                reconnect = true,
            ),
            Case(
                "identity_conflict",
                "profile mismatch",
                "Your active Minecraft profile does not match the profile linked to Odyssey.",
                reconnect = false,
            ),
            Case(
                "protocol_invalid",
                "bad version",
                "This Odyssey mod version is incompatible with the backend. Update Odyssey before reconnecting.",
                reconnect = false,
            ),
            Case(
                "invalid_request",
                "bad request",
                "Odyssey rejected an invalid client request. Check the Minecraft log.",
                reconnect = false,
            ),
            Case(
                "client_error",
                "actor failed",
                "Odyssey hit an unexpected client error. Check the Minecraft log, then reconnect.",
                reconnect = true,
            ),
            Case(
                "future_terminal",
                "Safe backend message.",
                "Safe backend message.",
                reconnect = false,
            ),
        )

        cases.forEach { case ->
            val notice = assertNotNull(
                OdysseyNotifications.transition(BridgeStatus.Terminal(case.code, case.sourceMessage)),
            )
            assertEquals(0xFF5555, body(notice, case.body).style.color?.value, case.code)
            assertOdysseyPill(notice)

            val action = notice.siblings.singleOrNull { it.string == "[Reconnect]" }
            if (case.reconnect) {
                val reconnect = assertNotNull(action, case.code)
                assertEquals(0x45C9C4, reconnect.style.color?.value)
                assertTrue(reconnect.style.isUnderlined)
                assertEquals(
                    ClickEvent.RunCommand("/odyssey reconnect"),
                    reconnect.style.clickEvent,
                )
                val hover = assertIs<HoverEvent.ShowText>(reconnect.style.hoverEvent)
                assertEquals("Reconnect Odyssey", hover.value.string)
            } else {
                assertNull(action, case.code)
            }
        }
    }

    @Test
    fun `command feedback keeps system identity with literal fallback outside Wynncraft`() {
        bootstrapMinecraft()
        val status = BridgeStatus.Retrying("network error", attempt = 1, delayMillis = 250)

        val report = OdysseyNotifications.statusReport(status, usePill = false)
        assertSystemSource(report)
        assertEquals("[Odyssey] ", report.siblings[1].string)
        assertEquals(0x45C9C4, report.siblings[1].style.color?.value)
        assertEquals("Current status: network error; reconnecting.", report.siblings[2].string)
        assertTrue(report.string.codePoints().noneMatch { it in 0xE000..0xE0FF })

        val reconnect = OdysseyNotifications.reconnectRequested(usePill = false)
        assertSystemSource(reconnect)
        assertEquals("[Odyssey] ", reconnect.siblings[1].string)
        assertEquals("Reconnect requested.", reconnect.siblings[2].string)
        assertEquals(0x45C9C4, reconnect.siblings[1].style.color?.value)

        assertOdysseyPill(OdysseyNotifications.statusReport(status, usePill = true))
        val rank = RankPresentation("Sentinel", RankColors(0x112233, 0x445566, 0x778899))
        val connected = OdysseyNotifications.statusReport(
            BridgeStatus.Connected("Alice", "Alps", rank),
            usePill = true,
        )
        val connectedBody = connected.siblings[3]
        assertEquals("Current status: connected as ", connectedBody.siblings[0].string)
        assertEquals(RankPillFactory.rank(rank).string, connectedBody.siblings[1].string)
        assertEquals("Alice", connectedBody.siblings[3].string)
        assertEquals(
            RankPillFactory.label("Alps", RankColors(0x55FFFF, 0x45C9C4)).string,
            connectedBody.siblings[5].string,
        )
        assertOdysseyPill(connected)
    }

    private fun body(component: Component, expected: String): Component =
        component.siblings.single { it.string == expected }

    private fun assertOdysseyPill(component: Component) {
        assertSystemSource(component)
        val pill = component.siblings[1]
        assertTrue(pill.string.startsWith("\uE010\u2064"))
        assertTrue(pill.string.endsWith("\uE011\u2064"))
        assertEquals(0x45C9C4, pill.siblings.first().style.color?.value)
        assertEquals(0x465FD8, pill.siblings.last().style.color?.value)
        assertEquals(
            listOf(
                "\uE012\uE04E",
                "\uE012\uE043",
                "\uE012\uE058",
                "\uE012\uE052",
                "\uE012\uE052",
                "\uE012\uE044",
                "\uE012\uE058",
            ),
            pill.siblings.filter { it.string.startsWith("\uE012") }.map { it.string },
        )
        val gradient = pill.siblings.filter { it.string == "\uE005\uE020" }
        assertEquals(0x45C9C4, gradient.first().style.color?.value)
        assertEquals(0x465FD8, gradient.last().style.color?.value)
    }

    private fun assertSystemSource(component: Component) {
        val source = component.siblings.first()
        assertEquals("\uF8FE\uF8FE\uF8FE\uF8FE\uF8F4\uF8FD\uF8FD", source.string)
        assertEquals(0xFFFFFF, source.style.color?.value)
        assertEquals(0, source.style.shadowColor)
    }

    private fun bootstrapMinecraft() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }
}
