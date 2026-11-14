package io.homeassistant.companion.android.common.data.tailcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class TailcatPairingLinkTest {

    private val address = "tcexampleaddress0123"
    private val key = "privkey:0123abcd"

    private fun link(query: String) = "homeassistant://tailcat/pair?$query"

    @Test
    fun `Given a complete pairing link when parsing then returns its configuration and name`() {
        val result = TailcatPairingLink.parse(link("address=$address&key=privkey%3A0123abcd&port=8123&name=My%20Home"))

        assertEquals(
            TailcatPairingLinkResult.Valid(
                TailcatPairingLink(TailcatConfig(address, key, 8123), name = "My Home"),
            ),
            result,
        )
    }

    @Test
    fun `Given a pairing link without name and with surrounding whitespace when parsing then name is null`() {
        val result = TailcatPairingLink.parse("  ${link("address=$address&key=$key&port=1")}\n")

        assertEquals(TailcatPairingLinkResult.Valid(TailcatPairingLink(TailcatConfig(address, key, 1), null)), result)
    }

    @ParameterizedTest
    @CsvSource(
        "https://tailcat/pair?address=tcx&key=privkey:1&port=1, INVALID_LINK",
        "homeassistant://invite/pair?address=tcx&key=privkey:1&port=1, INVALID_LINK",
        "homeassistant://tailcat/other?address=tcx&key=privkey:1&port=1, INVALID_LINK",
        "homeassistant://tailcat/pair?key=privkey:1&port=1, INVALID_ADDRESS",
        "homeassistant://tailcat/pair?address=xyz&key=privkey:1&port=1, INVALID_ADDRESS",
        "homeassistant://tailcat/pair?address=tc&key=privkey:1&port=1, INVALID_ADDRESS",
        "homeassistant://tailcat/pair?address=tcx&address=tcy&key=privkey:1&port=1, INVALID_ADDRESS",
        "homeassistant://tailcat/pair?address=tcx&port=1, INVALID_KEY",
        "homeassistant://tailcat/pair?address=tcx&key=nodekey:1&port=1, INVALID_KEY",
        "homeassistant://tailcat/pair?address=tcx&key=privkey:&port=1, INVALID_KEY",
        "homeassistant://tailcat/pair?address=tcx&key=privkey:1, INVALID_PORT",
        "homeassistant://tailcat/pair?address=tcx&key=privkey:1&port=0, INVALID_PORT",
        "homeassistant://tailcat/pair?address=tcx&key=privkey:1&port=65536, INVALID_PORT",
        "homeassistant://tailcat/pair?address=tcx&key=privkey:1&port=-1, INVALID_PORT",
        "homeassistant://tailcat/pair?address=tcx&key=privkey:1&port=99999999999, INVALID_PORT",
        "homeassistant://tailcat/pair?address=tcx&key=privkey:1&port=80a, INVALID_PORT",
        "homeassistant://tailcat/pair?address=tc%ZZ&key=privkey:1&port=1, INVALID_LINK",
    )
    fun `Given an invalid pairing link when parsing then reports why without the secrets`(
        link: String,
        error: TailcatPairingLinkError,
    ) {
        assertEquals(TailcatPairingLinkResult.Invalid(error), TailcatPairingLink.parse(link))
    }

    @ParameterizedTest
    @ValueSource(strings = ["homeassistant://tailcat/pair", "HOMEASSISTANT://TAILCAT/anything"])
    fun `Given a link to the Tailcat host when checking the route then it is a pairing link`(link: String) {
        assertTrue(TailcatPairingLink.isPairingLink(link))
    }

    @ParameterizedTest
    @ValueSource(strings = ["homeassistant://navigate/tailcat", "https://tailcat/pair", "not a link"])
    fun `Given a link elsewhere when checking the route then it is not a pairing link`(link: String) {
        assertFalse(TailcatPairingLink.isPairingLink(link))
    }

    @Test
    fun `Given a parsed link when converting it to text then secrets are redacted`() {
        val parsed = TailcatPairingLink(TailcatConfig(address, key, 8123), name = "Home")

        assertFalse(parsed.toString().contains(address))
        assertFalse(parsed.toString().contains(key))
        assertFalse(parsed.config.toString().contains(key))
    }
}
