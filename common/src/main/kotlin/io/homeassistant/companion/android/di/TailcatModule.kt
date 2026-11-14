package io.homeassistant.companion.android.di

import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.data.tailcat.KeystoreTailcatConfigCipher
import io.homeassistant.companion.android.common.data.tailcat.TailcatConfigCipher
import io.homeassistant.companion.android.common.data.tailcat.TailcatTunnelResolver

@Module
@InstallIn(SingletonComponent::class)
internal interface TailcatModule {
    @Binds
    fun bindTailcatConfigCipher(cipher: KeystoreTailcatConfigCipher): TailcatConfigCipher

    /** Bound by the phone app, which embeds libtailcat; absent elsewhere. */
    @BindsOptionalOf
    fun optionalTailcatTunnelResolver(): TailcatTunnelResolver
}
