package org.odyssey.mod.chat

import org.odyssey.mod.network.ChatOrigin
import java.util.concurrent.TimeUnit

/**
 * Recently verified guild-chat nicknames and their canonical IGNs, kept for one server and guild
 * scope. Names match exactly. A nickname seen for two IGNs stays ambiguous until it expires.
 * Call from one thread; Odyssey uses the client thread.
 */
internal class NicknameAliases(
    private val capacity: Int = 128,
    private val lifetimeNanos: Long = TimeUnit.MINUTES.toNanos(10),
    private val clock: () -> Long = System::nanoTime,
) {
    private class Alias(val username: String?, val seenAt: Long)

    private var origin: ChatOrigin? = null
    private val aliases = LinkedHashMap<String, Alias>()

    init {
        require(capacity > 0 && lifetimeNanos > 0)
    }

    fun remember(origin: ChatOrigin, nickname: String, username: String) {
        adopt(origin)
        val now = clock()
        val previous = live(nickname, now)
        val agreed = previous == null || previous.username.equals(username, ignoreCase = true)
        aliases.remove(nickname)
        aliases[nickname] = Alias(username.takeIf { agreed }, now)
        while (aliases.size > capacity) aliases.remove(aliases.keys.first())
    }

    fun resolve(origin: ChatOrigin, nickname: String): String? {
        adopt(origin)
        return live(nickname, clock())?.username
    }

    fun clear() {
        origin = null
        aliases.clear()
    }

    // Another server, guild or profile starts a new, empty scope.
    private fun adopt(origin: ChatOrigin) {
        if (this.origin == origin) return
        aliases.clear()
        this.origin = origin
    }

    private fun live(nickname: String, now: Long): Alias? {
        val alias = aliases[nickname] ?: return null
        if (now - alias.seenAt < lifetimeNanos) return alias
        aliases.remove(nickname)
        return null
    }
}
