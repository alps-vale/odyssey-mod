package org.odyssey.mod.network

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TransportTest {
    @Test
    fun `challenge decodes proof version and fails closed when it is missing`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        server.createContext("/api/v1/auth/minecraft/challenge") { exchange ->
            val version = if (requests.getAndIncrement() == 0) ",\"proof_version\":2" else ""
            val response = """
                {"challenge_id":"ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
                 "server_id":"${"a".repeat(40)}","expires_in":60$version}
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val origin = BackendOrigin.parse("http://127.0.0.1:${server.address.port}", true)
            val transport = JavaOdysseyTransport(origin, "test")
            val challenge = transport.challenge()
            assertEquals(2, challenge.proofVersion)
            assertEquals(60L, challenge.expiresIn)
            assertEquals("a".repeat(40), challenge.serverId)
            val legacy = transport.challenge()
            assertEquals(0, legacy.proofVersion)
            var joined = false
            assertFailsWith<TransportException> { MinecraftSessionProof.join(origin, legacy) { joined = true } }
            assertFalse(joined)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `completion sends client nonce on the wire`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var body = ""
        server.createContext("/api/v1/auth/minecraft/complete") { exchange ->
            body = exchange.requestBody.bufferedReader().use { it.readText() }
            val response = """{"token":"test","expires_at":"2026-10-26T00:00:00Z","minecraft_uuid":"test-uuid"}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val transport = JavaOdysseyTransport(
                BackendOrigin.parse("http://127.0.0.1:${server.address.port}", true), "test",
            )
            transport.complete("challenge", "Alice", "client-nonce")
            val fields = JsonParser.parseString(body).asJsonObject
            assertEquals(setOf("challenge_id", "username", "client_nonce"), fields.keySet())
            assertEquals("client-nonce", fields["client_nonce"].asString)
            assertEquals("challenge", fields["challenge_id"].asString)
            assertEquals("Alice", fields["username"].asString)
        } finally {
            server.stop(0)
        }
    }
}
