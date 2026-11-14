package io.homeassistant.companion.android.common.data.tailcat

/**
 * Encrypts a [TailcatConfig] for storage with the server in the database, and decrypts it back.
 *
 * The configuration holds this device's private key and the server's secret address. The database
 * is part of the app's backups, so the configuration is encrypted with a key that never leaves this
 * device: a restored backup keeps the server but loses its Tailcat pairing, which then has to be
 * paired again.
 */
interface TailcatConfigCipher {
    /** Encrypts [config] into an opaque string to persist. */
    fun encrypt(config: TailcatConfig): String

    /**
     * Decrypts a value produced by [encrypt].
     *
     * @return the configuration, or `null` when it cannot be decrypted on this device (for example
     *   after restoring a backup on another device) or is malformed
     */
    fun decrypt(encrypted: String): TailcatConfig?
}
