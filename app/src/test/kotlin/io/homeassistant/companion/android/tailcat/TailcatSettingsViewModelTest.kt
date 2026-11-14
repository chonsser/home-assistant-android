package io.homeassistant.companion.android.tailcat

import androidx.lifecycle.SavedStateHandle
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.servers.ServerConnectionStateProvider
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.servers.UrlState
import io.homeassistant.companion.android.common.data.tailcat.TailcatConfig
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.testing.unit.MainDispatcherJUnit5Extension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.net.URL
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherJUnit5Extension::class)
class TailcatSettingsViewModelTest {
    private val config = TailcatConfig("tcexample", "privkey:01", 8123)
    private val server: Server = mockk()
    private val connectionStateProvider: ServerConnectionStateProvider = mockk()
    private val serverManager: ServerManager = mockk {
        coEvery { getServer(1) } returns server
        coEvery { connectionStateProvider(1) } returns connectionStateProvider
    }
    private val pairing: TailcatServerPairing = mockk {
        coEvery { isAvailable() } returns true
        coEvery { localUrl(config) } returns "http://127.0.0.1:40123".toHttpUrl()
        coEvery { attach(any(), any()) } just runs
        coEvery { remove(any()) } just runs
    }

    private fun viewModel(paired: Boolean, activeUrl: String?): TailcatSettingsViewModel {
        coEvery { pairing.config(server) } returns config.takeIf { paired }
        every { connectionStateProvider.urlFlow(any()) } returns flowOf(UrlState.HasUrl(activeUrl?.let(::URL)))
        return TailcatSettingsViewModel(SavedStateHandle(mapOf(TAILCAT_SETTINGS_EXTRA_SERVER to 1)), serverManager, pairing)
    }

    @Test
    fun `Given a paired server using the tunnel when opening then shows paired and the tunnel as active`() = runTest {
        val viewModel = viewModel(paired = true, activeUrl = "http://127.0.0.1:40123/")
        advanceUntilIdle()

        assertEquals(
            TailcatSettingsUiState(isAvailable = true, isPaired = true, activeConnection = TailcatActiveConnection.TAILCAT),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `Given an unpaired server using another URL when opening then shows not paired`() = runTest {
        val viewModel = viewModel(paired = false, activeUrl = "https://example.com/")
        advanceUntilIdle()

        assertEquals(TailcatActiveConnection.OTHER, viewModel.uiState.value.activeConnection)
        assertEquals(false, viewModel.uiState.value.isPaired)
    }

    @Test
    fun `Given an invalid pasted link when pairing then shows why and attaches nothing`() = runTest {
        val viewModel = viewModel(paired = false, activeUrl = null)
        viewModel.onLinkTextChange("homeassistant://tailcat/pair?address=tcexample&key=privkey%3A01")

        viewModel.onPairClick()
        advanceUntilIdle()

        assertEquals(commonR.string.tailcat_pairing_error_invalid_port, viewModel.uiState.value.messageRes)
        coVerify(exactly = 0) { pairing.attach(any(), any()) }
    }

    @Test
    fun `Given a valid pasted link when pairing then attaches it and clears the field`() = runTest {
        val viewModel = viewModel(paired = false, activeUrl = null)
        viewModel.onLinkTextChange(" homeassistant://tailcat/pair?address=tcexample&key=privkey%3A01&port=8123 ")

        viewModel.onPairClick()
        advanceUntilIdle()

        coVerify { pairing.attach(config, 1) }
        assertEquals("", viewModel.uiState.value.linkText)
        assertEquals(commonR.string.tailcat_pair_success, viewModel.uiState.value.messageRes)
    }

    @Test
    fun `Given a paired server when removal is confirmed then the pairing is removed`() = runTest {
        val viewModel = viewModel(paired = true, activeUrl = null)

        viewModel.onRemoveConfirmed()
        advanceUntilIdle()

        coVerify { pairing.remove(1) }
    }
}
