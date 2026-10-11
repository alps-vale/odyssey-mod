package org.odyssey.mod.chat

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.contents.PlainTextContents
import org.odyssey.mod.network.GuildEvent
import org.odyssey.mod.network.GuildRank
import org.odyssey.mod.network.GuildResource
import org.odyssey.mod.network.actor
import org.odyssey.mod.network.isGuildEventName
import org.odyssey.mod.network.isGuildEventText
import org.odyssey.mod.network.isMinecraftUsername
import org.odyssey.mod.network.isWynncraftGuildName
import java.util.Optional

/**
 * Recognizes Wynncraft guild-channel system messages. Only the server can start a guild-channel
 * message with its prefix; player chat always follows it with a rank and speaker, so the whole
 * text must match one exact template.
 */
internal object GuildEventParser {
    private const val GUILD_COLOR = 0x55FFFF
    private const val GUILD_BADGE = "\uDAFF\uDFFC\uE006\uDAFF\uDFFF\uE002\uDAFF\uDFFE"
    private const val CONTINUATION = "\uDAFF\uDFFC\uE001\uDB00\uDC06"
    private const val MAX_TEXT_CHARS = 256
    private const val NAME = "([A-Za-z0-9_ ]+)"
    private val RANK = GuildRank.entries.joinToString("|", "(", ")") { it.label }

    private val joined = Regex("$NAME has joined the guild, say hello!")
    private val left = Regex("$NAME has left the guild")
    private val kicked = Regex("$NAME has kicked $NAME from the guild")
    private val invited = Regex("$NAME has invited $NAME to the guild")
    private val uninvited = Regex("$NAME has uninvited $NAME from the guild")
    private val rankChanged = Regex("$NAME has set $NAME guild rank from $RANK to $RANK")
    private val allianceFormed = Regex("$NAME formed an alliance with $NAME")
    private val allianceRevoked = Regex("$NAME revoked the alliance with $NAME")
    // Only the tab every member can use; Wynncraft may wrap between "(" and "Everyone" without a space.
    private val bank = Regex("$NAME (deposited|withdrew) ([1-9][0-9]{0,3})x (.+) (to|from) the Guild Bank \\( ?Everyone\\)")
    private val rewarded = Regex("$NAME rewarded (.+) to $NAME")
    private const val RESOURCE_AMOUNT = "(Ⓑ |Ⓒ |Ⓚ |Ⓙ )?([1-9][0-9]{0,9}) (Emeralds?|Ore|Wood|Fish|Crops?)"
    private val tributeScheduled = Regex("$NAME scheduled $RESOURCE_AMOUNT per hour to $NAME")
    private val resourceGifted = Regex("$NAME gifted $RESOURCE_AMOUNT to $NAME")
    private val territoryChanged = Regex("$NAME changed ([1-9][0-9]{0,3}) (upgrades|bonuses) on (.+)")
    private val territoryBonusSet = Regex("$NAME set (.+) bonus to level (0|[1-9][0-9]{0,2}) on (.+)")
    private val territoryLoadout = Regex("$NAME applied the loadout (.+) on (.+)")

    fun parse(component: Component): GuildEvent? {
        if (!isLiteralTree(component) || leadingColor(component) != GUILD_COLOR) return null
        val lines = component.string.split('\n')
        val first = lines.first().let { it.after("$GUILD_BADGE ") ?: it.after("$CONTINUATION ") } ?: return null
        val continued = lines.drop(1).map { it.after("$CONTINUATION ") ?: return null }
        // Wynncraft wraps system text at spaces, sometimes leaving one at the end of a line.
        val text = (listOf(first) + continued).joinToString(" ").split(' ').filter(String::isNotEmpty).joinToString(" ")
        if (text.length > MAX_TEXT_CHARS) return null
        return match(text)
    }

    /**
     * The canonical IGN Wynncraft attached to the acting player's name: the same real-name hover
     * as nicknamed guild chat, or a username insertion. Only a span holding exactly the actor at
     * the start of the message counts, because target and body spans name other players.
     * Disagreeing evidence yields no IGN.
     */
    fun actorUsername(component: Component, event: GuildEvent): String? {
        val actor = event.actor
        val text = component.string
        val start = listOf(GUILD_BADGE, CONTINUATION).firstOrNull { text.startsWith("$it ") }?.length?.plus(1) ?: return null
        val span = spanAt(component, start)?.takeIf { literal(it) == actor && it.siblings.isEmpty() } ?: return null
        val shown = (span.style.hoverEvent as? HoverEvent.ShowText)?.value?.string?.let { hover ->
            hover.removePrefix("$actor's real name is ").takeIf { it != hover && isMinecraftUsername(it) }
        }
        val inserted = span.style.insertion?.takeIf(::isMinecraftUsername)
        if (shown != null && inserted != null && !shown.equals(inserted, ignoreCase = true)) return null
        return shown ?: inserted
    }

    // The literal whose text begins exactly at `start` in the component's string, if one does.
    private fun spanAt(component: Component, start: Int): Component? {
        var offset = 0
        val pending = ArrayDeque(listOf(component))
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val text = literal(node) ?: return null
            if (offset == start && text.isNotEmpty()) return node
            offset += text.length
            if (offset > start) return null
            pending.addAll(0, node.siblings)
        }
        return null
    }

    private fun literal(component: Component): String? = (component.contents as? PlainTextContents)?.text()

    private fun match(text: String): GuildEvent? {
        joined.matchEntire(text)?.let { return names(it, 1)?.let { (player) -> GuildEvent.MemberJoined(player) } }
        left.matchEntire(text)?.let { return names(it, 1)?.let { (player) -> GuildEvent.MemberLeft(player) } }
        kicked.unambiguous(text, " has kicked ")?.let {
            return names(it, 2)?.let { (actor, player) -> GuildEvent.MemberKicked(actor, player) }
        }
        invited.unambiguous(text, " has invited ")?.let {
            return names(it, 2)?.let { (actor, player) -> GuildEvent.MemberInvited(actor, player) }
        }
        uninvited.unambiguous(text, " has uninvited ")?.let {
            return names(it, 2)?.let { (actor, player) -> GuildEvent.MemberUninvited(actor, player) }
        }
        rankChanged.unambiguous(text, " has set ")?.let { match ->
            val (actor, player) = names(match, 2) ?: return null
            val from = rank(match.groupValues[3])
            val to = rank(match.groupValues[4])
            return if (from == to) null else GuildEvent.RankChanged(actor, player, from, to)
        }
        allianceFormed.unambiguous(text, " formed an alliance with ")?.let {
            return alliance(it)?.let { (actor, guild) -> GuildEvent.AllianceFormed(actor, guild) }
        }
        allianceRevoked.unambiguous(text, " revoked the alliance with ")?.let {
            return alliance(it)?.let { (actor, guild) -> GuildEvent.AllianceRevoked(actor, guild) }
        }
        bank.unambiguous(text, " the Guild Bank (")?.let { return bankTransfer(it) }
        rewarded.unambiguous(text, " rewarded ")?.takeIf { text.indexOf(" to ") == text.lastIndexOf(" to ") }?.let { match ->
            val (actor) = names(match, 1) ?: return null
            val reward = match.groupValues[2].takeIf(::isGuildEventText) ?: return null
            val player = match.groupValues[3].takeIf { isName(it) } ?: return null
            return GuildEvent.MemberRewarded(actor, player, reward)
        }
        tributeScheduled.unambiguous(text, " scheduled ")?.let { return resourceTransfer(it, scheduled = true) }
        resourceGifted.unambiguous(text, " gifted ")?.let { return resourceTransfer(it, scheduled = false) }
        // Reject delimiter-bearing labels rather than guess which words are the territory.
        if (text.indexOf(" on ") != text.lastIndexOf(" on ")) return null
        territoryChanged.unambiguous(text, " changed ")?.let { match ->
            val actor = names(match, 1)?.single() ?: return null
            val count = match.groupValues[2].toInt().takeIf { it >= 2 } ?: return null
            val territory = match.groupValues[4].takeIf(::isGuildEventText) ?: return null
            return if (match.groupValues[3] == "upgrades") {
                GuildEvent.TerritoryUpgradesChanged(actor, count, territory)
            } else {
                GuildEvent.TerritoryBonusesChanged(actor, count, territory)
            }
        }
        territoryBonusSet.unambiguous(text, " set ")?.let { match ->
            val actor = names(match, 1)?.single() ?: return null
            val bonus = match.groupValues[2].takeIf(::isGuildEventText) ?: return null
            val territory = match.groupValues[4].takeIf(::isGuildEventText) ?: return null
            return GuildEvent.TerritoryBonusSet(actor, bonus, match.groupValues[3].toInt(), territory)
        }
        territoryLoadout.unambiguous(text, " applied the loadout ")?.let { match ->
            val actor = names(match, 1)?.single() ?: return null
            val loadout = match.groupValues[2].takeIf(::isGuildEventText) ?: return null
            val territory = match.groupValues[3].takeIf(::isGuildEventText) ?: return null
            return GuildEvent.TerritoryLoadoutApplied(actor, loadout, territory)
        }
        return null
    }

    private fun resourceTransfer(match: MatchResult, scheduled: Boolean): GuildEvent? {
        val sender = names(match, 1)?.single() ?: return null
        val amount = match.groupValues[3].toIntOrNull() ?: return null
        val resource = GuildResource.entries.firstOrNull {
            it.icon == match.groupValues[2] && it.label(amount) == match.groupValues[4]
        } ?: return null
        val guild = match.groupValues[5].takeIf(::isWynncraftGuildName) ?: return null
        return if (scheduled) {
            GuildEvent.TributeScheduled(sender, amount, resource, guild)
        } else {
            GuildEvent.ResourceGifted(sender, amount, resource, guild)
        }
    }

    private fun bankTransfer(match: MatchResult): GuildEvent? {
        val (player, action, count, item, direction) = match.destructured
        val deposit = action == "deposited"
        if (deposit != (direction == "to") || !isName(player) || !isGuildEventText(item)) return null
        return if (deposit) {
            GuildEvent.BankDeposit(player, count.toInt(), item)
        } else {
            GuildEvent.BankWithdrawal(player, count.toInt(), item)
        }
    }

    // A name containing a template phrase would make the split ambiguous.
    private fun Regex.unambiguous(text: String, phrase: String): MatchResult? =
        matchEntire(text)?.takeIf { text.indexOf(phrase) == text.lastIndexOf(phrase) }

    // Wynncraft's own perspective wording is unverified, so never relay "You" as a name.
    private fun names(match: MatchResult, count: Int): List<String>? =
        match.groupValues.subList(1, count + 1).takeIf { names -> names.all(::isName) }

    private fun isName(value: String): Boolean = isGuildEventName(value) && !value.equals("you", ignoreCase = true)

    private fun alliance(match: MatchResult): Pair<String, String>? {
        val actor = names(match, 1)?.single() ?: return null
        val guild = match.groupValues[2].takeIf(::isWynncraftGuildName) ?: return null
        return actor to guild
    }

    private fun rank(label: String): GuildRank = GuildRank.entries.first { it.label == label }

    private fun String.after(prefix: String): String? = if (startsWith(prefix)) substring(prefix.length) else null

    private fun isLiteralTree(component: Component): Boolean =
        component.contents is PlainTextContents && component.siblings.all(::isLiteralTree)

    private fun leadingColor(component: Component): Int? =
        component.visit(FormattedText.StyledContentConsumer { style, text ->
            if (text.isEmpty()) Optional.empty() else Optional.of(style)
        }, Style.EMPTY).orElse(null)?.color?.value
}
