package org.odyssey.mod.chat

import net.minecraft.network.chat.Component
import org.odyssey.mod.network.ChatOrigin
import org.odyssey.mod.network.GuildEvent
import org.odyssey.mod.network.actor
import org.odyssey.mod.network.actorMayBeGuild

/**
 * Chooses the IGN a client reports for a guild event's actor. Call from the client thread.
 *
 * Evidence from the event's own packet wins, and contradictory evidence reports nothing. A
 * nickname verified in guild chat fills in only when the packet has no evidence and the actor
 * must be a player, because a guild's name can match a nickname.
 */
internal class GuildEventActors(private val aliases: NicknameAliases = NicknameAliases()) {
    /** Remembers a guild-chat nickname whose real-name hover the chat parser verified. */
    fun learn(origin: ChatOrigin, visibleName: String, username: String) {
        if (visibleName != username) aliases.remember(origin, visibleName, username)
    }

    fun actorFor(origin: ChatOrigin, packet: Component, event: GuildEvent): String? =
        when (val evidence = GuildEventParser.actorEvidence(packet, event)) {
            is ActorEvidence.Named -> evidence.username
            ActorEvidence.Conflicting -> null
            ActorEvidence.Absent -> if (event.actorMayBeGuild) null else aliases.resolve(origin, event.actor)
        }

    fun clear() = aliases.clear()
}
