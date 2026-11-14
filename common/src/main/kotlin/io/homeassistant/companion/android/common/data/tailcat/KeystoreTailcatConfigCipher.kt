package io.homeassistant.companion.android.common.data.tailcat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import timber.log.Timber

private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val KEY_ALIAS = "tailcat_config"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val KEY_SIZE_BITS = 256
private const val GCM_TAG_BITS = 128

/** Prefix of every stored value, so the format can evolve. */
private const val FORMAT_PREFIX = "v1:"

/**
 * [TailcatConfigCipher] backed by a non-exportable AES-GCM key in the Android Keystore.
 *
 * Decrypted values are cached in memory by their ciphertext: the URL of a server is resolved often,
 * and a Keystore operation is comparatively slow.
 */
@Singleton
class KeystoreTailcatConfigCipher @Inject constructor() : TailcatConfigCipher {

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, TailcatConfig>()

    override fun encrypt(config: TailcatConfig): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val plaintext = json.encodeToString(StoredTailcatConfig.serializer(), StoredTailcatConfig.from(config))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val payload = ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
        val encrypted = FORMAT_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
        cache[encrypted] = config
        return encrypted
    }

    override fun decrypt(encrypted: String): TailcatConfig? = cache[encrypted]
        ?: encrypted.takeIf { it.startsWith(FORMAT_PREFIX) }
            ?.let { decryptStored(it.removePrefix(FORMAT_PREFIX)) }
            ?.also { cache[encrypted] = it }

    private fun decryptStored(encoded: String): TailcatConfig? = try {
        existingSecretKey()?.let { key ->
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val ivSize = payload[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, payload, 1, ivSize))
            val plaintext = cipher.doFinal(payload, 1 + ivSize, payload.size - 1 - ivSize)
            json.decodeFromString(StoredTailcatConfig.serializer(), plaintext.toString(Charsets.UTF_8)).toConfig()
        }
    } catch (e: GeneralSecurityException) {
        Timber.w(e, "Failed to decrypt the Tailcat configuration")
        null
    } catch (_: SerializationException) {
        // The exception message could quote the decrypted secrets, so it is not logged.
        Timber.w("Failed to parse the Tailcat configuration")
        null
    } catch (e: IllegalArgumentException) {
        Timber.w(e, "Failed to decode the Tailcat configuration")
        null
    } catch (e: IndexOutOfBoundsException) {
        Timber.w(e, "Failed to decode the Tailcat configuration")
        null
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun existingSecretKey(): SecretKey? =
        (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    @Synchronized
    private fun secretKey(): SecretKey = existingSecretKey() ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build(),
            )
        }
        .generateKey()
}

@Serializable
private data class StoredTailcatConfig(
    @SerialName("address") val address: String,
    @SerialName("key") val key: String,
    @SerialName("port") val port: Int,
) {
    fun toConfig(): TailcatConfig? = if (TailcatConfig.isValidPort(port)) TailcatConfig(address, key, port) else null

    override fun toString(): String = "StoredTailcatConfig(<redacted>)"

    companion object {
        fun from(config: TailcatConfig) =
            StoredTailcatConfig(config.address, config.clientPrivateKey, config.tunnelPort)
    }
}
