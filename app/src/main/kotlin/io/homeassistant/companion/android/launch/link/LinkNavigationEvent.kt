package io.homeassistant.companion.android.launch.link

import androidx.annotation.StringRes
import io.homeassistant.companion.android.frontend.navigation.FrontendTarget

/**
 * One-shot navigation events.
 */
sealed interface LinkNavigationEvent {
    /** Close without navigating anywhere. */
    data object Finish : LinkNavigationEvent

    /** Start the server onboarding flow for [serverUrl]. */
    data class OpenInvitation(val serverUrl: String) : LinkNavigationEvent

    /** Open the frontend at [target] on the server identified by [serverId]. */
    data class NavigateToWebView(val target: FrontendTarget, val serverId: Int) : LinkNavigationEvent

    /** Start onboarding a new server reached through a Tailcat tunnel at the loopback [serverUrl]. */
    data class OpenTailcatOnboarding(val serverUrl: String) : LinkNavigationEvent

    /** Tell the user why the link could not be used, then close. */
    data class ShowError(@StringRes val messageRes: Int) : LinkNavigationEvent

    /** Tell the user that [serverName] is now paired through Tailcat, then open it. */
    data class TailcatPaired(val serverName: String, val serverId: Int) : LinkNavigationEvent
}
