package io.homeassistant.companion.android.tailcat

import androidx.annotation.VisibleForTesting
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.tailcat.TailcatConfig
import io.homeassistant.companion.android.common.data.tailcat.TailcatConfigCipher
import io.homeassistant.companion.android.database.server.Server
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import timber.log.Timber

/**
 * Attaches Tailcat pairings to servers and removes them. The configuration is stored encrypted with
 * the server (see [TailcatConfigCipher]); Keystore and tunnel work runs on [ioDispatcher].
 */
@Singleton
class TailcatServerPairing @VisibleForTesting constructor(
    private val serverManager: ServerManager,
    private val cipher: TailcatConfigCipher,
    private val tunnelManager: TailcatTunnelManager,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        serverManager: ServerManager,
        cipher: TailcatConfigCipher,
        tunnelManager: TailcatTunnelManager,
    ) : this(serverManager, cipher, tunnelManager, Dispatchers.IO)

    /** Whether this build can run Tailcat tunnels. Loads the native library on first use. */
    suspend fun isAvailable(): Boolean = withContext(ioDispatcher) { tunnelManager.isAvailable }

    /** The loopback URL serving [config]'s tunnel, starting it if needed; `null` when it cannot run. */
    suspend fun localUrl(config: TailcatConfig): HttpUrl? = withContext(ioDispatcher) { tunnelManager.localUrl(config) }

    /** The decrypted pairing of [server], or `null` when it has none (or it cannot be decrypted here). */
    suspend fun config(server: Server): TailcatConfig? = server.connection.tailcat?.let { encrypted ->
        withContext(ioDispatcher) { cipher.decrypt(encrypted) }
    }

    /** The registered server already paired with [config]'s tunnel endpoint, if any. */
    suspend fun serverPairedWith(config: TailcatConfig): Server? = serverManager.servers().firstOrNull { server ->
        config(server)?.endpointIdentity == config.endpointIdentity
    }

    /** Pairs [serverId] with [config], replacing any previous pairing of that server. */
    suspend fun attach(config: TailcatConfig, serverId: Int) {
        val server = serverManager.getServer(serverId) ?: return
        val previous = config(server)
        serverManager.updateServer(server.copy(connection = server.connection.copy(tailcat = encrypt(config))))
        Timber.i("Tailcat pairing attached to server $serverId")
        if (previous != null && previous.endpointIdentity != config.endpointIdentity) {
            stopTunnelIfUnused(previous)
        }
    }

    /**
     * Pairs the server [serverId], just onboarded through [config]'s tunnel, with it. The loopback URL
     * it was onboarded at only works while this process runs the tunnel, so it does not stay the
     * server's external URL.
     */
    suspend fun attachToOnboardedServer(config: TailcatConfig, serverId: Int) {
        val server = serverManager.getServer(serverId) ?: return
        serverManager.updateServer(
            server.copy(
                connection = server.connection.copy(
                    externalUrl = "",
                    tailcat = encrypt(config),
                    // The tunnel is encrypted end to end; there is no plain-text connection to allow.
                    allowInsecureConnection = false,
                ),
            ),
        )
        Timber.i("Tailcat pairing attached to new server $serverId")
    }

    /** Removes the Tailcat pairing of [serverId]. */
    suspend fun remove(serverId: Int) {
        val server = serverManager.getServer(serverId) ?: return
        val previous = config(server)
        serverManager.updateServer(server.copy(connection = server.connection.copy(tailcat = null)))
        Timber.i("Tailcat pairing removed from server $serverId")
        previous?.let { stopTunnelIfUnused(it) }
    }

    private suspend fun encrypt(config: TailcatConfig): String = withContext(ioDispatcher) { cipher.encrypt(config) }

    private suspend fun stopTunnelIfUnused(config: TailcatConfig) {
        if (serverPairedWith(config) == null) {
            withContext(ioDispatcher) { tunnelManager.stopTunnel(config) }
        }
    }
}
