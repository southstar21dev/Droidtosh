package dev.southstar21.droidtosh.transport

import dev.southstar21.droidtosh.protocol.v1.Envelope
import java.util.UUID

const val PROTOCOL_VERSION = 2

/** The protocol versions this build can speak, advertised in AuthResponse. */
const val MIN_PROTOCOL_VERSION = 2
const val MAX_PROTOCOL_VERSION = 2

fun newEnvelope(block: Envelope.Builder.() -> Unit): Envelope =
    Envelope.newBuilder()
        .setProtocolVersion(PROTOCOL_VERSION)
        .setMessageId(UUID.randomUUID().toString())
        .setSentAtMs(System.currentTimeMillis())
        .apply(block)
        .build()
