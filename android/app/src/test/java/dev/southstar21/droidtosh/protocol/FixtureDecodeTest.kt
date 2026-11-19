package dev.southstar21.droidtosh.protocol

import dev.southstar21.droidtosh.protocol.v1.CallDirection
import dev.southstar21.droidtosh.protocol.v1.CallKind
import dev.southstar21.droidtosh.protocol.v1.CallPhase
import dev.southstar21.droidtosh.protocol.v1.ChargeSource
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.FindAction
import dev.southstar21.droidtosh.protocol.v1.FindEndReason
import dev.southstar21.droidtosh.protocol.v1.NetworkType
import dev.southstar21.droidtosh.protocol.v1.RingerMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureDecodeTest {

    private fun load(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.bin")) {
            "missing fixture $name.bin"
        }.use { it.readBytes() }

    @Test
    fun helloFixtureDecodesAndReEncodes() {
        val bytes = load("hello")
        val envelope = Envelope.parseFrom(bytes)
        assertEquals(2, envelope.protocolVersion)
        assertEquals("fixture-hello", envelope.messageId)
        assertEquals(1_730_000_000_000L, envelope.sentAtMs)
        assertTrue(envelope.hasHello())
        assertEquals("Pixel 8", envelope.hello.deviceName)
        assertEquals("15", envelope.hello.androidVersion)
        assertEquals(listOf("notifications", "calls"), envelope.hello.capabilitiesList)
        assertArrayEquals(bytes, envelope.toByteArray())
    }

    @Test
    fun pingFixtureDecodesAndReEncodes() {
        val bytes = load("ping")
        val envelope = Envelope.parseFrom(bytes)
        assertTrue(envelope.hasPing())
        assertEquals(1_730_000_000_003L, envelope.ping.sentAtMs)
        assertArrayEquals(bytes, envelope.toByteArray())
    }

    @Test
    fun notificationFixtureDecodesAndReEncodes() {
        val bytes = load("notification_record")
        val envelope = Envelope.parseFrom(bytes)
        assertTrue(envelope.hasNotificationPosted())
        val record = envelope.notificationPosted.record
        assertEquals("0|com.whatsapp|1|null|10123", record.key)
        assertEquals("com.whatsapp", record.packageName)
        assertEquals("Alice", record.title)
        assertEquals(1, record.actionsCount)
        assertEquals(0, record.actionsList[0].index)
        assertTrue(record.actionsList[0].hasRemoteInput)
        assertEquals("Message", record.remoteInputHint)
        assertArrayEquals(bytes, envelope.toByteArray())
    }

    @Test
    fun resumeFixtureDecodesAndReEncodes() {
        val bytes = load("resume")
        val envelope = Envelope.parseFrom(bytes)
        assertEquals(2, envelope.protocolVersion)
        assertEquals(7L, envelope.ack)
        assertTrue(envelope.hasResume())
        assertEquals("s-mac-1", envelope.resume.streamId)
        assertEquals(7L, envelope.resume.lastReceivedSeq)
        assertEquals("s-phone-1", envelope.resume.peerStreamId)
        assertArrayEquals(bytes, envelope.toByteArray())
    }

    @Test
    fun everyFixtureIsProtocolV2() {
        for (name in listOf("hello", "ping", "notification_record", "call_state", "resume", "device_status")) {
            assertEquals(name, 2, Envelope.parseFrom(load(name)).protocolVersion)
        }
    }

    @Test
    fun callStateFixtureDecodesAndReEncodes() {
        val bytes = load("call_state")
        val envelope = Envelope.parseFrom(bytes)
        assertTrue(envelope.hasCallState())
        val state = envelope.callState
        assertEquals(CallKind.CALL_KIND_CELLULAR, state.kind)
        assertEquals(CallDirection.CALL_DIRECTION_INCOMING, state.direction)
        assertEquals(CallPhase.CALL_PHASE_RINGING, state.phase)
        assertEquals("+15551234567", state.number)
        assertEquals("Personal", state.simLabel)
        assertArrayEquals(bytes, envelope.toByteArray())
    }

    @Test
    fun deviceStatusFixtureDecodesAndReEncodes() {
        val bytes = load("device_status")
        val envelope = Envelope.parseFrom(bytes)
        assertEquals("fixture-device-status", envelope.messageId)
        assertEquals(1_760_000_000_000L, envelope.sentAtMs)
        assertTrue(envelope.hasDeviceStatus())
        val status = envelope.deviceStatus
        assertEquals(57, status.batteryPercent)
        assertTrue(status.charging)
        assertEquals(ChargeSource.CHARGE_SOURCE_WIRELESS, status.chargeSource)
        assertFalse(status.powerSave)
        assertEquals(NetworkType.NETWORK_TYPE_CELLULAR, status.networkType)
        assertEquals(-1, status.wifiLevel)
        assertEquals(3, status.cellularLevel)
        assertEquals("5G", status.cellularGeneration)
        assertEquals("Telekom.de", status.carrier)
        assertFalse(status.airplaneMode)
        assertEquals(RingerMode.RINGER_MODE_VIBRATE, status.ringerMode)
        assertTrue(status.doNotDisturb)
        assertEquals(1_759_999_999_500L, status.sampledAtMs)
        assertArrayEquals(bytes, envelope.toByteArray())
    }

    @Test
    fun fileOfferFixtureDecodesAndReEncodes() {
        val bytes = load("file_offer")
        val envelope = Envelope.parseFrom(bytes)
        assertEquals("fixture-file-offer", envelope.messageId)
        assertTrue(envelope.hasFileOffer())
        val offer = envelope.fileOffer
        assertEquals("11111111-2222-3333-4444-555555555555", offer.transferId)
        assertEquals("holiday photo.jpg", offer.name)
        assertEquals("image/jpeg", offer.mimeType)
        assertEquals(4096L, offer.size)
        assertEquals(1_759_999_000_000L, offer.modifiedAtMs)
        assertArrayEquals(bytes, envelope.toByteArray())
    }

    @Test
    fun fileChunkFixtureDecodesAndReEncodes() {
        val bytes = load("file_chunk")
        val envelope = Envelope.parseFrom(bytes)
        assertEquals("fixture-file-chunk", envelope.messageId)
        assertTrue(envelope.hasFileChunk())
        val chunk = envelope.fileChunk
        assertEquals("11111111-2222-3333-4444-555555555555", chunk.transferId)
        assertEquals(1024L, chunk.offset)
        val expected = "droidtosh".toByteArray() + byteArrayOf(0, 0xFF.toByte()) + "chunk".toByteArray()
        assertArrayEquals(expected, chunk.data.toByteArray())
        assertArrayEquals(bytes, envelope.toByteArray())
    }

    @Test
    fun findPhoneRequestFixtureDecodesAndReEncodes() {
        val bytes = load("find_phone_request")
        val envelope = Envelope.parseFrom(bytes)
        assertEquals("fixture-find-phone-request", envelope.messageId)
        assertTrue(envelope.hasFindPhoneRequest())
        assertEquals("find-req-1", envelope.findPhoneRequest.requestId)
        assertEquals(FindAction.FIND_ACTION_START, envelope.findPhoneRequest.action)
        assertArrayEquals(bytes, envelope.toByteArray())
    }

    @Test
    fun findPhoneStateFixtureDecodesAndReEncodes() {
        val bytes = load("find_phone_state")
        val envelope = Envelope.parseFrom(bytes)
        assertEquals("fixture-find-phone-state", envelope.messageId)
        assertTrue(envelope.hasFindPhoneState())
        val state = envelope.findPhoneState
        assertEquals("find-req-1", state.requestId)
        assertFalse(state.ringing)
        assertEquals(FindEndReason.FIND_END_STOPPED_ON_PHONE, state.ended)
        assertArrayEquals(bytes, envelope.toByteArray())
    }
}
