package io.homeassistant.companion.android.tailcat

import io.homeassistant.companion.android.common.data.tailcat.TailcatConfig
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TailcatClientSessionTest {

    private val config = TailcatConfig("tcexample", "privkey:01", 8123)
    private val timeSource = TestTimeSource()
    private val clients = mutableListOf<FakeClient>()
    private val configsUsed = mutableListOf<TailcatConfig>()

    /** Results of the next dials across all clients, in order; a `null` entry succeeds. */
    private val dialResults = ArrayDeque<IOException?>()

    private inner class FakeClient : TailcatClient {
        var isClosed = false

        override fun dial(port: Int, timeout: Duration): TailcatStream {
            timeSource += 1.seconds
            dialResults.removeFirstOrNull()?.let { throw it }
            return FakeTailcatStream()
        }

        override fun close() {
            isClosed = true
        }
    }

    private fun session(failuresBeforeReset: Int = 2) = TailcatClientSession(
        config = config,
        closeExecutor = { it.run() },
        retryPolicy = TailcatRetryPolicy(
            attemptTimeout = 5.seconds,
            retryDelay = 1.seconds,
            failuresBeforeReset = failuresBeforeReset,
        ),
        timeSource = timeSource,
        sleep = { timeSource += it },
        makeClient = {
            configsUsed += it
            FakeClient().also(clients::add)
        },
    )

    @Test
    fun `Given a reachable server when dialing then one client is created lazily and reused`() {
        val session = session()
        assertTrue(clients.isEmpty())

        session.dial(8123, 10.seconds)
        session.dial(8123, 10.seconds)

        assertEquals(1, clients.size)
        assertFalse(clients[0].isClosed)
    }

    @Test
    fun `Given a fresh client failing its first dial when dialing then it is replaced and the retry succeeds`() {
        dialResults += IOException("not allowlisted yet")
        val session = session()

        session.dial(8123, 30.seconds)

        assertEquals(2, clients.size)
        assertTrue(clients[0].isClosed)
        assertFalse(clients[1].isClosed)
    }

    @Test
    fun `Given a client that already dialed when a single dial fails then it is kept`() {
        val session = session()
        session.dial(8123, 30.seconds)
        dialResults += IOException("transient")

        session.dial(8123, 30.seconds)

        assertEquals(1, clients.size)
        assertFalse(clients[0].isClosed)
    }

    @Test
    fun `Given a client that already dialed when dials keep failing then it is replaced`() {
        val session = session(failuresBeforeReset = 2)
        session.dial(8123, 30.seconds)
        dialResults += IOException("network changed")
        dialResults += IOException("network changed")

        session.dial(8123, 30.seconds)

        assertEquals(2, clients.size)
        assertTrue(clients[0].isClosed)
    }

    @Test
    fun `Given a server that never answers when dialing then gives up after the timeout with the last error`() {
        val lastError = IOException("unreachable")
        repeat(100) { dialResults += lastError }
        val session = session()
        val start = timeSource.markNow()

        val thrown = assertThrows(IOException::class.java) { session.dial(8123, 10.seconds) }

        assertSame(lastError, thrown)
        assertTrue(start.elapsedNow() <= 11.seconds)
    }

    @Test
    fun `Given a new key for the same endpoint when updating then the next dial uses a fresh client with it`() {
        val session = session()
        session.dial(8123, 10.seconds)
        val rekeyed = config.copy(clientPrivateKey = "privkey:02")

        session.update(rekeyed)
        session.dial(8123, 10.seconds)

        assertTrue(clients[0].isClosed)
        assertEquals(listOf(config, rekeyed), configsUsed)
    }
}
