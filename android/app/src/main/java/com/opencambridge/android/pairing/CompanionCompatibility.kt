package com.opencambridge.android.pairing

import kotlinx.serialization.Serializable

@Serializable internal data class CompanionCompatibility(
    val version: String,
    val controlProtocol: Int,
    val streamProtocol: Int,
    val minimumPhoneVersion: String
) {
    fun warning(phoneVersion: String): String? {
        if (controlProtocol != 1 || streamProtocol != 2) return "This PC uses an incompatible protocol. Install matching Android and Windows releases."
        fun parse(value: String): List<Int>? {
            if (!value.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) return null
            return value.split('.').map { it.toIntOrNull() ?: return null }
        }
        val current = parse(phoneVersion) ?: return "Cannot verify this phone's app version. Install matching releases."
        val minimum = parse(minimumPhoneVersion) ?: return "The PC supplied an unsupported minimum version. Install matching releases."
        for (index in 0..2) {
            if (current[index] != minimum[index]) return if (current[index] < minimum[index]) "This PC requires Android app $minimumPhoneVersion or newer." else null
        }
        return null
    }
}
