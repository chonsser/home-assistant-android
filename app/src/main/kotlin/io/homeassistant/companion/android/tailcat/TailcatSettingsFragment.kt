package io.homeassistant.companion.android.tailcat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.compose.theme.HATheme

/**
 * Hosts [TailcatSettingsScreen] in the fragment-based server settings. Pass the server id as
 * [TAILCAT_SETTINGS_EXTRA_SERVER] in the arguments.
 */
@AndroidEntryPoint
class TailcatSettingsFragment : Fragment() {

    private val viewModel by viewModels<TailcatSettingsViewModel>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setContent {
                HATheme {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(state.messageRes) {
                        state.messageRes?.let { message ->
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                            viewModel.onMessageShown()
                        }
                    }
                    Surface {
                        TailcatSettingsScreen(
                            state = state,
                            onLinkTextChange = viewModel::onLinkTextChange,
                            onPairClick = viewModel::onPairClick,
                            onRemoveConfirmed = viewModel::onRemoveConfirmed,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activity?.title = getString(commonR.string.tailcat_settings_title)
    }
}
