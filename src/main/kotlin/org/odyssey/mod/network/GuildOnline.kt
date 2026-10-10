package org.odyssey.mod.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GuildOnlineSnapshot(
    @SerialName("refreshed_at") val refreshedAt: String,
    val members: List<OnlineMember>,
)

@Serializable
internal data class OnlineMember(
    val username: String,
    val guild: GuildRef,
    val online: Boolean?,
    val server: String?,
    @SerialName("mod_versions") val modVersions: List<String>,
)
