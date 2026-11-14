package io.homeassistant.companion.android.tailcat

import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.tailcat.TailcatConfig
import io.homeassistant.companion.android.common.data.tailcat.TailcatConfigCipher
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import io.homeassistant.companion.android.database.server.ServerSessionInfo
import io.homeassistant.companion.android.database.server.ServerUserInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TailcatServerPairingTest {

    /** Stores configurations in memory: "encrypted" values are opaque ids. */
    private class FakeCipher : TailcatConfigCipher {
        private val stored = mutableMapOf<String, TailcatConfig>()

        override fun encrypt(config: TailcatConfig): String = "enc-${stored.size}".also { stored[it] = config }

        override fun decrypt(encrypted: String): TailcatConfig? = stored[encrypted]
    }

    private val cipher = FakeCipher()
    private val servers = mutableMapOf<Int, Server>()
    private val serverManager: ServerManager = mockk {
        coEvery { getServer(any<Int>()) } answers { servers[firstArg()] }
        coEvery { servers() } answers { servers.values.toList() }
        coEvery { updateServer(any()) } answers { firstArg<Server>().let { servers[it.id] = it } }
    }
    private val tunnelManager: TailcatTunnelManager = mockk {
        every { stopTunnel(any()) } just runs
    }
    private val pairing = TailcatServerPairing(serverManager, cipher, tunnelManager)

    private val config = TailcatConfig("tcexample", "privkey:01", 8123)

    private fun addServer(id: Int, externalUrl: String = "http://192.168.1.2:8123", tailcat: TailcatConfig? = null) {
        servers[id] = Server(
            id = id,
            _name = "Home $id",
            connection = ServerConnectionInfo(externalUrl = externalUrl, tailcat = tailcat?.let(cipher::encrypt)),
            session = ServerSessionInfo(),
            user = ServerUserInfo(),
        )
    }

    @Test
    fun `Given a server when attaching a pairing then it is stored encrypted and found by endpoint`() = runTest {
        addServer(1)

        pairing.attach(config, serverId = 1)

        val stored = checkNotNull(servers[1]).connection.tailcat
        assertFalse(stored.orEmpty().contains(config.clientPrivateKey))
        assertEquals(config, pairing.config(checkNotNull(servers[1])))
        assertEquals(1, pairing.serverPairedWith(config.copy(clientPrivateKey = "privkey:02"))?.id)
        assertEquals("http://192.168.1.2:8123", servers[1]?.connection?.externalUrl)
    }

    @Test
    fun `Given a paired server when pairing it with another endpoint then the unused old tunnel stops`() = runTest {
        addServer(1, tailcat = config)
        val other = config.copy(address = "tcother")

        pairing.attach(other, serverId = 1)

        verify { tunnelManager.stopTunnel(config) }
        assertEquals(other, pairing.config(checkNotNull(servers[1])))
    }

    @Test
    fun `Given a paired server when removing the pairing then it is cleared and its tunnel stops`() = runTest {
        addServer(1, tailcat = config)

        pairing.remove(serverId = 1)

        assertNull(servers[1]?.connection?.tailcat)
        verify { tunnelManager.stopTunnel(config) }
    }

    @Test
    fun `Given two servers on one endpoint when removing one pairing then the tunnel keeps running`() = runTest {
        addServer(1, tailcat = config)
        addServer(2, tailcat = config)

        pairing.remove(serverId = 1)

        verify(exactly = 0) { tunnelManager.stopTunnel(any()) }
    }

    @Test
    fun `Given a server onboarded through a tunnel when attaching it then it keeps the pairing, not the loopback URL`() = runTest {
        addServer(3, externalUrl = "http://127.0.0.1:40123")

        pairing.attachToOnboardedServer(config, serverId = 3)

        val connection = checkNotNull(servers[3]).connection
        assertEquals("", connection.externalUrl)
        assertEquals(false, connection.allowInsecureConnection)
        assertEquals(config, pairing.config(checkNotNull(servers[3])))
    }
}
