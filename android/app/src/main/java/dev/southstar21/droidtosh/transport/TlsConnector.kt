package dev.southstar21.droidtosh.transport

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

sealed class RelayConnectResult {
    data class Connected(val socket: SSLSocket) : RelayConnectResult()
    data object Waiting : RelayConnectResult()
    data class Failed(val cause: Throwable) : RelayConnectResult()
}

/** The relay's TLS certificate didn't match the pinned fingerprint, or the relay doesn't speak TLS. */
class RelayTlsException(message: String, cause: Throwable) : IOException(message, cause)

/**
 * Connects through the relay (protocol/PROTOCOL-v2.md §1): TCP, then outer TLS pinned to the relay's
 * certificate, the join line inside it, and on `PAIRED` the end-to-end inner TLS pinned to the Mac,
 * layered on the outer TLS stream. Closing the returned socket closes both layers and the TCP socket.
 */
open class TlsConnector {

    open fun connect(
        host: String,
        port: Int,
        joinLine: String,
        relayFingerprint: String,
        macFingerprint: String,
    ): RelayConnectResult {
        var plain: Socket? = null
        var outer: SSLSocket? = null
        return try {
            val socket = Socket()
            plain = socket
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val relay = wrapRelayTls(socket, host, port, relayFingerprint)
            outer = relay
            relay.soTimeout = JOIN_TIMEOUT_MS
            relay.outputStream.apply {
                write(joinLine.toByteArray(Charsets.UTF_8))
                flush()
            }
            val response = readRelayResponseLine(relay.inputStream, MAX_RESPONSE_LINE)
            when (val parsed = RelayResponse.parse(response)) {
                RelayResponse.Waiting -> {
                    relay.close()
                    RelayConnectResult.Waiting
                }
                is RelayResponse.Error -> {
                    relay.close()
                    RelayConnectResult.Failed(IOException(relayErrorMessage(parsed.reason)))
                }
                RelayResponse.Paired -> {
                    relay.soTimeout = HANDSHAKE_TIMEOUT_MS
                    val ssl = wrapMacTls(relay, host, port, macFingerprint)
                    ssl.soTimeout = SESSION_READ_TIMEOUT_MS
                    RelayConnectResult.Connected(ssl)
                }
            }
        } catch (t: Throwable) {
            runCatching { outer?.close() }
            runCatching { plain?.close() }
            RelayConnectResult.Failed(t)
        }
    }

    /**
     * Connects straight to the Mac on the local network (protocol/PROTOCOL-v2.md §1.4): TCP, then the
     * inner TLS pinned to the Mac, with no relay TLS or join line. Throws when the Mac can't be reached.
     */
    open fun connectDirect(host: String, port: Int, macFingerprint: String): SSLSocket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), LOCAL_CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val ssl = wrapMacTls(socket, host, port, macFingerprint)
            ssl.soTimeout = SESSION_READ_TIMEOUT_MS
            return ssl
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    private fun wrapRelayTls(plain: Socket, host: String, port: Int, fingerprint: String): SSLSocket {
        val socket = pinnedSocket(plain, host, port, fingerprint, arrayOf("TLSv1.3", "TLSv1.2"))
        try {
            socket.startHandshake()
        } catch (error: SSLHandshakeException) {
            runCatching { socket.close() }
            throw relayHandshakeFailure(error)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
        return socket
    }

    private fun wrapMacTls(outer: Socket, host: String, port: Int, fingerprint: String): SSLSocket {
        val socket = pinnedSocket(outer, host, port, fingerprint, arrayOf("TLSv1.3"))
        try {
            socket.startHandshake()
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
        return socket
    }

    private fun pinnedSocket(
        under: Socket,
        host: String,
        port: Int,
        fingerprint: String,
        protocols: Array<String>,
    ): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(PinnedTrustManager(fingerprint)), SecureRandom())
        val socket = context.socketFactory.createSocket(under, host, port, true) as SSLSocket
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        socket.enabledProtocols = protocols
        return socket
    }
}

/**
 * Only a rejected certificate means the relay changed; a handshake cut short (relay busy or
 * restarting, network drop) is transient and must not tell the user to re-pair.
 */
internal fun relayHandshakeFailure(error: SSLHandshakeException): RelayTlsException {
    val pinRejected = generateSequence<Throwable>(error) { it.cause }.any { it is CertificateException }
    val message = if (pinRejected) {
        "Couldn't verify the relay; its certificate changed or it needs updating. Re-pair if it was replaced."
    } else {
        "The relay closed the connection during the TLS handshake"
    }
    return RelayTlsException(message, error)
}

internal fun relayErrorMessage(reason: String): String = when (reason) {
    "upgrade" -> "Relay and app versions don't match; update the relay and Droidtosh"
    else -> "relay error: $reason"
}

internal fun readRelayResponseLine(input: InputStream, max: Int): String {
    val bytes = ByteArrayOutputStream()
    while (bytes.size() <= max) {
        val next = input.read()
        if (next == -1) break
        if (next == '\n'.code) return bytes.toString(Charsets.UTF_8.name())
        bytes.write(next)
    }
    throw IOException("relay response line too long or truncated")
}

private const val CONNECT_TIMEOUT_MS = 10_000
private const val LOCAL_CONNECT_TIMEOUT_MS = 3_000
private const val JOIN_TIMEOUT_MS = 10_000
private const val HANDSHAKE_TIMEOUT_MS = 30_000
private const val SESSION_READ_TIMEOUT_MS = 120_000
private const val MAX_RESPONSE_LINE = 512
