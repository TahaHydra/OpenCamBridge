package com.opencambridge.android.server

import java.net.URI
import java.security.MessageDigest

data class CorsEndpoint(val host: String, val schemes: List<String>)

object ControlServerSecurityPolicy {
    val corsEndpoints = listOf(
        CorsEndpoint("tauri.localhost", listOf("http", "https")),
        CorsEndpoint("localhost:1420", listOf("http", "https")),
        CorsEndpoint("127.0.0.1:1420", listOf("http", "https"))
    )

    fun isLoopback(address: String?): Boolean = address in setOf(
        "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost"
    )

    fun shouldStripSecurityMutation(address: String?, touchesSecurity: Boolean): Boolean =
        touchesSecurity && !isLoopback(address)

    fun isCorsOriginAllowed(origin: String): Boolean = try {
        val parsed = URI(origin)
        val authority = parsed.rawAuthority ?: return false
        corsEndpoints.any { endpoint ->
            parsed.scheme in endpoint.schemes && authority.equals(endpoint.host, ignoreCase = true)
        }
    } catch (_: Exception) {
        false
    }

    fun constantTimeTokenMatches(expected: ByteArray, provided: String?): Boolean {
        if (provided == null || expected.isEmpty()) return false
        return MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected)
    }
}

/** Immutable authentication decision captured when the HTTP listener binds. */
class BoundAuthenticationSnapshot(accessMode: String, accessToken: String?) {
    val requiresToken: Boolean = accessMode != "usbOnly"
    private val token = accessToken.orEmpty().toByteArray(Charsets.UTF_8)

    fun authorizes(path: String, provided: String?): Boolean =
        path == "/health" || !requiresToken ||
            ControlServerSecurityPolicy.constantTimeTokenMatches(token, provided)
}
