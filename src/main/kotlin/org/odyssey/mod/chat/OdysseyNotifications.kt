package org.odyssey.mod.chat

import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import org.odyssey.mod.network.BridgeStatus
import org.odyssey.mod.network.BridgeWarning
import org.odyssey.mod.network.GuildOnlineSnapshot
import org.odyssey.mod.network.RankColors
import org.odyssey.mod.update.UpdateManifest
import org.odyssey.mod.update.UpdateNotice
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object OdysseyNotifications {
    private const val BRAND_START = 0x45C9C4
    private const val BRAND_END = 0x465FD8
    private const val FROST = 0xD7DEE8
    private const val NEUTRAL = 0xA0A0A0
    private const val WARNING = 0xFFAA00
    private const val ERROR = 0xFF5555
    private val brandColors = RankColors(BRAND_START, BRAND_END)
    private val guildColors = RankColors(0x55FFFF, BRAND_START)

    fun transition(status: BridgeStatus): Component? = when (status) {
        BridgeStatus.Idle,
        is BridgeStatus.Progress,
        -> null
        is BridgeStatus.Retrying -> if (status.attempt == 1) {
            notice("Connection interrupted. Reconnecting…", WARNING)
        } else {
            null
        }
        is BridgeStatus.Connected -> notice(connectedBody(status, " "))
        is BridgeStatus.Terminal -> terminal(status)
    }

    fun warning(warning: BridgeWarning): Component =
        notice(warning.message, WARNING)

    fun statusReport(status: BridgeStatus, usePill: Boolean): Component {
        val color = statusColor(status)
        return if (status is BridgeStatus.Connected) {
            notice(connectedBody(status, "Current status: connected as ", "."), usePill = usePill)
        } else {
            notice("Current status: ${status.summary()}.", color, usePill)
        }
    }

    fun reconnectRequested(usePill: Boolean): Component =
        notice("Reconnect requested.", NEUTRAL, usePill)

    fun onlineReport(snapshot: GuildOnlineSnapshot, page: Int, usePill: Boolean): List<Component> {
        val pages = maxOf(1, (snapshot.members.size + 14) / 15)
        if (page !in 1..pages) return listOf(notice("Choose a page from 1 to $pages.", WARNING, usePill))
        val time = runCatching {
            DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC)
                .format(Instant.parse(snapshot.refreshedAt))
        }.getOrDefault("unknown time")
        val heading = notice("Guild activity · page $page/$pages · Wynncraft as of $time", FROST, usePill)
        val rows = snapshot.members.drop((page - 1) * 15).take(15).map { member ->
            val state = when (member.online) {
                true -> "online" + (member.server?.let { " · ${plain(it, 24)}" } ?: "")
                false -> "offline in Wynncraft's last report"
                null -> "Wynncraft status hidden"
            }
            val versions = member.modVersions.take(3).joinToString(", ") { plain(it, 64) } +
                (if (member.modVersions.size > 3) " (+${member.modVersions.size - 3} more)" else "")
            val body = Component.literal(plain(member.username, 32)).withStyle(Style.EMPTY.withColor(FROST))
                .append(Component.literal(" "))
                .append(if (usePill) RankPillFactory.label(plain(member.guild.prefix, 16), guildColors)
                    else Component.literal("[${plain(member.guild.prefix, 16)}]").withStyle(Style.EMPTY.withColor(BRAND_START)))
                .append(Component.literal(" · $state · ").withStyle(Style.EMPTY.withColor(NEUTRAL)))
                .append(Component.literal(if (versions.isEmpty()) "Odyssey not connected" else "Odyssey $versions")
                    .withStyle(Style.EMPTY.withColor(if (versions.isEmpty()) NEUTRAL else BRAND_START)))
            notice(body, usePill)
        }
        val empty = if (rows.isEmpty()) listOf(notice("No online members or Odyssey connections reported.", NEUTRAL, usePill)) else rows
        return listOf(heading) + empty + notice("Wynncraft refreshes every 2 minutes; Odyssey connections are live.", NEUTRAL, usePill)
    }

    fun onlineError(message: String, usePill: Boolean): Component = notice(message, WARNING, usePill)

    private fun plain(text: String, limit: Int): String = text.filterNot(Char::isISOControl).take(limit)

    fun update(update: UpdateNotice, usePill: Boolean): Component {
        val result = notice(update.text, if (update.warning) WARNING else FROST, usePill).copy()
        val action = when (update.action) {
            UpdateNotice.Action.INSTALL -> action(
                "Update", ClickEvent.RunCommand("/odyssey update install"), "Install when Minecraft closes",
            )
            UpdateNotice.Action.RELEASES -> action(
                "Releases", ClickEvent.OpenUrl(URI.create(UpdateManifest.RELEASES + "latest")), "Open Odyssey releases",
            )
            null -> null
        }
        if (action != null) result.append(Component.literal(" ")).append(action)
        return result
    }

    private fun action(label: String, click: ClickEvent, hover: String): Component =
        Component.literal("[$label]").withStyle(
            Style.EMPTY.withColor(BRAND_START).withUnderlined(true)
                .withClickEvent(click).withHoverEvent(HoverEvent.ShowText(Component.literal(hover))),
        )

    private fun terminal(status: BridgeStatus.Terminal): Component {
        val (body, reconnect) = when (status.code) {
            "token_invalid" -> "Your Odyssey session expired." to true
            "identity_not_linked" ->
                "Link your profile with /link in the Odyssey Discord server, then reconnect." to true
            "not_in_odyssey" ->
                "This profile is not currently in Alps or Vale. Join an Odyssey guild, then reconnect." to true
            "identity_conflict" ->
                "Your active Minecraft profile does not match the profile linked to Odyssey." to false
            "protocol_invalid" ->
                "This Odyssey mod version is incompatible with the backend. Update Odyssey before reconnecting." to false
            "invalid_request" ->
                "Odyssey rejected an invalid client request. Check the Minecraft log." to false
            "client_error" ->
                "Odyssey hit an unexpected client error. Check the Minecraft log, then reconnect." to true
            else -> status.message to false
        }
        return notice(body, ERROR, reconnect = reconnect)
    }

    private fun connectedBody(
        status: BridgeStatus.Connected,
        lead: String,
        suffix: String = "",
    ): Component {
        val result = Component.empty()
        result.append(Component.literal(lead).withStyle(Style.EMPTY.withColor(FROST)))
        status.rank?.let { rank ->
            result.append(RankPillFactory.rank(rank))
            result.append(Component.literal(" "))
        }
        result.append(Component.literal(status.username).withStyle(Style.EMPTY.withColor(FROST)))
        result.append(Component.literal(" "))
        result.append(RankPillFactory.label(status.guildPrefix, guildColors))
        if (suffix.isNotEmpty()) {
            result.append(Component.literal(suffix).withStyle(Style.EMPTY.withColor(FROST)))
        }
        return result
    }

    private fun statusColor(status: BridgeStatus): Int = when (status) {
        BridgeStatus.Idle,
        is BridgeStatus.Progress,
        -> NEUTRAL
        is BridgeStatus.Connected -> FROST
        is BridgeStatus.Retrying -> WARNING
        is BridgeStatus.Terminal -> ERROR
    }

    private fun notice(
        body: String,
        color: Int,
        usePill: Boolean = true,
        reconnect: Boolean = false,
    ): Component = notice(
        Component.literal(body).withStyle(Style.EMPTY.withColor(color)),
        usePill,
        reconnect,
    )

    private fun notice(
        body: Component,
        usePill: Boolean = true,
        reconnect: Boolean = false,
    ): Component {
        val result = Component.empty()
            .append(RankPillFactory.systemSource())
        if (usePill) {
            result.append(RankPillFactory.label("ODYSSEY", brandColors))
            result.append(Component.literal(" "))
        } else {
            result.append(Component.literal("[Odyssey] ").withStyle(Style.EMPTY.withColor(BRAND_START)))
        }
        result.append(body)
        if (reconnect) {
            result.append(Component.literal(" "))
            result.append(action("Reconnect", ClickEvent.RunCommand("/odyssey reconnect"), "Reconnect Odyssey"))
        }
        return result
    }
}
