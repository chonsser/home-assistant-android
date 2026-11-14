package io.homeassistant.companion.android.common.data.servers

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.common.data.network.NetworkChangeObserver
import io.homeassistant.companion.android.common.data.network.NetworkHelper
import io.homeassistant.companion.android.common.data.network.WifiHelper
import io.homeassistant.companion.android.common.data.tailcat.TailcatUrlProvider
import io.homeassistant.companion.android.common.util.DisabledLocationHandler
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import io.homeassistant.companion.android.database.server.ServerDao
import io.homeassistant.companion.android.util.hasSameOrigin
import java.net.URL
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

internal class ServerConnectionStateProviderImpl @AssistedInject constructor(
    @param:ApplicationContext private val context: Context,
    private val serverManager: ServerManager,
    private val serverDao: ServerDao,
    private val wifiHelper: WifiHelper,
    private val networkHelper: NetworkHelper,
    private val networkChangeObserver: NetworkChangeObserver,
    private val tailcatUrlProvider: TailcatUrlProvider,
    @Assisted private val serverId: Int,
) : ServerConnectionStateProvider {

    private suspend fun connection(): ServerConnectionInfo {
        return checkNotNull(serverManager.getServer(serverId)?.connection) {
            "No server found for id $serverId"
        }
    }

    override suspend fun isInternal(requiresUrl: Boolean): Boolean {
        val connection = connection()

        if (requiresUrl && connection.internalUrl.isNullOrBlank()) return false

        if (connection.internalEthernet == true) {
            val usesEthernet = networkHelper.isUsingEthernet()
            Timber.d("usesEthernet is: $usesEthernet")
            if (usesEthernet) return true
        }

        if (connection.internalVpn == true) {
            val usesVpn = networkHelper.isUsingVpn()
            Timber.d("usesVpn is: $usesVpn")
            if (usesVpn) return true
        }

        return if (connection.internalSsids.isNotEmpty()) {
            val usesInternalSsid = wifiHelper.isUsingSpecificWifi(connection.internalSsids)
            val usesWifi = wifiHelper.isUsingWifi()
            Timber.d("usesInternalSsid is: $usesInternalSsid, usesWifi is: $usesWifi")
            usesInternalSsid && usesWifi
        } else {
            false
        }
    }

    override suspend fun getExternalUrl(): URL? {
        val connection = connection()

        val url = if (connection.useCloud && connection.cloudUrl != null) {
            Timber.d("Using cloud / remote UI URL")
            connection.cloudHttpUrl?.toUrl()
        } else {
            Timber.d("Using external URL")
            connection.externalHttpUrl?.toUrl()
        }

        return url
    }

    override suspend fun getApiUrls(): List<HttpUrl> {
        val connection = connection()
        val webhookId = connection.webhookId

        if (webhookId.isNullOrBlank()) {
            return emptyList()
        }

        val allowInsecure = connection.allowInsecureConnection ?: true
        val isOnHomeNetwork = isInternal(requiresUrl = false)

        return buildList {
            // Internal URL: use when on home network, or when prioritized and connection is secure/allowed
            val internalUrlIsSecure = connection.internalUrl?.startsWith("https://") == true
            val canUseInternalUrl = isOnHomeNetwork ||
                (connection.prioritizeInternal && (internalUrlIsSecure || allowInsecure))
            if (canUseInternalUrl) {
                connection.internalHttpUrl?.buildWebhookUrl(webhookId)?.let(::add)
            }

            // Cloudhook URL: always add if available
            connection.cloudhookHttpUrl?.let(::add)

            // External URL: use when on home network, or when connection is secure/allowed. A plain
            // HTTP external URL comes after the Tailcat tunnel, which is encrypted end to end.
            val externalUrlIsSecure = connection.externalUrl.startsWith("https://")
            val canUseExternalUrl = isOnHomeNetwork || externalUrlIsSecure || allowInsecure
            if (canUseExternalUrl && externalUrlIsSecure) {
                connection.externalHttpUrl?.buildWebhookUrl(webhookId)?.let(::add)
            }

            tailcatUrlProvider.localUrl(connection)?.buildWebhookUrl(webhookId)?.let(::add)

            if (canUseExternalUrl && !externalUrlIsSecure) {
                connection.externalHttpUrl?.buildWebhookUrl(webhookId)?.let(::add)
            }
        }
    }

    override suspend fun getSecurityState(): SecurityState {
        val connection = connection()

        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val isLocationEnabled = DisabledLocationHandler.isLocationEnabled(context)

        return SecurityState(
            isOnHomeNetwork = isInternal(requiresUrl = false),
            hasHomeSetup = connection.hasHomeNetworkSetup,
            locationEnabled = hasLocationPermission && isLocationEnabled,
        )
    }

    override suspend fun canSafelySendCredentials(url: String): Boolean {
        val connection = connection()
        // The tunnel's loopback URL is plain HTTP, but traffic leaves the device encrypted end to end.
        if (isTailcatUrl(connection, url)) return true
        if (!connection.isKnownUrl(url)) return false
        if (url.startsWith("https://")) return true

        // URL is HTTP and belongs to this server - check if it's safe
        val allowInsecure = connection.allowInsecureConnection ?: true
        return allowInsecure || isInternal(requiresUrl = false)
    }

    // TODO this could be cache too, the idea would be to have better logging experience regarding
    //  which URL is currently being used
    //  https://github.com/home-assistant/android/issues/6147
    override fun urlFlow(isInternalOverride: ((ServerConnectionInfo) -> Boolean)?): Flow<UrlState> {
        return merge(
            flowOf(Unit), // Used to trigger a getUrl
            observeLocationState(),
            networkChangeObserver.observerNetworkChange,
            observeConnectionInfoChanges(),
        ).map {
            val connection = connection()
            val isInternal =
                isInternalOverride?.invoke(connection) ?: isInternal() ||
                    connection.prioritizeInternal &&
                    !DisabledLocationHandler.isLocationEnabled(
                        context,
                    )
            val tailcatUrl = tailcatUrlProvider.localUrl(connection)
            val potentialUrl = connection.getUrl(isInternal, tailcatUrl)
            // The Tailcat tunnel counts as secure: it is encrypted end to end like HTTPS.
            val isTailcat = tailcatUrl != null && potentialUrl?.toString() == tailcatUrl.toUrl().toString()
            // Null means the user hasn't configured their insecure connection preference yet.
            // This only applies to users who installed before it became mandatory in onboarding.
            // Default to true to maintain backwards compatibility with their existing setup.
            val allowInsecure = connection.allowInsecureConnection ?: true

            if (allowInsecure || isTailcat || potentialUrl?.protocol == "https") {
                UrlState.HasUrl(potentialUrl)
            } else {
                if (isInternal(requiresUrl = false)) {
                    UrlState.HasUrl(potentialUrl)
                } else {
                    UrlState.InsecureState
                }
            }
        }.distinctUntilChanged()
    }

    private suspend fun isTailcatUrl(connection: ServerConnectionInfo, url: String): Boolean {
        val tailcatUrl = tailcatUrlProvider.localUrl(connection) ?: return false
        return url.toHttpUrlOrNull()?.hasSameOrigin(tailcatUrl) == true
    }

    private fun observeConnectionInfoChanges(): Flow<ServerConnectionInfo> {
        return serverDao.getFlow(serverId)
            .mapNotNull { it?.connection }
            .distinctUntilChanged()
    }

    // If permission is denied to the app then the process restart so initial state is going to be updated
    private fun observeLocationState(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Emit initial state
        trySend(Unit)

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }
}

private fun HttpUrl.buildWebhookUrl(webhookId: String): HttpUrl {
    return newBuilder()
        .addPathSegments("api/webhook/$webhookId")
        .build()
}

/**
 * Picks the URL to use: internal (on the home network) > cloud (when enabled) > HTTPS external >
 * Tailcat tunnel > plain HTTP external.
 *
 * Unlike the iOS app, a plain HTTP external URL ranks below the tunnel: the onboarding stores the
 * URL the server was found at as the external URL, which for most local-only setups is a LAN
 * address that does not work away from home, while the tunnel works anywhere.
 */
private fun ServerConnectionInfo.getUrl(isInternal: Boolean, tailcatUrl: HttpUrl?): URL? {
    val external = externalHttpUrl
    val url = if (isInternal && internalUrl != null) {
        Timber.d("Using internal URL")
        internalHttpUrl?.toUrl()
    } else if (useCloud && cloudUrl != null) {
        Timber.d("Using cloud / remote UI URL")
        cloudHttpUrl?.toUrl()
    } else if (external != null && (external.isHttps || tailcatUrl == null)) {
        Timber.d("Using external URL")
        external.toUrl()
    } else if (tailcatUrl != null) {
        Timber.d("Using Tailcat tunnel")
        tailcatUrl.toUrl()
    } else {
        Timber.d("No usable URL")
        null
    }

    return url
}
