package io.homeassistant.companion.android.tailcat

import io.homeassistant.companion.android.common.data.tailcat.TailcatConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TailcatOnboardingTest {
    private val config = TailcatConfig("tcexample", "privkey:01", 8123)
    private val serverPairing: TailcatServerPairing = mockk {
        coEvery { localUrl(config) } returns "http://127.0.0.1:40123".toHttpUrl()
        coEvery { attachToOnboardedServer(any(), any()) } just runs
    }
    private val onboarding = TailcatOnboarding(serverPairing)

    @Test
    fun `Given onboarding through a tunnel when the new server is created then the pairing is attached once`() = runTest {
        val url = checkNotNull(onboarding.start(config)).toString()

        assertEquals("http://127.0.0.1:40123/", url)
        assertTrue(onboarding.isOnboardingUrl("http://127.0.0.1:40123"))
        assertTrue(onboarding.complete(url, serverId = 3))
        coVerify(exactly = 1) { serverPairing.attachToOnboardedServer(config, 3) }
        assertFalse(onboarding.isOnboardingUrl(url))
    }

    @Test
    fun `Given onboarding a regular URL when the new server is created then nothing is attached`() = runTest {
        onboarding.start(config)

        assertFalse(onboarding.isOnboardingUrl("http://192.168.1.2:8123"))
        assertFalse(onboarding.complete("http://192.168.1.2:8123", serverId = 4))
        assertFalse(onboarding.complete("not a url", serverId = 4))
        coVerify(exactly = 0) { serverPairing.attachToOnboardedServer(any(), any()) }
    }

    @Test
    fun `Given the tunnel cannot start when onboarding then there is no URL`() = runTest {
        coEvery { serverPairing.localUrl(config) } returns null

        assertNull(onboarding.start(config))
        assertFalse(onboarding.isOnboardingUrl("http://127.0.0.1:40123"))
    }
}
