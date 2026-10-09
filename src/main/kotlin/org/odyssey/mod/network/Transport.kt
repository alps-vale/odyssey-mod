package org.odyssey.mod.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal interface OdysseyTransport {
    suspend fun challenge(): MinecraftChallenge
    suspend fun complete(challengeId: String, username: String): OdysseySession
    suspend fun openSocket(token: String, events: SocketEvents): BridgeSocket
}

internal interface SocketEvents {
    fun text(socket: BridgeSocket, fragment: String, last: Boolean)
    fun binary(socket: BridgeSocket, bytes: ByteArray, last: Boolean)
    fun ping(socket: BridgeSocket, bytes: ByteArray)
    fun pong(socket: BridgeSocket, bytes: ByteArray)
    fun closed(socket: BridgeSocket, statusCode: Int, reason: String)
    fun failed(socket: BridgeSocket, error: Throwable)
}

internal interface BridgeSocket {
    fun requestNext()
    fun send(text: String): CompletionStage<*>
    fun pong(bytes: ByteArray): CompletionStage<*>
    fun close(statusCode: Int = 1000, reason: String = ""): CompletionStage<*>
}

@Serializable
internal data class MinecraftChallenge(
    @kotlinx.serialization.SerialName("challenge_id") val challengeId: String,
    @kotlinx.serialization.SerialName("server_id") val serverId: String,
    @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long,
)

@Serializable
internal data class OdysseySession(
    val token: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String,
    @kotlinx.serialization.SerialName("minecraft_uuid") val minecraftUuid: String,
) {
    fun expiry(): Instant = Instant.parse(expiresAt)
}

internal class TransportException(
    val code: String,
    val retryable: Boolean,
    message: String,
) : RuntimeException(message)

internal class JavaOdysseyTransport(
    private val origin: BackendOrigin,
    private val modVersion: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : OdysseyTransport {
    private val restJson = Json { ignoreUnknownKeys = true }

    override suspend fun challenge(): MinecraftChallenge {
        val request = request("/api/v1/auth/minecraft/challenge")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        return restJson.decodeFromString(send(request))
    }

    override suspend fun complete(challengeId: String, username: String): OdysseySession {
        val payload = restJson.encodeToString(
            CompleteRequest.serializer(),
            CompleteRequest(challengeId, username),
        )
        val request = request("/api/v1/auth/minecraft/complete")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        return restJson.decodeFromString(send(request))
    }

    override suspend fun openSocket(token: String, events: SocketEvents): BridgeSocket {
        val listener = JavaSocketListener(events)
        val socket = suspendCancellableCoroutine { continuation ->
            val future = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer $token")
                .header("X-Odyssey-Mod-Version", modVersion)
                .subprotocols("odyssey.bridge.v2")
                .buildAsync(origin.webSocket.resolve("/api/v1/ws"), listener)
            continuation.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { webSocket, error ->
                if (error != null) continuation.resumeWithException(error)
                else continuation.resume(webSocket)
            }
        }
        return listener.bind(socket)
    }

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder()
        .uri(origin.http.resolve(path))
        .timeout(Duration.ofSeconds(15))
        .header("Accept", "application/json")
        .header("X-Odyssey-Mod-Version", modVersion)

    private suspend fun send(request: HttpRequest): String {
        val response = suspendCancellableCoroutine { continuation ->
            val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            continuation.invokeOnCancellation { future.cancel(true) }
            future.whenComplete { value, error ->
                if (error != null) continuation.resumeWithException(error)
                else continuation.resume(value)
            }
        }
        if (response.statusCode() in 200..299) return response.body()
        val body = runCatching { restJson.parseToJsonElement(response.body()).jsonObject }.getOrNull()
        val code = body?.get("error")
            ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf(::isSafeRemoteCode)
            ?: "http_${response.statusCode()}"
        val retryable = body?.get("retryable")
            ?.let { runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull() }
            ?: (response.statusCode() == 429 || response.statusCode() >= 500)
        val message = body?.get("message")
            ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf(::isSafeRemoteText)
            ?: "Odyssey request failed"
        throw TransportException(code, retryable, message)
    }
}

@Serializable
private data class CompleteRequest(
    @kotlinx.serialization.SerialName("challenge_id") val challengeId: String,
    val username: String,
)

private class JavaSocketListener(private val events: SocketEvents) : WebSocket.Listener {
    @Volatile
    private var socket: JavaBridgeSocket? = null

    fun bind(webSocket: WebSocket): BridgeSocket =
        socket ?: JavaBridgeSocket(webSocket).also { socket = it }

    override fun onOpen(webSocket: WebSocket) {
        // BridgeClient requests the first frame only after its actor accepts this socket.
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<Void> {
        events.text(requireSocket(webSocket), data.toString(), last)
        return CompletableFuture.completedFuture(null)
    }

    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletableFuture<Void> {
        events.binary(requireSocket(webSocket), data.copyBytes(), last)
        return CompletableFuture.completedFuture(null)
    }

    override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletableFuture<Void> {
        events.ping(requireSocket(webSocket), message.copyBytes())
        return CompletableFuture.completedFuture(null)
    }

    override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletableFuture<Void> {
        events.pong(requireSocket(webSocket), message.copyBytes())
        return CompletableFuture.completedFuture(null)
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<Void> {
        events.closed(requireSocket(webSocket), statusCode, reason)
        return CompletableFuture.completedFuture(null)
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        events.failed(requireSocket(webSocket), error)
    }

    private fun requireSocket(webSocket: WebSocket): JavaBridgeSocket =
        socket ?: JavaBridgeSocket(webSocket).also { socket = it }
}

private class JavaBridgeSocket(private val socket: WebSocket) : BridgeSocket {
    private var outbound = CompletableFuture.completedFuture(socket)

    override fun requestNext() {
        socket.request(1)
    }

    override fun send(text: String): CompletionStage<*> =
        enqueue { it.sendText(text, true) }

    override fun pong(bytes: ByteArray): CompletionStage<*> =
        enqueue { it.sendPong(ByteBuffer.wrap(bytes)) }

    override fun close(statusCode: Int, reason: String): CompletionStage<*> =
        enqueue(recoverFailedQueue = true) { it.sendClose(statusCode, reason) }

    @Synchronized
    private fun enqueue(
        recoverFailedQueue: Boolean = false,
        operation: (WebSocket) -> CompletableFuture<WebSocket>,
    ): CompletionStage<*> {
        val predecessor = if (recoverFailedQueue) outbound.handle { _, _ -> socket } else outbound
        val next = predecessor.thenCompose(operation)
        outbound = next
        return next
    }
}

private fun ByteBuffer.copyBytes(): ByteArray {
    val copy = slice()
    return ByteArray(copy.remaining()).also(copy::get)
}
