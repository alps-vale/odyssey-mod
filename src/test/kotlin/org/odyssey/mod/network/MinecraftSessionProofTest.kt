package org.odyssey.mod.network

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MinecraftSessionProofTest {
    private val origin = BackendOrigin.parse("https://odyssey.notes.supply", false)
    private val challenge = MinecraftChallenge(
        "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8", "a".repeat(40), 60, 2,
    )

    @Test
    fun `client and backend share proof vectors`() {
        val vectors = javaClass.getResourceAsStream("/session-proof-v2.json")!!.bufferedReader().use { it.readText() }
        for (element in JsonParser.parseString(vectors).asJsonArray) {
            val vector = element.asJsonObject
            fun field(key: String) = vector[key].asString
            assertEquals(field("derived_server_id"), MinecraftSessionProof.derive(
                BackendOrigin.parse(field("origin"), true), field("challenge_id"),
                field("server_id"), field("client_nonce"),
            ))
        }
    }

    @Test
    fun `each join has fresh randomness and uses its own origin`() {
        val joined = mutableListOf<String>()
        val first = MinecraftSessionProof.join(origin, challenge, joined::add)
        val second = MinecraftSessionProof.join(origin, challenge, joined::add)
        assertNotEquals(first, second)
        assertNotEquals(joined[0], joined[1])
        assertEquals(MinecraftSessionProof.derive(origin, challenge.challengeId, challenge.serverId, first), joined[0])
        assertNotEquals(joined[0], MinecraftSessionProof.derive(
            BackendOrigin.parse("https://attacker.example", false), challenge.challengeId, challenge.serverId, first,
        ))
        assertEquals(origin.proofAudience, BackendOrigin.parse("https://ODYSSEY.NOTES.SUPPLY:443/", false).proofAudience)
    }

    @Test
    fun `malformed and legacy challenges never authorize a join`() {
        for (invalid in listOf(
            challenge.copy(proofVersion = 0), challenge.copy(proofVersion = 3),
            challenge.copy(serverId = "-123456789abcdef"), challenge.copy(serverId = "A".repeat(40)),
            challenge.copy(challengeId = "short"), challenge.copy(expiresIn = 0),
        )) {
            var joined = false
            assertFailsWith<TransportException> { MinecraftSessionProof.join(origin, invalid) { joined = true } }
            assertTrue(!joined)
        }
    }

    @Test
    fun `failed join does not return a completion nonce`() {
        assertFailsWith<IllegalStateException> {
            MinecraftSessionProof.join(origin, challenge) { throw IllegalStateException("join failed") }
        }
    }
}
