package dev.southstar21.droidtosh.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64 as AndroidBase64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.KeyStore
import java.util.Base64
import dev.southstar21.droidtosh.transport.ConnectionMode
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A stored pairing. The relay fields ([host], [port], [relayFingerprint]) are null for a local-only
 * pairing; [mode] starts as the Mac's mode from the QR code and the user can change it.
 */
data class PairingCredentials(
    val host: String?,
    val port: Int?,
    val room: String,
    val fingerprint: String,
    val relayFingerprint: String?,
    val psk: ByteArray,
    val mode: ConnectionMode = ConnectionMode.RELAY,
    val localHost: String? = null,
    val localPort: Int? = null,
) {
    /** Whether this pairing can use the relay at all. */
    val hasRelay: Boolean get() = host != null && port != null && relayFingerprint != null

    override fun equals(other: Any?): Boolean =
        other is PairingCredentials &&
            host == other.host && port == other.port && room == other.room &&
            fingerprint == other.fingerprint && relayFingerprint == other.relayFingerprint &&
            psk.contentEquals(other.psk) && mode == other.mode &&
            localHost == other.localHost && localPort == other.localPort

    override fun hashCode(): Int =
        listOf(host, port, room, fingerprint, relayFingerprint, mode, localHost, localPort).hashCode() * 31 +
            psk.contentHashCode()
}

/** The Keystore failed transiently; the stored pairing is intact and may load on a later attempt. */
class CredentialsUnavailableException(cause: Throwable) : Exception("credentials unavailable", cause)

class CredentialStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("droidsync", Context.MODE_PRIVATE)
    // Kept outside [preferences] so it survives [clear].
    private val meta = context.getSharedPreferences("droidsync_meta", Context.MODE_PRIVATE)

    /** True after a stored pairing from an older app version was discarded; cleared by [save]. */
    fun repairNeeded(): Boolean = meta.getBoolean(KEY_REPAIR_NEEDED, false)

    fun save(credentials: PairingCredentials) {
        meta.edit().remove(KEY_REPAIR_NEEDED).apply()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = encode(credentials).toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(payload)
        preferences.edit()
            .putString(KEY_IV, AndroidBase64.encodeToString(cipher.iv, AndroidBase64.NO_WRAP))
            .putString(KEY_PAYLOAD, AndroidBase64.encodeToString(ciphertext, AndroidBase64.NO_WRAP))
            .apply()
    }

    /** Whether a pairing exists, without treating a transient Keystore failure as unpaired. */
    fun isPaired(): Boolean =
        try {
            load() != null
        } catch (e: CredentialsUnavailableException) {
            true
        }

    /**
     * Returns null when nothing usable is stored. Only data that can never decrypt is wiped; other
     * Keystore errors throw [CredentialsUnavailableException] so a hiccup doesn't silently unpair.
     */
    fun load(): PairingCredentials? {
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val ciphertext = preferences.getString(KEY_PAYLOAD, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, AndroidBase64.decode(iv, AndroidBase64.NO_WRAP)),
            )
            val plaintext = cipher.doFinal(AndroidBase64.decode(ciphertext, AndroidBase64.NO_WRAP))
                .toString(Charsets.UTF_8)
            decode(plaintext) ?: run {
                // Decrypted fine but in an older format (e.g. a v1 pairing without the relay
                // fingerprint): it can't connect any more, so ask the user to re-pair.
                clear()
                meta.edit().putBoolean(KEY_REPAIR_NEEDED, true).apply()
                null
            }
        } catch (e: AEADBadTagException) {
            clear()
            null
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear()
            null
        } catch (e: IllegalArgumentException) {
            clear()
            null
        } catch (e: GeneralSecurityException) {
            throw CredentialsUnavailableException(e)
        } catch (e: ProviderException) {
            throw CredentialsUnavailableException(e)
        } catch (e: IOException) {
            throw CredentialsUnavailableException(e)
        }
    }

    /** Changes the connection mode of the stored pairing; returns false when nothing could be updated. */
    fun updateMode(mode: ConnectionMode): Boolean {
        val current = try {
            load()
        } catch (e: CredentialsUnavailableException) {
            null
        } ?: return false
        if (current.mode != mode) save(current.copy(mode = mode))
        return true
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    internal companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "droidsync-credentials"
        const val KEY_IV = "iv"
        const val KEY_PAYLOAD = "credentials"
        const val KEY_REPAIR_NEEDED = "repair_needed"
        const val FORMAT_VERSION = "v4"
        /** v3 predates connection modes; it always means a relay pairing. */
        private const val RELAY_ONLY_FORMAT_VERSION = "v3"

        internal fun encode(credentials: PairingCredentials): String =
            listOf(
                FORMAT_VERSION,
                credentials.host.orEmpty(),
                credentials.port?.toString().orEmpty(),
                credentials.room,
                credentials.fingerprint,
                credentials.relayFingerprint.orEmpty(),
                Base64.getEncoder().encodeToString(credentials.psk),
                credentials.mode.wire,
                credentials.localHost.orEmpty(),
                credentials.localPort?.toString().orEmpty(),
            ).joinToString("\n")

        internal fun decode(plaintext: String): PairingCredentials? {
            val parts = plaintext.split("\n")
            val expected = when (parts.first()) {
                FORMAT_VERSION -> 10
                RELAY_ONLY_FORMAT_VERSION -> 7
                else -> return null
            }
            if (parts.size != expected) return null
            return try {
                val credentials = PairingCredentials(
                    host = parts[1].ifEmpty { null },
                    port = parts[2].ifEmpty { null }?.toInt(),
                    room = parts[3],
                    fingerprint = parts[4],
                    relayFingerprint = parts[5].ifEmpty { null },
                    psk = Base64.getDecoder().decode(parts[6]),
                    mode = if (expected == 7) ConnectionMode.RELAY else ConnectionMode.fromWire(parts[7]) ?: return null,
                    localHost = parts.getOrNull(8)?.ifEmpty { null },
                    localPort = parts.getOrNull(9)?.ifEmpty { null }?.toInt(),
                )
                credentials.takeIf { !it.mode.usesRelay || it.hasRelay }
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
