package org.odyssey.mod.network

import com.google.common.net.InetAddresses
import org.odyssey.mod.generated.BuildConfig
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

internal class BackendOrigin private constructor(val http: URI, val webSocket: URI) {
    val proofAudience: String
        get() {
            val defaultPort = if (http.scheme == "https") 443 else 80
            val port = if (http.port == defaultPort) -1 else http.port
            val host = http.host.lowercase()
            val canonicalHost = if (':' in host) {
                val address = InetAddresses.forString(host.removeSurrounding("[", "]"))
                // Java represents mapped IPv6 literals as IPv4; retain the URL's IPv6 form.
                val ipv6 = if (address is Inet6Address) address else Inet6Address.getByAddress(
                    null, ByteArray(16).also {
                        it[10] = -1
                        it[11] = -1
                        address.address.copyInto(it, 12)
                    }, -1,
                )
                InetAddresses.toAddrString(ipv6)
            } else host
            return URI(http.scheme, null, canonicalHost, port, null, null, null).toASCIIString()
        }

    companion object {
        fun configured(runtimeOverride: String? = System.getProperty("odyssey.backend_url")): BackendOrigin {
            if (runtimeOverride != null && !BuildConfig.DEVELOPMENT) {
                throw IllegalArgumentException("Runtime backend overrides are development-only")
            }
            return parse(runtimeOverride ?: BuildConfig.BACKEND_URL, BuildConfig.DEVELOPMENT)
        }

        fun parse(value: String, development: Boolean): BackendOrigin {
            val origin = URI(value)
            require(origin.isAbsolute && origin.host != null) { "Backend URL must be an absolute origin" }
            require(origin.rawUserInfo == null && origin.rawQuery == null && origin.rawFragment == null) {
                "Backend URL must not contain credentials, a query, or a fragment"
            }
            require(origin.path.isEmpty() || origin.path == "/") { "Backend URL must not contain a path" }

            val scheme = origin.scheme.lowercase()
            if (scheme != "https") {
                require(development && scheme == "http" && isLoopback(origin.host)) {
                    "Plaintext backend URLs are allowed only on loopback in development builds"
                }
            }
            val normalized = URI(scheme, null, origin.host, origin.port, null, null, null)
            val socketScheme = if (scheme == "https") "wss" else "ws"
            val socket = URI(socketScheme, null, origin.host, origin.port, null, null, null)
            return BackendOrigin(normalized, socket)
        }

        private fun isLoopback(host: String): Boolean =
            host.equals("localhost", ignoreCase = true) ||
                runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
    }
}
