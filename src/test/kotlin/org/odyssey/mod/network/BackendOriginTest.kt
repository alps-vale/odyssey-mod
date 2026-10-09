package org.odyssey.mod.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackendOriginTest {
    @Test
    fun `production requires TLS`() {
        assertFailsWith<IllegalArgumentException> {
            BackendOrigin.parse("http://127.0.0.1:8080", development = false)
        }
        val origin = BackendOrigin.parse("https://bridge.example.com", development = false)
        assertEquals("https", origin.http.scheme)
        assertEquals("wss", origin.webSocket.scheme)
    }

    @Test
    fun `development plaintext is loopback only`() {
        val origin = BackendOrigin.parse("http://localhost:8080", development = true)
        assertEquals("ws", origin.webSocket.scheme)
        assertFailsWith<IllegalArgumentException> {
            BackendOrigin.parse("http://bridge.example.com", development = true)
        }
    }

    @Test
    fun `origin cannot carry path credentials query or fragment`() {
        listOf(
            "https://bridge.example.com/api",
            "https://user@bridge.example.com",
            "https://bridge.example.com?token=secret",
            "https://bridge.example.com#fragment",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException> { BackendOrigin.parse(value, development = false) }
        }
    }
}
