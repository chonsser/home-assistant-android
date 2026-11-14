package io.homeassistant.companion.android.common.data.tailcat

import androidx.annotation.VisibleForTesting
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/**
 * Resolves the Tailcat tunnel URL of a server: its stored configuration decrypted, served by the
 * process' [TailcatTunnelResolver] if there is one.
 *
 * Decrypting uses the Android Keystore and the first resolution loads the native library and binds
 * the loopback port, so both run on [ioDispatcher].
 */
@Singleton
class TailcatUrlProvider @VisibleForTesting constructor(
    private val cipher: TailcatConfigCipher,
    private val resolver: Optional<TailcatTunnelResolver>,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        cipher: TailcatConfigCipher,
        resolver: Optional<TailcatTunnelResolver>,
    ) : this(cipher, resolver, Dispatchers.IO)

    /** The decrypted Tailcat configuration of [connection], or `null` without a usable one. */
    suspend fun config(connection: ServerConnectionInfo): TailcatConfig? = connection.tailcat?.let { encrypted ->
        withContext(ioDispatcher) { cipher.decrypt(encrypted) }
    }

    /**
     * The loopback URL of [connection]'s Tailcat tunnel in this process, or `null` when the server
     * has no usable Tailcat configuration or this process cannot run a tunnel.
     */
    suspend fun localUrl(connection: ServerConnectionInfo): HttpUrl? {
        val tunnelResolver = resolver.orElse(null) ?: return null
        return config(connection)?.let { config -> withContext(ioDispatcher) { tunnelResolver.localUrl(config) } }
    }
}
