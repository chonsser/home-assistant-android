package io.homeassistant.companion.android.tailcat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.compose.composable.ButtonVariant
import io.homeassistant.companion.android.common.compose.composable.HAAccentButton
import io.homeassistant.companion.android.common.compose.composable.HAPlainButton
import io.homeassistant.companion.android.common.compose.composable.HASettingsCard
import io.homeassistant.companion.android.common.compose.composable.HATextField
import io.homeassistant.companion.android.common.compose.theme.HADimens
import io.homeassistant.companion.android.common.compose.theme.HATextStyle
import io.homeassistant.companion.android.common.compose.theme.HAThemeForPreview

/**
 * The Tailcat section of a server's connection settings: whether the server is paired, which URL
 * the app uses right now, pairing with a pasted link and removing the pairing.
 */
@Composable
fun TailcatSettingsScreen(
    state: TailcatSettingsUiState,
    onLinkTextChange: (String) -> Unit,
    onPairClick: () -> Unit,
    onRemoveConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRemoveConfirmation by rememberSaveable { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(HADimens.SPACE4),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(HADimens.SPACE4),
    ) {
        Text(
            text = stringResource(commonR.string.tailcat_settings_description),
            style = HATextStyle.Body.copy(textAlign = TextAlign.Start),
        )

        if (!state.isAvailable) {
            Text(
                text = stringResource(commonR.string.tailcat_unavailable),
                style = HATextStyle.Body.copy(textAlign = TextAlign.Start),
            )
            return@Column
        }

        TailcatStatusCard(state)
        TailcatPairSection(
            state = state,
            onLinkTextChange = onLinkTextChange,
            onPairClick = onPairClick,
            onRemoveClick = { showRemoveConfirmation = true },
        )
    }

    if (showRemoveConfirmation) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirmation = false },
            title = { Text(stringResource(commonR.string.tailcat_remove_title)) },
            text = { Text(stringResource(commonR.string.tailcat_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveConfirmation = false
                    onRemoveConfirmed()
                }) { Text(stringResource(commonR.string.tailcat_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirmation = false }) { Text(stringResource(commonR.string.cancel)) }
            },
        )
    }
}

@Composable
private fun TailcatStatusCard(state: TailcatSettingsUiState) {
    HASettingsCard {
        Column(
            modifier = Modifier.padding(HADimens.SPACE4),
            verticalArrangement = Arrangement.spacedBy(HADimens.SPACE2),
        ) {
            StatusRow(
                title = stringResource(commonR.string.tailcat_status_title),
                value = stringResource(
                    if (state.isPaired) {
                        commonR.string.tailcat_status_paired
                    } else {
                        commonR.string.tailcat_status_not_paired
                    },
                ),
            )
            StatusRow(
                title = stringResource(commonR.string.tailcat_active_connection_title),
                value = stringResource(
                    when (state.activeConnection) {
                        TailcatActiveConnection.TAILCAT -> commonR.string.tailcat_active_connection_tailcat
                        TailcatActiveConnection.OTHER -> commonR.string.tailcat_active_connection_other
                        TailcatActiveConnection.NONE -> commonR.string.tailcat_active_connection_none
                    },
                ),
            )
        }
    }
}

@Composable
private fun TailcatPairSection(
    state: TailcatSettingsUiState,
    onLinkTextChange: (String) -> Unit,
    onPairClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    HATextField(
        value = state.linkText,
        onValueChange = onLinkTextChange,
        label = { Text(stringResource(commonR.string.tailcat_pair_link_label)) },
        supportingText = if (state.isPaired) {
            { Text(stringResource(commonR.string.tailcat_pair_replaces)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        maxLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    HAAccentButton(
        text = stringResource(commonR.string.tailcat_pair_button),
        onClick = onPairClick,
        enabled = state.linkText.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.isPaired) {
        HAPlainButton(
            text = stringResource(commonR.string.tailcat_remove_title),
            onClick = onRemoveClick,
            variant = ButtonVariant.DANGER,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatusRow(title: String, value: String) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = HATextStyle.Body.copy(textAlign = TextAlign.Start))
        Text(text = value, style = HATextStyle.BodyMedium.copy(textAlign = TextAlign.End))
    }
}

@Preview
@Composable
private fun TailcatSettingsScreenPairedPreview() {
    HAThemeForPreview {
        TailcatSettingsScreen(
            state = TailcatSettingsUiState(isPaired = true, activeConnection = TailcatActiveConnection.TAILCAT),
            onLinkTextChange = {},
            onPairClick = {},
            onRemoveConfirmed = {},
        )
    }
}

@Preview
@Composable
private fun TailcatSettingsScreenNotPairedPreview() {
    HAThemeForPreview {
        TailcatSettingsScreen(
            state = TailcatSettingsUiState(isPaired = false, activeConnection = TailcatActiveConnection.OTHER),
            onLinkTextChange = {},
            onPairClick = {},
            onRemoveConfirmed = {},
        )
    }
}
