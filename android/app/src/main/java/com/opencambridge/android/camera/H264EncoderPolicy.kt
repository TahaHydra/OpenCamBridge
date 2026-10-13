package com.opencambridge.android.camera

/** Preserve MediaCodecList platform priority within each hardware/evidence tier.
 * Missing or non-covering performance guarantees never reject an otherwise
 * declared size/rate: upgraded vendor HALs frequently omit these guarantees. */
internal object H264EncoderPolicy {
    data class Evidence(val name: String, val hardware: Boolean, val softwareOnly: Boolean,
                        val surface: Boolean, val sizeRate: Boolean, val performanceCovered: Boolean?)
    fun rank(candidates: List<Evidence>): List<Evidence> = candidates
        .filter { !it.softwareOnly && it.surface && it.sizeRate }
        .distinctBy { it.name }
        .sortedWith(compareByDescending<Evidence> { it.hardware }
            .thenByDescending { it.performanceCovered == true })
}

/** In-process hints, never capability evidence. An OS/app restart invalidates
 * these automatically; callers include build/camera/mode/settings in the key.
 * Only a real encoded IDR marks success, and every attempt still rechecks the
 * current camera and codec capabilities. Bounded to avoid retaining arbitrary
 * user-created mode/settings combinations for the service's entire lifetime. */
internal class H264EncoderHistory {
    private val successful = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 128
    }
    @Synchronized fun succeeded(tuple: String, codec: String) { successful[tuple] = codec }
    @Synchronized fun failed(tuple: String, codec: String) {
        if (successful[tuple] == codec) successful.remove(tuple)
    }
    @Synchronized fun order(tuple: String, names: List<String>): List<String> {
        val good = successful[tuple] ?: return names
        return names.sortedBy { it != good }
    }
}
