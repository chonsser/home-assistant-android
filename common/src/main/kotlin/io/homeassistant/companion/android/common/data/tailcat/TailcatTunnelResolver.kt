package io.homeassistant.companion.android.common.data.tailcat

import okhttp3.HttpUrl

/**
 * Serves Tailcat tunnels on loopback URLs. Only the phone app can run tunnels (it embeds
 * libtailcat), so this is an optional binding: where it is absent (Wear OS) servers simply have no
 * tunnel URL.
 */
fun interface TailcatTunnelResolver {
    /**
     * The loopback URL (`http://127.0.0.1:<port>`) serving [config]'s tunnel, starting the tunnel if
     * needed. Binding the loopback port is quick; the tunnel itself connects lazily on first use.
     *
     * @return the URL, or `null` when no tunnel can run (library missing, no free port)
     */
    fun localUrl(config: TailcatConfig): HttpUrl?
}
