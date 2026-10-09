package org.odyssey.mod.network

import org.odyssey.mod.generated.BuildConfig
import java.net.InetAddress
import java.net.URI

internal class BackendOrigin private constructor(val http: URI, val webSocket: URI) {
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
