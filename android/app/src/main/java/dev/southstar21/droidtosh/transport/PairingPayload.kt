package dev.southstar21.droidtosh.transport

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** A scanned pairing code; the relay fields are null in a local-only code (protocol/PROTOCOL-v2.md §2). */
data class PairingPayload(
    val deviceId: String,
    val deviceName: String,
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
    override fun equals(other: Any?): Boolean =
        other is PairingPayload &&
            deviceId == other.deviceId && deviceName == other.deviceName &&
            host == other.host && port == other.port && room == other.room &&
            fingerprint == other.fingerprint && relayFingerprint == other.relayFingerprint &&
            psk.contentEquals(other.psk) && mode == other.mode &&
            localHost == other.localHost && localPort == other.localPort

    override fun hashCode(): Int =
        listOf(deviceId, deviceName, host, port, room, fingerprint, relayFingerprint, mode, localHost, localPort)
            .hashCode() * 31 + psk.contentHashCode()
}

/** A pairing code from an older Mac app; the message is meant for the user. */
class PairingVersionException(message: String) : IllegalArgumentException(message)

object Base64URL {
    fun encode(data: ByteArray): String =
        Base64.getEncoder().encodeToString(data).trimEnd('=').replace('+', '-').replace('/', '_')

    fun decode(value: String): ByteArray {
        var padded = value.replace('-', '+').replace('_', '/')
        while (padded.length % 4 != 0) padded += "="
        return Base64.getDecoder().decode(padded)
    }
}

object PairingPayloadParser {
    const val VERSION = 2
    private val ROOM_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
    private val FINGERPRINT_PATTERN = Regex("^[0-9a-fA-F]{64}$")

    fun parse(uri: String): PairingPayload {
        val parsed = URI(uri)
        require(parsed.scheme == "droidsync" && parsed.host == "pair") { "unsupported pairing URI" }
        val params = (parsed.rawQuery ?: "")
            .split("&")
            .filter { it.isNotEmpty() }
            .associate {
                val pieces = it.split("=", limit = 2)
                URLDecoder.decode(pieces[0], "UTF-8") to URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
            }
        val version = params["v"]?.toIntOrNull() ?: throw IllegalArgumentException("missing version")
        if (version < VERSION) throw PairingVersionException("Pairing code is from an older version — re-pair")
        require(version == VERSION) { "unsupported version $version" }
        val mode = params["mode"]?.let { ConnectionMode.fromWire(it) ?: throw IllegalArgumentException("invalid mode") }
            ?: ConnectionMode.RELAY
        val fingerprint = params["fp"] ?: throw IllegalArgumentException("missing fp")
        require(FINGERPRINT_PATTERN.matches(fingerprint)) { "invalid fp" }
        // A local-only code carries no relay; any relay fields it does carry must still be valid.
        val relayFingerprint = params["rfp"]
        if (mode.usesRelay && relayFingerprint == null) throw IllegalArgumentException("missing rfp")
        require(relayFingerprint == null || FINGERPRINT_PATTERN.matches(relayFingerprint)) { "invalid rfp" }
        val host = params["host"]?.takeIf { it.isNotEmpty() }
        if (mode.usesRelay && host == null) throw IllegalArgumentException("missing host")
        val port = params["port"]?.let { port(it) ?: throw IllegalArgumentException("invalid port") }
        if (mode.usesRelay && port == null) throw IllegalArgumentException("missing port")
        val localPort = params["lport"]?.let { port(it) ?: throw IllegalArgumentException("invalid lport") }
        if (mode.usesLocal && localPort == null) throw IllegalArgumentException("missing lport")
        val psk = Base64URL.decode(params["psk"] ?: throw IllegalArgumentException("missing psk"))
        require(psk.size == Auth.PSK_LENGTH) { "invalid psk length" }
        val room = params["room"] ?: throw IllegalArgumentException("missing room")
        require(ROOM_PATTERN.matches(room)) { "invalid room" }
        return PairingPayload(
            deviceId = params["did"] ?: throw IllegalArgumentException("missing did"),
            deviceName = params["name"] ?: throw IllegalArgumentException("missing name"),
            host = host,
            port = port,
            room = room,
            fingerprint = fingerprint.lowercase(),
            relayFingerprint = relayFingerprint?.lowercase(),
            psk = psk,
            mode = mode,
            localHost = params["lhost"]?.takeIf { it.isNotEmpty() },
            localPort = localPort,
        )
    }

    private fun port(value: String): Int? = value.toIntOrNull()?.takeIf { it in 1..65535 }
}
