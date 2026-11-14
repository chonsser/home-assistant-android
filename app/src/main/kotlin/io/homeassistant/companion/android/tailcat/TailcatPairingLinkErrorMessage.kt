package io.homeassistant.companion.android.tailcat

import androidx.annotation.StringRes
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.tailcat.TailcatPairingLinkError

/** The message telling the user why a pairing link was rejected. */
@StringRes
internal fun TailcatPairingLinkError.messageRes(): Int = when (this) {
    TailcatPairingLinkError.INVALID_LINK -> commonR.string.tailcat_pairing_error_invalid_link
    TailcatPairingLinkError.INVALID_ADDRESS -> commonR.string.tailcat_pairing_error_invalid_address
    TailcatPairingLinkError.INVALID_KEY -> commonR.string.tailcat_pairing_error_invalid_key
    TailcatPairingLinkError.INVALID_PORT -> commonR.string.tailcat_pairing_error_invalid_port
}
