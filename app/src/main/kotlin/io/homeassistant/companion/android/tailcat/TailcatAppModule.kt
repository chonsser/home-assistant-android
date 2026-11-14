package io.homeassistant.companion.android.tailcat

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.data.tailcat.TailcatTunnelResolver

/** Lets the shared connection logic resolve Tailcat tunnel URLs in the phone app process. */
@Module
@InstallIn(SingletonComponent::class)
internal interface TailcatAppModule {
    @Binds
    fun bindTailcatTunnelResolver(manager: TailcatTunnelManager): TailcatTunnelResolver
}
