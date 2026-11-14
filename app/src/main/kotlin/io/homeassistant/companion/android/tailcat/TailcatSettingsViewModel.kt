package io.homeassistant.companion.android.tailcat

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.data.servers.UrlState
import io.homeassistant.companion.android.common.data.tailcat.TailcatPairingLink
import io.homeassistant.companion.android.common.data.tailcat.TailcatPairingLinkResult
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** Argument naming the server whose Tailcat pairing is shown. */
const val TAILCAT_SETTINGS_EXTRA_SERVER = "server"

/** Which kind of URL the app currently uses for the server. */
enum class TailcatActiveConnection {
    /** The Tailcat tunnel. */
    TAILCAT,

    /** One of the server's other URLs. */
    OTHER,

    /** No URL is usable right now. */
    NONE,
}

/**
 * State of the Tailcat section of a server's settings.
 *
 * @property isAvailable whether this build can run Tailcat tunnels
 * @property isPaired whether the server has a usable Tailcat pairing on this device
 * @property activeConnection which URL the app uses for the server right now
 * @property linkText the pairing link being typed or pasted
 * @property messageRes a message to show once (pairing result or error), if any
 */
data class TailcatSettingsUiState(
    val isAvailable: Boolean = true,
    val isPaired: Boolean = false,
    val activeConnection: TailcatActiveConnection = TailcatActiveConnection.NONE,
    val linkText: String = "",
    @StringRes val messageRes: Int? = null,
)

/** Backs the Tailcat section of a server's connection settings: status, pairing and unpairing. */
@HiltViewModel
class TailcatSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverManager: ServerManager,
    private val tailcatServerPairing: TailcatServerPairing,
) : ViewModel() {

    private val serverId: Int = checkNotNull(savedStateHandle[TAILCAT_SETTINGS_EXTRA_SERVER]) { "Missing server id" }

    private val _uiState = MutableStateFlow(TailcatSettingsUiState())
    val uiState: StateFlow<TailcatSettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun onLinkTextChange(text: String) {
        _uiState.update { it.copy(linkText = text) }
    }

    /** Pairs the server with the pairing link typed or pasted by the user. */
    fun onPairClick() {
        when (val result = TailcatPairingLink.parse(_uiState.value.linkText)) {
            is TailcatPairingLinkResult.Invalid -> {
                // Never log the link itself: it carries the tunnel's secrets.
                Timber.w("Rejected Tailcat pairing link: ${result.error}")
                _uiState.update { it.copy(messageRes = result.error.messageRes()) }
            }
            is TailcatPairingLinkResult.Valid -> viewModelScope.launch {
                tailcatServerPairing.attach(result.link.config, serverId)
                _uiState.update { it.copy(linkText = "", messageRes = commonR.string.tailcat_pair_success) }
                refresh()
            }
        }
    }

    /** Removes the server's pairing. */
    fun onRemoveConfirmed() {
        viewModelScope.launch {
            tailcatServerPairing.remove(serverId)
            refresh()
        }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(messageRes = null) }
    }

    private fun refresh() {
        viewModelScope.launch {
            val isAvailable = tailcatServerPairing.isAvailable()
            val server = serverManager.getServer(serverId) ?: return@launch
            val config = tailcatServerPairing.config(server)
            val activeUrl = (serverManager.connectionStateProvider(serverId).urlFlow().first() as? UrlState.HasUrl)?.url
            val tailcatUrl = config?.let { tailcatServerPairing.localUrl(it) }?.toUrl()
            val active = when {
                activeUrl == null -> TailcatActiveConnection.NONE
                activeUrl.toString() == tailcatUrl?.toString() -> TailcatActiveConnection.TAILCAT
                else -> TailcatActiveConnection.OTHER
            }
            _uiState.update { it.copy(isAvailable = isAvailable, isPaired = config != null, activeConnection = active) }
        }
    }
}
