package io.homeassistant.companion.android.tailcat

import io.homeassistant.companion.android.common.data.tailcat.TailcatConfig

private const val FNV_OFFSET_BASIS = 0xCBF29CE484222325UL
private const val FNV_PRIME = 0x00000100000001B3UL

/**
 * The loopback port a Tailcat tunnel is served on. The WebView keys an origin's storage (the
 * frontend's login state, local storage) by `host:port`, so the port has to come out the same on
 * every launch: it is derived from the tunnel endpoint with a stable hash (FNV-1a, 64 bit), the
 * same way as in the iOS app.
 */
internal object TailcatLocalPort {
    val range: IntRange = 20000..59999

    /** The port to try first for [config]. */
    fun preferred(config: TailcatConfig): Int = preferred(config.endpointIdentity)

    /** The port to try first for an endpoint identity. */
    fun preferred(identity: String): Int {
        val span = (range.last - range.first + 1).toULong()
        return range.first + (fnv1a64(identity) % span).toInt()
    }

    /**
     * The ports to try, in order, when [preferred] is taken: walking forward and wrapping within
     * [range], so the fallback is as stable as it can be.
     */
    fun candidates(preferred: Int, count: Int): List<Int> {
        val span = range.last - range.first + 1
        val start = preferred.coerceIn(range) - range.first
        return (0 until count.coerceIn(1, span)).map { offset -> range.first + (start + offset) % span }
    }

    fun fnv1a64(value: String): ULong {
        var hash = FNV_OFFSET_BASIS
        for (byte in value.encodeToByteArray()) {
            hash = hash xor byte.toUByte().toULong()
            hash *= FNV_PRIME
        }
        return hash
    }
}
