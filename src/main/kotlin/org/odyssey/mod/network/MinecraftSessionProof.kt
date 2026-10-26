package org.odyssey.mod.network

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The backend supplies a seed; only this client can choose the final Mojang proof. */
internal object MinecraftSessionProof {
    private const val VERSION = 2
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val seedPattern = Regex("[0-9a-f]{40}")

    fun join(origin: BackendOrigin, challenge: MinecraftChallenge, joinServer: (String) -> Unit): String {
        if (challenge.proofVersion != VERSION) {
            throw TransportException("session_proof_required", false, "The backend must support session proof v2")
        }
        if (challenge.expiresIn !in 1..60) invalidChallenge()
        val nonce = encoder.encodeToString(ByteArray(32).also(random::nextBytes))
        val serverId = derive(origin, challenge.challengeId, challenge.serverId, nonce)
        joinServer(serverId)
        // Reveal the nonce only after Mojang has accepted this locally chosen proof.
        return nonce
    }

    internal fun derive(origin: BackendOrigin, challengeId: String, seed: String, clientNonce: String): String {
        decodeNonce(challengeId)
        if (!seedPattern.matches(seed)) invalidChallenge()
        val seedBytes = ByteArray(20) { seed.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val hash = MessageDigest.getInstance("SHA-256")
        for (field in listOf(
            "odyssey/minecraft-session-proof/v2".toByteArray(Charsets.UTF_8),
            "account".toByteArray(Charsets.UTF_8),
            origin.proofAudience.toByteArray(Charsets.UTF_8),
            challengeId.toByteArray(Charsets.UTF_8),
            seedBytes,
            decodeNonce(clientNonce),
        )) {
            hash.update(ByteBuffer.allocate(4).putInt(field.size).array())
            hash.update(field)
        }
        return hash.digest().take(20).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun decodeNonce(value: String): ByteArray {
        if (value.length != 43) invalidChallenge()
        val bytes = try {
            decoder.decode(value)
        } catch (_: IllegalArgumentException) {
            invalidChallenge()
        }
        if (bytes.size != 32 || encoder.encodeToString(bytes) != value) invalidChallenge()
        return bytes
    }

    private fun invalidChallenge(): Nothing =
        throw TransportException("invalid_challenge", false, "The backend sent an invalid authentication challenge")
}
