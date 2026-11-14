package io.homeassistant.companion.android.tailcat

import io.homeassistant.companion.android.common.data.tailcat.TailcatConfig
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TailcatTunnelManagerTest {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val config = TailcatConfig("tcmanager${System.nanoTime()}", "privkey:01", 8123)
    private val sessionsCreated = mutableListOf<TailcatConfig>()
    private var libraryAvailable = true

    private val manager = TailcatTunnelManager(
        isLibraryAvailable = { libraryAvailable },
        executor = executor,
        dialTimeout = 5.seconds,
        makeSession = { config, executor ->
            sessionsCreated += config
            TailcatClientSession(config, executor, makeClient = { NeverUsedClient })
        },
    )

    private object NeverUsedClient : TailcatClient {
        override fun dial(port: Int, timeout: Duration): TailcatStream = error("Not dialed in these tests")

        override fun close() = Unit
    }

    @AfterEach
    fun tearDown() {
        manager.stopTunnel(config)
        manager.stopTunnel(config.copy(tunnelPort = 8124))
        executor.shutdownNow()
    }

    @Test
    fun `Given the native library is missing when resolving a tunnel URL then there is none`() {
        libraryAvailable = false

        assertNull(manager.localUrl(config))
        assertEquals(emptyList<TailcatConfig>(), sessionsCreated)
    }

    @Test
    fun `Given an endpoint when resolving its URL repeatedly, even with a new key, then one tunnel serves the same URL`() {
        val first = manager.localUrl(config)
        val again = manager.localUrl(config.copy(clientPrivateKey = "privkey:02"))

        assertEquals(first, again)
        assertEquals("127.0.0.1", first?.host)
        assertEquals(TailcatLocalPort.preferred(config), first?.port)
        assertEquals(1, sessionsCreated.size)
    }

    @Test
    fun `Given two endpoints when resolving their URLs then each gets its own tunnel`() {
        assertNotEquals(manager.localUrl(config), manager.localUrl(config.copy(tunnelPort = 8124)))
        assertEquals(2, sessionsCreated.size)
    }

    @Test
    fun `Given a running tunnel when it is stopped then its port is released and a new tunnel reuses it`() {
        val url = checkNotNull(manager.localUrl(config))

        manager.stopTunnel(config)
        ServerSocket(url.port, 1, InetAddress.getByName("127.0.0.1")).close()

        assertEquals(url, manager.localUrl(config))
        assertEquals(2, sessionsCreated.size)
    }
}
