package io.homeassistant.companion.android.common.data.tailcat

/** Smallest valid TCP port. */
private const val MIN_PORT = 1

/** Largest valid TCP port. */
private const val MAX_PORT = 65535

/**
 * How to reach a Home Assistant server through a Tailcat tunnel (https://github.com/tailscale/tailcat):
 * the server's Tailcat address, this device's own private client key (the server allowlists its
 * public half) and the TCP port inside the tunnel that speaks plain HTTP to Home Assistant.
 *
 * [address] and [clientPrivateKey] are secrets: never log them. [toString] is redacted for that
 * reason, so the instance can safely end up in a log line or an exception message.
 *
 * @property address the server's Tailcat address (`tc…`)
 * @property clientPrivateKey this device's Tailcat private key (`privkey:…`)
 * @property tunnelPort TCP port inside the tunnel that Home Assistant answers on
 */
data class TailcatConfig(val address: String, val clientPrivateKey: String, val tunnelPort: Int) {
    init {
        require(tunnelPort in MIN_PORT..MAX_PORT) { "Invalid tunnel port $tunnelPort" }
    }

    /**
     * Identifies the tunnel endpoint (server address and port) regardless of the client key, so a
     * device re-paired with a new key keeps talking to the same endpoint, on the same loopback port.
     * Contains the secret address: never log it.
     */
    val endpointIdentity: String
        get() = "$address:$tunnelPort"

    override fun toString(): String = "TailcatConfig(tunnelPort=$tunnelPort, <redacted>)"

    companion object {
        /** Whether [port] is a usable TCP port. */
        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT
    }
}
