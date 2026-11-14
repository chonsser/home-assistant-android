package io.homeassistant.companion.android.common.data.tailcat

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

private const val PAIRING_HOST = "tailcat"
private const val PAIRING_PATH = "/pair"
private const val ADDRESS_PREFIX = "tc"
private const val KEY_PREFIX = "privkey:"
private const val PARAM_ADDRESS = "address"
private const val PARAM_KEY = "key"
private const val PARAM_PORT = "port"
private const val PARAM_NAME = "name"

/** The `homeassistant://` deep link scheme, also used by debug builds. */
private const val DEEP_LINK_SCHEME = "homeassistant"

/**
 * A Tailcat pairing link produced by Home Assistant core (also shown as a QR code):
 *
 * `homeassistant://tailcat/pair?address=<tc…>&key=<privkey:…>&port=<1-65535>&name=<instance name>`
 *
 * The server generates this device's key pair and allowlists its public half, so the link carries
 * a secret private key and the server's secret address: never log the link or its values.
 *
 * @property config how to reach the server through its tunnel
 * @property name the instance name suggested by the server, if any
 */
data class TailcatPairingLink(val config: TailcatConfig, val name: String?) {

    override fun toString(): String = "TailcatPairingLink(config=$config, name=$name)"

    companion object {
        /**
         * Whether [link] addresses the Tailcat pairing route at all, valid or not. Lets callers
         * route the link (and keep it out of logs) before validating it.
         */
        fun isPairingLink(link: String): Boolean {
            val uri = parseUri(link) ?: return false
            return uri.scheme.equals(DEEP_LINK_SCHEME, ignoreCase = true) &&
                uri.host.equals(PAIRING_HOST, ignoreCase = true)
        }

        /**
         * Parses and strictly validates a pairing link.
         *
         * @return the parsed link, or the reason it is invalid. Never contains the secret values.
         */
        fun parse(link: String): TailcatPairingLinkResult {
            val query = pairingQuery(link.trim())
                ?: return TailcatPairingLinkResult.Invalid(TailcatPairingLinkError.INVALID_LINK)

            val address = query.single(PARAM_ADDRESS)?.takeIf { it.isEncodedSecret(ADDRESS_PREFIX) }
            val key = query.single(PARAM_KEY)?.takeIf { it.isEncodedSecret(KEY_PREFIX) }
            val port = query.single(PARAM_PORT)
                ?.takeIf { value -> value.all { it in '0'..'9' } && value.length <= MAX_PORT_DIGITS }
                ?.toInt()
                ?.takeIf(TailcatConfig::isValidPort)

            return when {
                address == null -> TailcatPairingLinkResult.Invalid(TailcatPairingLinkError.INVALID_ADDRESS)
                key == null -> TailcatPairingLinkResult.Invalid(TailcatPairingLinkError.INVALID_KEY)
                port == null -> TailcatPairingLinkResult.Invalid(TailcatPairingLinkError.INVALID_PORT)
                else -> TailcatPairingLinkResult.Valid(
                    TailcatPairingLink(
                        config = TailcatConfig(address = address, clientPrivateKey = key, tunnelPort = port),
                        name = query.single(PARAM_NAME),
                    ),
                )
            }
        }

        /** The decoded query of a pairing link, or `null` when [link] is not a well-formed one. */
        private fun pairingQuery(link: String): Map<String, List<String>>? = parseUri(link)
            ?.takeIf { isPairingLink(link) && it.path == PAIRING_PATH }
            ?.let { parseQuery(it.rawQuery) }

        private fun String.isEncodedSecret(prefix: String): Boolean =
            startsWith(prefix) && length > prefix.length && none(Char::isWhitespace)

        private fun parseUri(link: String): URI? = try {
            URI(link)
        } catch (_: URISyntaxException) {
            // The link may hold secrets, so the exception (which quotes it) is not logged.
            null
        }

        /** Decodes the query into values per name, or `null` when it is not valid percent-encoding. */
        private fun parseQuery(rawQuery: String?): Map<String, List<String>>? {
            if (rawQuery.isNullOrEmpty()) return emptyMap()
            return try {
                rawQuery.split('&')
                    .filter { it.isNotEmpty() }
                    .map { pair ->
                        val name = URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8.name())
                        val value = URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8.name())
                        name to value
                    }
                    .groupBy({ it.first }, { it.second })
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** The trimmed value of [name], or `null` when it is missing, blank or repeated (ambiguous). */
        private fun Map<String, List<String>>.single(name: String): String? =
            get(name)?.singleOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        private const val MAX_PORT_DIGITS = 5
    }
}

/** Outcome of [TailcatPairingLink.parse]. */
sealed interface TailcatPairingLinkResult {
    /** The link is a valid pairing. */
    data class Valid(val link: TailcatPairingLink) : TailcatPairingLinkResult

    /** The link is not a usable pairing, for [error]. */
    data class Invalid(val error: TailcatPairingLinkError) : TailcatPairingLinkResult
}

/** Why a pairing link was rejected. Never carries the rejected values, which may be secrets. */
enum class TailcatPairingLinkError {
    INVALID_LINK,
    INVALID_ADDRESS,
    INVALID_KEY,
    INVALID_PORT,
}
