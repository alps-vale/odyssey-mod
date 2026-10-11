package org.odyssey.mod.chat

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.contents.PlainTextContents
import org.odyssey.mod.network.GuildEvent
import org.odyssey.mod.network.GuildRank
import org.odyssey.mod.network.isGuildEventName
import org.odyssey.mod.network.isGuildEventText
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
        return null
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
