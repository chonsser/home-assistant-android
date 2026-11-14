package io.homeassistant.companion.android.launch.link

import android.net.Uri
import app.cash.turbine.test
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.tailcat.TailcatConfig
import io.homeassistant.companion.android.common.data.tailcat.TailcatPairingLink
import io.homeassistant.companion.android.common.data.tailcat.TailcatPairingLinkError
import io.homeassistant.companion.android.common.util.FailFast
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.frontend.navigation.FrontendTarget
import io.homeassistant.companion.android.settings.server.ServerChooserItem
import io.homeassistant.companion.android.settings.server.ServerChooserItemsUseCase
import io.homeassistant.companion.android.tailcat.TailcatOnboarding
import io.homeassistant.companion.android.tailcat.TailcatServerPairing
import io.homeassistant.companion.android.testing.unit.MainDispatcherJUnit5Extension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherJUnit5Extension::class)
class LinkViewModelTest {

    private val linkHandler: LinkHandler = mockk()
    private val serverChooserItems: ServerChooserItemsUseCase = mockk()

    private val serverManager: ServerManager = mockk()
    private val tailcatServerPairing: TailcatServerPairing = mockk(relaxUnitFun = true) {
        coEvery { isAvailable() } returns true
        coEvery { serverPairedWith(any()) } returns null
    }

    private val tailcatOnboarding: TailcatOnboarding = mockk()

    private fun createViewModel(): LinkViewModel = LinkViewModel(
        linkHandler = linkHandler,
        serverChooserItemsUseCase = serverChooserItems,
        serverManager = serverManager,
        tailcatServerPairing = tailcatServerPairing,
        tailcatOnboarding = tailcatOnboarding,
    )

    private val tailcatLink = TailcatPairingLink(TailcatConfig("tcexample", "privkey:01", 8123), name = "Home")

    private fun server(id: Int, name: String = "Home $id"): Server = mockk {
        every { this@mockk.id } returns id
        every { friendlyName } returns name
    }

    private fun uri(value: String = "https://my.home-assistant.io/redirect/foo"): Uri = mockk(relaxed = true) {
        every { this@mockk.toString() } returns value
    }

    @Test
    fun `Given ServerPicker destination when onLinkReceived then uiState becomes ChoosingServer`() = runTest {
        val servers = listOf<Server>(mockk { every { id } returns 1 }, mockk { every { id } returns 2 })
        val items = listOf(
            ServerChooserItem(serverId = 1, userName = "Alice", serverName = "Home"),
            ServerChooserItem(serverId = 2, userName = "Bob", serverName = "Office"),
        )
        coEvery { linkHandler.handleLink(any()) } returns LinkDestination.ServerPicker(FrontendTarget.Path("/lovelace"), servers)
        every { serverChooserItems(servers) } returns flowOf(items)

        val viewModel = createViewModel()
        viewModel.uiState.test {
            assertEquals(LinkUiState.Loading, awaitItem())
            viewModel.onLinkReceived(uri())
            assertEquals(LinkUiState.ChoosingServer(items = items, target = FrontendTarget.Path("/lovelace")), awaitItem())
        }
    }

    @Test
    fun `Given Webview destination when onLinkReceived then NavigateToWebView event is sent`() = runTest {
        coEvery { linkHandler.handleLink(any()) } returns LinkDestination.Webview(FrontendTarget.Path("/lovelace"), serverId = 3)

        val viewModel = createViewModel()
        viewModel.navigationEvents.test {
            viewModel.onLinkReceived(uri())
            assertEquals(LinkNavigationEvent.NavigateToWebView(target = FrontendTarget.Path("/lovelace"), serverId = 3), awaitItem())
        }
    }

    @Test
    fun `Given Onboarding destination when onLinkReceived then OpenInvitation event is sent`() = runTest {
        coEvery { linkHandler.handleLink(any()) } returns LinkDestination.Onboarding("http://homeassistant.local:8123")

        val viewModel = createViewModel()
        viewModel.navigationEvents.test {
            viewModel.onLinkReceived(uri())
            assertEquals(LinkNavigationEvent.OpenInvitation("http://homeassistant.local:8123"), awaitItem())
        }
    }

    @Test
    fun `Given NoDestination when onLinkReceived then Finish event is sent`() = runTest {
        coEvery { linkHandler.handleLink(any()) } returns LinkDestination.NoDestination

        val viewModel = createViewModel()
        viewModel.navigationEvents.test {
            viewModel.onLinkReceived(uri())
            assertEquals(LinkNavigationEvent.Finish, awaitItem())
        }
    }

    @Test
    fun `Given a null uri when onLinkReceived then FailFast triggers and a Finish event is sent`() = runTest {
        var failFastTriggered = false
        FailFast.setHandler { _, _ -> failFastTriggered = true }

        val viewModel = createViewModel()
        viewModel.navigationEvents.test {
            viewModel.onLinkReceived(null)
            assertEquals(LinkNavigationEvent.Finish, awaitItem())
        }
        assertTrue(failFastTriggered, "FailFast should be triggered for a missing uri")
    }

    @Test
    fun `Given ChoosingServer state when onServerSelected then NavigateToWebView event carries the path and id`() = runTest {
        val servers = listOf<Server>(mockk { every { id } returns 1 }, mockk { every { id } returns 2 })
        coEvery { linkHandler.handleLink(any()) } returns LinkDestination.ServerPicker(FrontendTarget.Path("/lovelace"), servers)
        every { serverChooserItems(servers) } returns flowOf(
            listOf(
                ServerChooserItem(serverId = 1, userName = "Alice", serverName = "Home"),
                ServerChooserItem(serverId = 2, userName = "Bob", serverName = "Office"),
            ),
        )

        val viewModel = createViewModel()
        viewModel.onLinkReceived(uri())
        advanceUntilIdle()

        viewModel.navigationEvents.test {
            viewModel.onServerSelected(serverId = 2)
            assertEquals(LinkNavigationEvent.NavigateToWebView(target = FrontendTarget.Path("/lovelace"), serverId = 2), awaitItem())
        }
    }

    @Test
    fun `Given no ChoosingServer state when onServerSelected then no event is sent`() = runTest {
        val viewModel = createViewModel()
        viewModel.navigationEvents.test {
            viewModel.onServerSelected(serverId = 2)
            expectNoEvents()
        }
    }

    @Test
    fun `When onServerChooserDismissed then Finish event is sent`() = runTest {
        val viewModel = createViewModel()
        viewModel.navigationEvents.test {
            viewModel.onServerChooserDismissed()
            assertEquals(LinkNavigationEvent.Finish, awaitItem())
        }
    }

    @Test
    fun `Given a Tailcat pairing and no server when onLinkReceived then onboards through the tunnel`() = runTest {
        coEvery { linkHandler.handleLink(any()) } returns LinkDestination.TailcatPairing(tailcatLink)
        coEvery { serverManager.servers() } returns emptyList()
        coEvery { tailcatOnboarding.start(tailcatLink.config) } returns "http://127.0.0.1:40123".toHttpUrl()

        val viewModel = createViewModel()
        viewModel.navigationEvents.test {
            viewModel.onLinkReceived(uri())
            assertEquals(LinkNavigationEvent.OpenTailcatOnboarding("http://127.0.0.1:40123/"), awaitItem())
        }
    }

    @Test
    fun `Given a Tailcat pairing whose tunnel cannot start when onLinkReceived then shows an error`() = runTest {
        coEvery { linkHandler.handleLink(any()) } returns LinkDestination.TailcatPairing(tailcatLink)
        coEvery { serverManager.servers() } returns emptyList()
        coEvery { tailcatOnboarding.start(tailcatLink.config) } returns null

        val viewModel = createViewModel()
        viewModel.navigationEvents.test {
            viewModel.onLinkReceived(uri())
            assertEquals(LinkNavigationEvent.ShowError(commonR.string.tailcat_tunnel_unavailable), awaitItem())
        }
    }

    @Test
    fun `Given a build without Tailcat when a pairing arrives then shows that it is unavailable`() = runTest {
        coEvery { linkHandler.handleLink(any()) } returns LinkDestination.TailcatPairing(tailcatLink)
        coEvery { tailcatServerPairing.isAvailable() } returns false

        val viewModel = createViewModel()
        viewModel.navigationEvents.test {
            viewModel.onLinkReceived(uri())
            assertEquals(LinkNavigationEvent.ShowError(commonR.string.tailcat_unavailable), awaitItem())
        }
    }

    @Test
    fun `Given a server already paired with the tunnel when the pairing arrives again then its key is refreshed and it opens`() = runTest {
        val existing = server(4, "Cabin")
        coEvery { linkHandler.handleLink(any()) } returns LinkDestination.TailcatPairing(tailcatLink)
        coEvery { tailcatServerPairing.serverPairedWith(tailcatLink.config) } returns existing
        coEvery { serverManager.getServer(4) } returns existing

        val viewModel = createViewModel()
        viewModel.navigationEvents.test {
            viewModel.onLinkReceived(uri())
            assertEquals(LinkNavigationEvent.TailcatPaired(serverName = "Cabin", serverId = 4), awaitItem())
        }
        coVerify { tailcatServerPairing.attach(tailcatLink.config, 4) }
    }

    @Test
    fun `Given a Tailcat pairing and registered servers when the user picks one then the pairing is attached to it`() = runTest {
        val servers = listOf(server(1), server(2))
        val items = listOf(
            ServerChooserItem(serverId = 1, userName = "Alice", serverName = "Home 1"),
            ServerChooserItem(serverId = 2, userName = "Alice", serverName = "Home 2"),
        )
        coEvery { linkHandler.handleLink(any()) } returns LinkDestination.TailcatPairing(tailcatLink)
        coEvery { serverManager.servers() } returns servers
        coEvery { serverManager.getServer(2) } returns servers[1]
        every { serverChooserItems(servers) } returns flowOf(items)

        val viewModel = createViewModel()
        viewModel.onLinkReceived(uri())
        advanceUntilIdle()
        assertEquals(LinkUiState.ChoosingTailcatServer(items), viewModel.uiState.value)

        viewModel.navigationEvents.test {
            viewModel.onTailcatServerSelected(2)
            assertEquals(LinkNavigationEvent.TailcatPaired(serverName = "Home 2", serverId = 2), awaitItem())
        }
        coVerify { tailcatServerPairing.attach(tailcatLink.config, 2) }
    }

    @Test
    fun `Given a Tailcat pairing and registered servers when the user adds a new server then onboards through the tunnel`() = runTest {
        val servers = listOf(server(1))
        coEvery { linkHandler.handleLink(any()) } returns LinkDestination.TailcatPairing(tailcatLink)
        coEvery { serverManager.servers() } returns servers
        every { serverChooserItems(servers) } returns flowOf(emptyList())
        coEvery { tailcatOnboarding.start(tailcatLink.config) } returns "http://127.0.0.1:40123".toHttpUrl()

        val viewModel = createViewModel()
        viewModel.onLinkReceived(uri())
        advanceUntilIdle()

        viewModel.navigationEvents.test {
            viewModel.onTailcatNewServerSelected()
            assertEquals(LinkNavigationEvent.OpenTailcatOnboarding("http://127.0.0.1:40123/"), awaitItem())
        }
    }

    @Test
    fun `Given an invalid Tailcat pairing when onLinkReceived then shows why`() = runTest {
        coEvery { linkHandler.handleLink(any()) } returns LinkDestination.InvalidTailcatPairing(TailcatPairingLinkError.INVALID_KEY)

        val viewModel = createViewModel()
        viewModel.navigationEvents.test {
            viewModel.onLinkReceived(uri())
            assertEquals(LinkNavigationEvent.ShowError(commonR.string.tailcat_pairing_error_invalid_key), awaitItem())
        }
    }
}
