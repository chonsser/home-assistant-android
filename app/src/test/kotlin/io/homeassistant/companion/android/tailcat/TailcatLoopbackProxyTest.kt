package io.homeassistant.companion.android.tailcat

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/** Runs the proxy on real loopback sockets with an in-memory tunnel. */
@Timeout(10, unit = TimeUnit.SECONDS)
class TailcatLoopbackProxyTest {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val dialer = FakeTailcatDialer()
    private val proxies = mutableListOf<TailcatLoopbackProxy>()

    @AfterEach
    fun tearDown() {
        proxies.forEach(TailcatLoopbackProxy::close)
        executor.shutdownNow()
    }

    private fun proxy(preferredPort: Int = TailcatLocalPort.preferred("test-${System.nanoTime()}")) = TailcatLoopbackProxy(preferredPort, tunnelPort = 8123, dialer = dialer, dialTimeout = 5.seconds, executor = executor)
        .also(proxies::add)

    private fun connect(proxy: TailcatLoopbackProxy) = Socket(InetAddress.getByName("127.0.0.1"), proxy.port)

    private fun awaitStream(): FakeTailcatStream {
        while (dialer.streams.isEmpty()) Thread.onSpinWait()
        return dialer.streams.first()
    }

    @Test
    fun `Given a proxy when it starts then it serves a loopback HTTP URL on its port`() {
        val proxy = proxy()

        assertEquals("http://127.0.0.1:${proxy.port}/", proxy.localUrl.toString())
    }

    @Test
    fun `Given a loopback connection when bytes flow both ways then they are piped through the tunnel port`() {
        val proxy = proxy()
        connect(proxy).use { client ->
            client.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
            client.shutdownOutput()
            val stream = awaitStream()

            assertEquals("GET / HTTP/1.1\r\n\r\n", stream.toServer.readAll().decodeToString())

            stream.toProxy.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
            stream.toProxy.close()
            assertEquals("HTTP/1.1 200 OK\r\n\r\n", client.getInputStream().readBytes().decodeToString())
        }
        assertEquals(listOf(8123), dialer.dialedPorts)
    }

    @Test
    fun `Given the tunnel cannot be dialed when a client connects then its connection is closed`() {
        dialer.failure = IOException("unreachable")
        val proxy = proxy()

        connect(proxy).use { client ->
            assertEquals(-1, client.getInputStream().read())
        }
        assertEquals(0, proxy.activeConnectionCount)
    }

    @Test
    fun `Given the preferred port is taken when starting then the next free port is used`() {
        val preferred = TailcatLocalPort.preferred("taken-${System.nanoTime()}")
        ServerSocket(preferred, 1, InetAddress.getByName("127.0.0.1")).use {
            val proxy = proxy(preferredPort = preferred)

            assertNotEquals(preferred, proxy.port)
            assertTrue(proxy.port in TailcatLocalPort.range)
        }
    }

    @Test
    fun `Given a piped connection when the proxy closes then the tunnel stream is closed and the port is released`() {
        val proxy = proxy()
        val client = connect(proxy)
        val stream = awaitStream()

        proxy.close()

        assertEquals(-1, client.getInputStream().read())
        assertTrue(stream.isClosed)
        ServerSocket(proxy.port, 1, InetAddress.getByName("127.0.0.1")).close()
        client.close()
    }
}
