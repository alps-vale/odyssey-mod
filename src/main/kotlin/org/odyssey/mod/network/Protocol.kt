package org.odyssey.mod.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

internal const val PROTOCOL_VERSION = 2
internal const val MAX_FRAME_BYTES = 8 * 1024
internal const val MAX_CLIENT_FRAME_BYTES = 320 * 1024
internal const val MAX_ITEM_SHARES = 3
internal const val MAX_TOOLTIP_PNG_BYTES = 64 * 1024
internal const val MAX_GUILD_BODY_CODE_POINTS = 400
internal const val MAX_DISCORD_BODY_CODE_POINTS = 2_000
internal const val MAX_DISCORD_LINES = 8
internal const val MAX_PRESENTATION_ENTRIES = 4_096
internal const val MAX_REMOTE_MESSAGE_CODE_POINTS = 256
private const val MAX_GUILD_PREFIX_CODE_POINTS = 16
private const val MAX_RGB = 0x00ffffff
private const val MAX_CHAT_DISPLAY_NAME_CODE_POINTS = 64
private const val MAX_REMOTE_CODE_CODE_POINTS = 64
private val minecraftUsername = Regex("[A-Za-z0-9_]{1,16}")
private val remoteCode = Regex("[a-z0-9_]{1,$MAX_REMOTE_CODE_CODE_POINTS}")

@Serializable
internal sealed interface ClientMessage {
    val v: Int

    @Serializable
    @SerialName("observer_state")
    data class ObserverState(override val v: Int, val active: Boolean) : ClientMessage

    @Serializable
    @SerialName("guild_observation")
    data class GuildObservation(
        override val v: Int,
        val id: String,
        @SerialName("author_username") val authorUsername: String,
        val content: String,
        @SerialName("item_shares") val itemShares: List<ItemShare> = emptyList(),
    ) : ClientMessage
}

@Serializable
internal enum class ItemShareKind {
    @SerialName("wynntils") WYNNTILS,
    @SerialName("wynncraft") WYNNCRAFT,
}

@Serializable
internal data class ItemShare(
    val kind: ItemShareKind,
    val encoded: String,
    val name: String,
    val color: Int,
    val png: String? = null,
)

@Serializable
internal sealed interface ServerMessage {
    val v: Int

    @Serializable
    @SerialName("welcome")
    data class Welcome(
        override val v: Int,
        val identity: MinecraftIdentity,
        val guild: GuildRef,
        val capabilities: List<String>,
        @SerialName("presentation_revision") val presentationRevision: Long,
    ) : ServerMessage

    @Serializable
    @SerialName("presentation_snapshot")
    data class PresentationSnapshot(
        override val v: Int,
        val revision: Long,
        val entries: List<PresentationEntry>,
        val complete: Boolean,
    ) : ServerMessage

    @Serializable
    @SerialName("observation_result")
    data class ObservationResult(
        override val v: Int,
        val id: String,
        val status: ObservationStatus,
        @SerialName("event_id") val eventId: String? = null,
        val reason: String? = null,
    ) : ServerMessage

    @Serializable
    @SerialName("chat")
    data class Chat(
        override val v: Int,
        @SerialName("event_id") val eventId: String,
        val source: ChatSource,
        @SerialName("origin_guild") val originGuild: GuildRef? = null,
        val author: ChatAuthor,
        val content: String,
        @SerialName("sent_at") val sentAt: String,
    ) : ServerMessage

    @Serializable
    @SerialName("presentation_upsert")
    data class PresentationUpsert(
        override val v: Int,
        val revision: Long,
        val presentation: PresentationEntry,
    ) : ServerMessage

    @Serializable
    @SerialName("presentation_remove")
    data class PresentationRemove(
        override val v: Int,
        val revision: Long,
        @SerialName("minecraft_uuid") val minecraftUuid: String,
    ) : ServerMessage

    @Serializable
    @SerialName("error")
    data class Error(
        override val v: Int,
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : ServerMessage
}

@Serializable
internal data class MinecraftIdentity(val uuid: String, val username: String)

@Serializable
internal data class GuildRef(val uuid: String, val prefix: String)

@Serializable
internal data class PresentationEntry(
    @SerialName("minecraft_uuid") val minecraftUuid: String,
    @SerialName("minecraft_username") val minecraftUsername: String,
    val role: RankPresentation,
)

@Serializable
internal data class RankPresentation(val label: String, val colors: RankColors)

@Serializable
internal data class RankColors(
    val primary: Int,
    val secondary: Int? = null,
    val tertiary: Int? = null,
)

@Serializable
internal enum class ObservationStatus {
    @SerialName("pending") PENDING,
    @SerialName("accepted") ACCEPTED,
    @SerialName("rejected") REJECTED,
}

@Serializable
internal enum class ChatSource {
    @SerialName("discord") DISCORD,
    @SerialName("wynn") WYNN,
}

@Serializable
internal data class ChatAuthor(
    @SerialName("discord_id") val discordId: String? = null,
    @SerialName("minecraft_uuid") val minecraftUuid: String? = null,
    @SerialName("display_name") val displayName: String,
    val role: RankPresentation? = null,
    @SerialName("wynn_rank") val wynnRank: String? = null,
)

internal sealed interface ProtocolFrame {
    data class Text(val text: String) : ProtocolFrame
    data class Binary(val bytes: ByteArray) : ProtocolFrame
}

internal object ProtocolCodec {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun decodeClient(frame: ProtocolFrame): ClientMessage {
        require(frame is ProtocolFrame.Text) { "Binary WebSocket frames are not supported" }
        requireFrameSize(frame.text, MAX_CLIENT_FRAME_BYTES)
        return json.decodeFromString<ClientMessage>(frame.text).also(::validate)
    }

    fun decodeServer(frame: ProtocolFrame): ServerMessage {
        require(frame is ProtocolFrame.Text) { "Binary WebSocket frames are not supported" }
        requireFrameSize(frame.text)
        return json.decodeFromString<ServerMessage>(frame.text).also(::validate)
    }

    fun encode(message: ClientMessage): String {
        validate(message)
        return json.encodeToString(ClientMessage.serializer(), message).also { requireFrameSize(it, MAX_CLIENT_FRAME_BYTES) }
    }

    fun encode(message: ServerMessage): String {
        validate(message)
        return json.encodeToString(ServerMessage.serializer(), message).also(::requireFrameSize)
    }

    private fun validate(message: ClientMessage) {
        requireVersion(message.v)
        when (message) {
            is ClientMessage.ObserverState -> Unit
            is ClientMessage.GuildObservation -> {
                requireUuid(message.id)
                require(minecraftUsername.matches(message.authorUsername)) {
                    "Invalid observation author"
                }
                require(
                    message.content.isNotEmpty() &&
                        message.content.codePointLength() <= MAX_GUILD_BODY_CODE_POINTS &&
                        '\n' !in message.content && '\r' !in message.content,
                ) { "Invalid guild-chat body" }
                require(message.itemShares.size <= MAX_ITEM_SHARES) { "Too many item shares" }
                message.itemShares.forEachIndexed { index, share ->
                    if (share.kind == ItemShareKind.WYNNTILS) {
                        require(message.itemShares.take(index).none { it.encoded == share.encoded })
                    } else {
                        require(message.itemShares.count { it.encoded == share.encoded } <=
                            countOccurrences(message.content, share.encoded))
                    }
                }
                message.itemShares.forEach {
                    require(it.encoded.isNotEmpty() && it.encoded in message.content)
                    require(isSafeRemoteText(it.name, 128) && it.color in 0..MAX_RGB)
                    require(it.png == null || it.png.length <= (MAX_TOOLTIP_PNG_BYTES + 2) / 3 * 4)
                }
            }
        }
    }

    private fun validate(message: ServerMessage) {
        requireVersion(message.v)
        when (message) {
            is ServerMessage.Welcome -> {
                validate(message.identity)
                validate(message.guild)
                require(message.presentationRevision >= 0) { "Invalid presentation revision" }
            }
            is ServerMessage.ObservationResult -> {
                requireUuid(message.id)
                message.eventId?.let(::requireUuid)
                message.reason?.let {
                    require(isSafeRemoteCode(it)) { "Invalid observation reason" }
                }
            }
            is ServerMessage.Chat -> {
                requireUuid(message.eventId)
                require((message.source == ChatSource.DISCORD) == (message.originGuild == null)) {
                    "Chat source and origin guild disagree"
                }
                message.originGuild?.let(::validate)
                validate(message.author)
                when (message.source) {
                    ChatSource.WYNN -> require(
                        message.content.isNotEmpty() &&
                            message.content.codePointLength() <= MAX_GUILD_BODY_CODE_POINTS &&
                            '\n' !in message.content && '\r' !in message.content,
                    ) { "Invalid Wynn chat body" }
                    ChatSource.DISCORD -> require(
                        message.content.isNotEmpty() &&
                            message.content.codePointLength() <= MAX_DISCORD_BODY_CODE_POINTS &&
                            message.content.count { it == '\n' } + 1 <= MAX_DISCORD_LINES,
                    ) { "Invalid Discord chat body" }
                }
            }
            is ServerMessage.PresentationSnapshot -> {
                require(message.revision >= 0) { "Invalid presentation revision" }
                require(
                    (message.complete && message.entries.isEmpty()) ||
                        (!message.complete && message.entries.size == 1),
                ) { "Invalid presentation snapshot frame" }
                message.entries.forEach(::validate)
            }
            is ServerMessage.PresentationUpsert -> {
                require(message.revision >= 0) { "Invalid presentation revision" }
                validate(message.presentation)
            }
            is ServerMessage.PresentationRemove -> {
                require(message.revision >= 0) { "Invalid presentation revision" }
                requireUuid(message.minecraftUuid)
            }
            is ServerMessage.Error -> {
                require(isSafeRemoteCode(message.code)) { "Invalid server error code" }
                require(isSafeRemoteText(message.message)) { "Invalid server error message" }
            }
        }
    }

    private fun validate(identity: MinecraftIdentity) {
        requireUuid(identity.uuid)
        require(minecraftUsername.matches(identity.username)) { "Invalid Minecraft identity" }
    }

    private fun validate(guild: GuildRef) {
        requireUuid(guild.uuid)
        require(isSafeRemoteText(guild.prefix, MAX_GUILD_PREFIX_CODE_POINTS)) {
            "Invalid guild prefix"
        }
    }

    private fun validate(entry: PresentationEntry) {
        requireUuid(entry.minecraftUuid)
        require(minecraftUsername.matches(entry.minecraftUsername)) {
            "Invalid presentation username"
        }
        validate(entry.role)
    }

    private fun validate(author: ChatAuthor) {
        require(author.discordId != null || author.minecraftUuid != null) { "Chat author has no stable identity" }
        author.discordId?.let {
            val parsed = it.toULongOrNull()
            require(parsed != null && parsed != 0uL && parsed.toString() == it) { "Invalid Discord ID" }
        }
        author.minecraftUuid?.let(::requireUuid)
        require(isSafeRemoteText(author.displayName, MAX_CHAT_DISPLAY_NAME_CODE_POINTS)) {
            "Invalid chat display name"
        }
        author.wynnRank?.let {
            require(isSafeRemoteText(it, 100)) { "Invalid Wynn rank" }
        }
        author.role?.let(::validate)
    }

    private fun validate(role: RankPresentation) {
        require(isSafeRemoteText(role.label, 100)) { "Invalid rank label" }
        validateColor(role.colors.primary)
        role.colors.secondary?.let(::validateColor)
        role.colors.tertiary?.let(::validateColor)
    }

    private fun validateColor(color: Int) {
        require(color in 1..MAX_RGB) { "Rank color must be a nonzero 24-bit RGB value" }
    }

    private fun requireVersion(version: Int) {
        require(version == PROTOCOL_VERSION) { "Unsupported protocol version $version" }
    }

    private fun requireFrameSize(text: String, limit: Int = MAX_FRAME_BYTES) {
        require(text.toByteArray(Charsets.UTF_8).size <= limit) { "WebSocket frame exceeds its size limit" }
    }

    private fun countOccurrences(content: String, reference: String): Int {
        if (reference.isEmpty()) return 0
        var count = 0
        var offset = 0
        while (true) {
            val next = content.indexOf(reference, offset)
            if (next < 0) return count
            count += 1
            offset = next + reference.length
        }
    }

    private fun requireUuid(value: String) {
        require(UUID.fromString(value).toString() == value) { "UUID is not canonical" }
    }
}

internal fun isSafeRemoteCode(value: String): Boolean = remoteCode.matches(value)

internal fun isSafeRemoteText(
    value: String,
    maxCodePoints: Int = MAX_REMOTE_MESSAGE_CODE_POINTS,
): Boolean =
    value.isNotEmpty() &&
        value.codePointLength() <= maxCodePoints &&
        value.codePoints().noneMatch {
            Character.isISOControl(it) || it in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
        }

private fun String.codePointLength(): Int = codePointCount(0, length)
