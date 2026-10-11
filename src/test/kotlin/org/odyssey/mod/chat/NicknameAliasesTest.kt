package org.odyssey.mod.chat

import org.odyssey.mod.network.ChatOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NicknameAliasesTest {
    private val alps = ChatOrigin("play.wynncraft.com", scope = 0)
    private var now = 0L
    private val aliases = NicknameAliases(capacity = 2, lifetimeNanos = 100, clock = { now })

    @Test
    fun `only exact verified nicknames resolve`() {
        aliases.remember(alps, "Wall of Cheese", "Colossal_Rat")
        assertEquals("Colossal_Rat", aliases.resolve(alps, "Wall of Cheese"))
        assertNull(aliases.resolve(alps, "Wall"))
        assertNull(aliases.resolve(alps, "wall of cheese"))
    }

    @Test
    fun `a nickname seen for two players stays ambiguous until it expires`() {
        aliases.remember(alps, "Wall of Cheese", "Colossal_Rat")
        aliases.remember(alps, "Wall of Cheese", "Other_Rat")
        assertNull(aliases.resolve(alps, "Wall of Cheese"))
        aliases.remember(alps, "Wall of Cheese", "Colossal_Rat")
        assertNull(aliases.resolve(alps, "Wall of Cheese"))
        now += 100
        aliases.remember(alps, "Wall of Cheese", "Colossal_Rat")
        assertEquals("Colossal_Rat", aliases.resolve(alps, "Wall of Cheese"))
    }

    @Test
    fun `aliases expire, stay bounded and never cross servers or guilds`() {
        aliases.remember(alps, "First", "First_Rat")
        now += 99
        assertEquals("First_Rat", aliases.resolve(alps, "First"))
        now += 1
        assertNull(aliases.resolve(alps, "First"))

        aliases.remember(alps, "A", "A_Rat")
        aliases.remember(alps, "B", "B_Rat")
        aliases.remember(alps, "C", "C_Rat")
        assertNull(aliases.resolve(alps, "A"))
        assertEquals("C_Rat", aliases.resolve(alps, "C"))

        assertNull(aliases.resolve(ChatOrigin("other.example.com", scope = 0), "C"))
        aliases.remember(alps, "C", "C_Rat")
        assertNull(aliases.resolve(alps.copy(scope = 1), "C"))
    }
}
