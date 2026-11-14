package io.homeassistant.companion.android.tailcat

import io.homeassistant.companion.android.common.data.tailcat.TailcatConfig
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Carries a Tailcat pairing through the onboarding of a new server.
 *
 * Onboarding signs in through the tunnel's loopback URL, which the onboarding screens pass along
 * like any server URL. The pairing itself is kept here, in memory only (it holds secrets, so it never
 * goes into navigation arguments or saved state), until the new server exists and [complete]
 * attaches it. If the process dies in between, the user opens the pairing link again.
 */
@Singleton
class TailcatOnboarding @Inject constructor(private val serverPairing: TailcatServerPairing) {

    private var pending: Pair<HttpUrl, TailcatConfig>? = null

    /**
     * Starts the tunnel of [config] for onboarding a new server through it.
     *
     * @return the loopback URL to onboard, or `null` when the tunnel cannot run on this device
     */
    suspend fun start(config: TailcatConfig): HttpUrl? = serverPairing.localUrl(config)?.also { url ->
        synchronized(this) { pending = url to config }
    }

    /** Whether [url] is the loopback URL of a pairing being onboarded. */
    fun isOnboardingUrl(url: String): Boolean = pendingConfigFor(url) != null

    /**
     * Attaches the pairing being onboarded through [url] to the new server [serverId].
     *
     * @return whether [url] belonged to a pairing being onboarded
     */
    suspend fun complete(url: String, serverId: Int): Boolean {
        val config = pendingConfigFor(url)
        if (config != null) {
            serverPairing.attachToOnboardedServer(config, serverId)
            synchronized(this) { pending = null }
        }
        return config != null
    }

    private fun pendingConfigFor(url: String): TailcatConfig? {
        val parsed = url.toHttpUrlOrNull()
        return synchronized(this) {
            pending?.takeIf { (pendingUrl, _) ->
                parsed != null &&
                    parsed.scheme == pendingUrl.scheme &&
                    parsed.host == pendingUrl.host &&
                    parsed.port == pendingUrl.port
            }?.second
        }
    }
}
