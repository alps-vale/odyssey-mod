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
internal const val MAX_ITEM_NAME_CODE_POINTS = 128
internal const val MAX_GUILD_BODY_CODE_POINTS = 400
internal const val MAX_DISCORD_BODY_CODE_POINTS = 2_000
internal const val MAX_DISCORD_LINES = 8
internal const val MAX_PRESENTATION_ENTRIES = 4_096
internal const val MAX_REMOTE_MESSAGE_CODE_POINTS = 256
internal const val MAX_GUILD_EVENT_NAME_CHARS = 32
internal const val MAX_GUILD_EVENT_TEXT_CODE_POINTS = 64
internal const val MAX_BANK_COUNT = 9_999
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

    /** Guild-channel content reported for corroboration and acknowledged by [id]. */
    sealed interface Observation : ClientMessage {
        val id: String
    }

    @Serializable
    @SerialName("guild_observation")
    data class GuildObservation(
        override val v: Int,
        override val id: String,
        @SerialName("author_username") val authorUsername: String,
        val content: String,
        @SerialName("item_shares") val itemShares: List<ItemShare> = emptyList(),
    ) : Observation

    @Serializable
    @SerialName("guild_event")
    data class GuildEventObservation(override val v: Int, override val id: String, val event: GuildEvent) : Observation
}

/** A Wynncraft guild-channel system message, carrying names exactly as the game displayed them. */
@Serializable
internal sealed interface GuildEvent {
    @Serializable
    @SerialName("member_joined")
    data class MemberJoined(val player: String) : GuildEvent

    @Serializable
    @SerialName("member_left")
    data class MemberLeft(val player: String) : GuildEvent

    @Serializable
    @SerialName("member_kicked")
    data class MemberKicked(val actor: String, val player: String) : GuildEvent

    @Serializable
    @SerialName("member_invited")
    data class MemberInvited(val actor: String, val player: String) : GuildEvent

    @Serializable
    @SerialName("member_uninvited")
    data class MemberUninvited(val actor: String, val player: String) : GuildEvent

    @Serializable
    @SerialName("rank_changed")
    data class RankChanged(val actor: String, val player: String, val from: GuildRank, val to: GuildRank) : GuildEvent

    @Serializable
    @SerialName("alliance_formed")
    data class AllianceFormed(val actor: String, val guild: String) : GuildEvent

    @Serializable
    @SerialName("alliance_revoked")
    data class AllianceRevoked(val actor: String, val guild: String) : GuildEvent

    /** A transfer in the bank tab every member can use. */
    @Serializable
    @SerialName("bank_deposit")
    data class BankDeposit(val player: String, val count: Int, val item: String) : GuildEvent

    @Serializable
    @SerialName("bank_withdrawal")
    data class BankWithdrawal(val player: String, val count: Int, val item: String) : GuildEvent

    @Serializable
    @SerialName("member_rewarded")
    data class MemberRewarded(val actor: String, val player: String, val reward: String) : GuildEvent

    /** The sender can be a displayed player nickname or the allied guild itself. */
    @Serializable
    @SerialName("tribute_scheduled")
    data class TributeScheduled(val sender: String, val amount: Int, val resource: GuildResource, val guild: String) : GuildEvent

    @Serializable
    @SerialName("resource_gifted")
    data class ResourceGifted(val sender: String, val amount: Int, val resource: GuildResource, val guild: String) : GuildEvent

    @Serializable
    @SerialName("territory_upgrades_changed")
    data class TerritoryUpgradesChanged(val actor: String, val count: Int, val territory: String) : GuildEvent

    @Serializable
    @SerialName("territory_bonus_set")
    data class TerritoryBonusSet(val actor: String, val bonus: String, val level: Int, val territory: String) : GuildEvent

    @Serializable
    @SerialName("territory_bonuses_changed")
    data class TerritoryBonusesChanged(val actor: String, val count: Int, val territory: String) : GuildEvent

    @Serializable
    @SerialName("territory_loadout_applied")
    data class TerritoryLoadoutApplied(val actor: String, val loadout: String, val territory: String) : GuildEvent
}

/** Treasury resource wording includes the observed server icon and quantity agreement. */
@Serializable
internal enum class GuildResource(val icon: String, private val singular: String, private val plural: String = singular) {
    @SerialName("emeralds") EMERALDS("", "Emerald", "Emeralds"),
    @SerialName("ore") ORE("Ⓑ ", "Ore"),
    @SerialName("wood") WOOD("Ⓒ ", "Wood"),
    @SerialName("fish") FISH("Ⓚ ", "Fish"),
    @SerialName("crops") CROPS("Ⓙ ", "Crop", "Crops"),
    ;

    fun label(amount: Int): String = if (amount == 1) singular else plural
}

internal fun GuildEvent.requiresManagementCapability(): Boolean = when (this) {
    is GuildEvent.TributeScheduled, is GuildEvent.ResourceGifted,
    is GuildEvent.TerritoryUpgradesChanged, is GuildEvent.TerritoryBonusSet,
    is GuildEvent.TerritoryBonusesChanged, is GuildEvent.TerritoryLoadoutApplied -> true
    is GuildEvent.MemberJoined, is GuildEvent.MemberLeft, is GuildEvent.MemberKicked,
    is GuildEvent.MemberInvited, is GuildEvent.MemberUninvited, is GuildEvent.RankChanged,
    is GuildEvent.AllianceFormed, is GuildEvent.AllianceRevoked, is GuildEvent.BankDeposit,
    is GuildEvent.BankWithdrawal, is GuildEvent.MemberRewarded -> false
}

@Serializable
internal enum class GuildRank(val label: String) {
    @SerialName("recruit") RECRUIT("Recruit"),
    @SerialName("recruiter") RECRUITER("Recruiter"),
    @SerialName("captain") CAPTAIN("Captain"),
    @SerialName("strategist") STRATEGIST("Strategist"),
    @SerialName("chief") CHIEF("Chief"),
    @SerialName("owner") OWNER("Owner"),
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
                    require(isSafeRemoteText(it.name, MAX_ITEM_NAME_CODE_POINTS) && it.color in 0..MAX_RGB)
                    require(it.png == null || it.png.length <= (MAX_TOOLTIP_PNG_BYTES + 2) / 3 * 4)
                }
            }
            is ClientMessage.GuildEventObservation -> {
                requireUuid(message.id)
                require(isValid(message.event)) { "Invalid guild event" }
            }
        }
    }

    private fun isValid(event: GuildEvent): Boolean = when (event) {
        is GuildEvent.MemberJoined -> isGuildEventName(event.player)
        is GuildEvent.MemberLeft -> isGuildEventName(event.player)
        is GuildEvent.MemberKicked -> isGuildEventName(event.actor) && isGuildEventName(event.player)
        is GuildEvent.MemberInvited -> isGuildEventName(event.actor) && isGuildEventName(event.player)
        is GuildEvent.MemberUninvited -> isGuildEventName(event.actor) && isGuildEventName(event.player)
        is GuildEvent.RankChanged ->
            isGuildEventName(event.actor) && isGuildEventName(event.player) && event.from != event.to
        is GuildEvent.AllianceFormed -> isGuildEventName(event.actor) && isWynncraftGuildName(event.guild)
        is GuildEvent.AllianceRevoked -> isGuildEventName(event.actor) && isWynncraftGuildName(event.guild)
        is GuildEvent.BankDeposit -> isBankTransfer(event.player, event.count, event.item)
        is GuildEvent.BankWithdrawal -> isBankTransfer(event.player, event.count, event.item)
        is GuildEvent.MemberRewarded ->
            isGuildEventName(event.actor) && isGuildEventName(event.player) && isGuildEventText(event.reward)
        is GuildEvent.TributeScheduled ->
            isGuildEventName(event.sender) && event.amount > 0 && isWynncraftGuildName(event.guild)
        is GuildEvent.ResourceGifted ->
            isGuildEventName(event.sender) && event.amount > 0 && isWynncraftGuildName(event.guild)
        is GuildEvent.TerritoryUpgradesChanged ->
            isGuildEventName(event.actor) && event.count in 2..MAX_BANK_COUNT && isGuildEventText(event.territory)
        is GuildEvent.TerritoryBonusesChanged ->
            isGuildEventName(event.actor) && event.count in 2..MAX_BANK_COUNT && isGuildEventText(event.territory)
        is GuildEvent.TerritoryBonusSet ->
            isGuildEventName(event.actor) && isGuildEventText(event.bonus) && event.level in 0..999 && isGuildEventText(event.territory)
        is GuildEvent.TerritoryLoadoutApplied ->
            isGuildEventName(event.actor) && isGuildEventText(event.loadout) && isGuildEventText(event.territory)
    }

    private fun isBankTransfer(player: String, count: Int, item: String): Boolean =
        isGuildEventName(player) && count in 1..MAX_BANK_COUNT && isGuildEventText(item)

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

/** A displayed player or guild name: single-spaced words, since nicknames may contain spaces. */
internal fun isGuildEventName(value: String): Boolean = isSpacedName(value) { it.isLetterOrDigit() || it == '_' }

internal fun isWynncraftGuildName(value: String): Boolean = isSpacedName(value, Char::isLetter)

/** Server-written item or reward text, without formatting codes, private glyphs or invisible characters. */
internal fun isGuildEventText(value: String): Boolean =
    value.codePointLength() in 1..MAX_GUILD_EVENT_TEXT_CODE_POINTS &&
        value.split(' ').all(String::isNotEmpty) &&
        value.codePoints().noneMatch { codePoint -> hiddenCodePoints.any { codePoint in it } }

// Mirrors the backend's list exactly; a frame it rejects would close the bridge.
private val hiddenCodePoints = listOf(
    0x00..0x1F, 0x7F..0x9F, 0xA7..0xA7, 0xAD..0xAD, 0x061C..0x061C, 0x180E..0x180E, 0x200B..0x200F,
    0x2028..0x202E, 0x2060..0x206F, 0xD800..0xF8FF, 0xFE00..0xFE0F, 0xFEFF..0xFEFF, 0xFFF9..0xFFFB,
    0xE0000..0xE007F, 0xF0000..0x10FFFF,
)

private fun isSpacedName(value: String, allowed: (Char) -> Boolean): Boolean =
    value.length <= MAX_GUILD_EVENT_NAME_CHARS &&
        value.split(' ').all { word -> word.isNotEmpty() && word.all { it.code < 0x80 && allowed(it) } }

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
