package io.homeassistant.companion.android.tailcat

import io.homeassistant.companion.android.common.data.tailcat.TailcatConfig
import io.homeassistant.companion.android.common.data.tailcat.TailcatTunnelResolver
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import okhttp3.HttpUrl
import timber.log.Timber

/**
 * How long one loopback connection waits for its tunnel stream, retries included. Covers the
 * couple of seconds Home Assistant needs to allowlist a freshly paired device.
 */
private val DIAL_TIMEOUT = 30.seconds

/**
 * Runs the app's Tailcat tunnels: one [TailcatLoopbackProxy] per tunnel endpoint (server address and
 * port), created the first time a server's URL is resolved and kept for the life of the process.
 *
 * [localUrl] is synchronous so URL resolution stays cheap: the proxy binds its port right away and
 * only the tunnel client, which needs the network, is created on the first connection.
 *
 * Only the app process runs tunnels. Widgets, tiles and background workers run in this process too,
 * so they use the tunnel while the process lives; Wear OS has no tunnel at all.
 */
@Singleton
class TailcatTunnelManager internal constructor(
    private val isLibraryAvailable: () -> Boolean,
    private val executor: ExecutorService,
    private val dialTimeout: Duration,
    private val makeSession: (TailcatConfig, ExecutorService) -> TailcatClientSession,
) : TailcatTunnelResolver {

    @Inject
    constructor() : this(
        isLibraryAvailable = { LibTailcat.isAvailable },
        executor = Executors.newCachedThreadPool(TailcatThreadFactory()),
        dialTimeout = DIAL_TIMEOUT,
        makeSession = { config, executor -> TailcatClientSession(config, executor) },
    )

    private class Tunnel(val proxy: TailcatLoopbackProxy, val session: TailcatClientSession)

    private val tunnels = mutableMapOf<String, Tunnel>()

    /** Whether this build can run Tailcat tunnels (the native library is packaged). */
    val isAvailable: Boolean
        get() = isLibraryAvailable()

    override fun localUrl(config: TailcatConfig): HttpUrl? = if (!isAvailable) {
        null
    } else {
        synchronized(tunnels) {
            val existing = tunnels[config.endpointIdentity]
            if (existing != null) {
                existing.session.update(config)
                existing.proxy.localUrl
            } else {
                startTunnel(config)
            }
        }
    }

    /** Starts a tunnel for [config]; called with the lock on [tunnels] held. */
    private fun startTunnel(config: TailcatConfig): HttpUrl? {
        val session = makeSession(config, executor)
        return try {
            val proxy = TailcatLoopbackProxy(
                preferredPort = TailcatLocalPort.preferred(config),
                tunnelPort = config.tunnelPort,
                dialer = session,
                dialTimeout = dialTimeout,
                executor = executor,
            )
            tunnels[config.endpointIdentity] = Tunnel(proxy, session)
            Timber.i("Serving a Tailcat tunnel on loopback port ${proxy.port}")
            proxy.localUrl
        } catch (e: IOException) {
            Timber.e(e, "Failed to start the Tailcat loopback proxy")
            null
        }
    }

    /** Stops the tunnel of [config]'s endpoint, for example after its pairing was removed. */
    fun stopTunnel(config: TailcatConfig) {
        val tunnel = synchronized(tunnels) { tunnels.remove(config.endpointIdentity) } ?: return
        tunnel.proxy.close()
        tunnel.session.reset()
    }
}

/** Names the tunnel threads and keeps them from holding the process alive. */
private class TailcatThreadFactory : ThreadFactory {
    private val count = AtomicInteger()

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "tailcat-${count.incrementAndGet()}").apply { isDaemon = true }
}
