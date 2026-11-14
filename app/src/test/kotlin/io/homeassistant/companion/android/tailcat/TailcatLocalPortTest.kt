package io.homeassistant.companion.android.tailcat

import io.homeassistant.companion.android.common.data.tailcat.TailcatConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TailcatLocalPortTest {

    @Test
    fun `Given known inputs when hashing then matches the FNV-1a 64 reference values`() {
        assertEquals(0xCBF29CE484222325UL, TailcatLocalPort.fnv1a64(""))
        assertEquals(0xAF6C3A0B7E33B304UL, TailcatLocalPort.fnv1a64("tcexample:8123"))
    }

    @Test
    fun `Given an endpoint when deriving its port then it is stable, in range and independent of the client key`() {
        val config = TailcatConfig("tcexample", "privkey:01", 8123)

        assertEquals(48292, TailcatLocalPort.preferred(config))
        assertEquals(TailcatLocalPort.preferred(config), TailcatLocalPort.preferred(config.copy(clientPrivateKey = "privkey:02")))
        assertNotEquals(TailcatLocalPort.preferred(config), TailcatLocalPort.preferred(config.copy(tunnelPort = 8124)))
        assertTrue(TailcatLocalPort.preferred(config) in TailcatLocalPort.range)
    }

    @Test
    fun `Given the last port of the range when listing candidates then they wrap around`() {
        assertEquals(listOf(59998, 59999, 20000, 20001), TailcatLocalPort.candidates(59998, 4))
    }
}
