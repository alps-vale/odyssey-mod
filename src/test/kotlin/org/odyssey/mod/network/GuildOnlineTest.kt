package org.odyssey.mod.network

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNull

class GuildOnlineTest {
    @Test
    fun `omitted optional status fields remain unknown rather than rejecting the report`() {
        val report = Json.decodeFromString<GuildOnlineSnapshot>("""
            {"refreshed_at":"2026-10-10T12:00:00Z","members":[
                {"username":"Alice","guild":{"uuid":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","prefix":"Alps"},"mod_versions":[]}
            ]}
        """.trimIndent())
        assertNull(report.members.single().online)
        assertNull(report.members.single().server)
    }
}
