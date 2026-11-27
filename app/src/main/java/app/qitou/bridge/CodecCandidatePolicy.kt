package app.qitou.bridge

import java.util.Locale

/**
 * Deterministic tie-breaking for Android MediaCodec candidates.
 *
 * Hardware candidates always stay ahead of software. Within hardware candidates, Codec2 is
 * preferred over legacy OMX, secure-only components are deferred, and low-latency variants are
 * tried after the normal component so a lightweight fallback remains available.
 */
internal object CodecCandidatePolicy {
    fun rank(name: String, hardware: Boolean): Int {
        val lower = name.lowercase(Locale.ROOT)
        var score = if (hardware) 0 else 10_000
        score += when {
            lower.startsWith("c2.") -> 0
            lower.startsWith("omx.") -> 100
            else -> 200
        }
        if (isSecure(lower)) score += 1_000
        if (isLowLatency(lower)) score += 10
        return score
    }

    fun isSecure(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.contains(".secure") || lower.contains("secure.")
    }

    fun displayKind(hardware: Boolean): String = if (hardware) "硬件" else "软件"

    private fun isLowLatency(lowerName: String): Boolean =
        lowerName.contains("low_latency") || lowerName.contains(".low-latency")
}
