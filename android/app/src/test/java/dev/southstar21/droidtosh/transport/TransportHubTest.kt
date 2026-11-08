package dev.southstar21.droidtosh.transport

import dev.southstar21.droidtosh.protocol.v1.CallAction
import dev.southstar21.droidtosh.protocol.v1.ClipboardBlobChunk
import dev.southstar21.droidtosh.protocol.v1.DeviceStatus
import dev.southstar21.droidtosh.protocol.v1.DialRequest
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.FindPhoneRequest
import dev.southstar21.droidtosh.protocol.v1.FindPhoneState
import dev.southstar21.droidtosh.protocol.v1.Ping
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransportHubTest {
    private val psk = ByteArray(32) { 0x42 }
    private fun client(stream: DuplexStream) = ProtocolClient(stream, psk) { ByteArray(32) { 0x01 } }

    @Test
    fun sendForwardsFrameToAttachedClient() = runBlocking {
        val stream = FakeDuplexStream()
        TransportHub.attach(client(stream))
        val ping = newEnvelope { setPing(Ping.newBuilder().setSentAtMs(7)) }

        assertTrue(TransportHub.send(ping))
        withTimeout(2_000) {
            while (stream.written.isEmpty()) delay(10)
        }
        assertEquals(1, stream.written.size)
        assertTrue(Envelope.parseFrom(stream.written[0]).hasPing())
        TransportHub.detach()
    }

    @Test
    fun sendReturnsFalseWhenDetached() {
        TransportHub.detach()
        assertFalse(TransportHub.send(newEnvelope { setPing(Ping.getDefaultInstance()) }))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun dispatchRoutesToTheFeatureInbox() = runTest {
        TransportHub.attach(client(FakeDuplexStream()))
        var received: Envelope? = null
        val job = launch { received = TransportHub.incoming(InboundFeature.CALLS).first() }
        runCurrent()
        assertTrue(TransportHub.dispatch(dial("123")))
        job.join()
        assertEquals("123", received?.dialRequest?.number)
        TransportHub.detach()
    }

    @Test
    fun routesCallActionsByCallKind() {
        assertEquals(InboundFeature.CALLS, InboundFeature.of(callAction("cellular:abc")))
        assertEquals(InboundFeature.NOTIFICATIONS, InboundFeature.of(callAction("voip:0|pkg|1")))
        assertEquals(null, InboundFeature.of(newEnvelope { setPing(Ping.getDefaultInstance()) }))
        assertFalse(TransportHub.dispatch(newEnvelope { setPing(Ping.getDefaultInstance()) }))
    }

    @Test
    fun stalledFeatureDoesNotBlockOrDropOthers() = runBlocking {
        TransportHub.attach(client(FakeDuplexStream()))
        // Nobody collects CALLS: overfill it while a clipboard burst flows.
        repeat(InboundFeature.CALLS.capacity + 10) { TransportHub.dispatch(dial("$it")) }
        val received = mutableListOf<Int>()
        val collector = launch {
            TransportHub.incoming(InboundFeature.CLIPBOARD).collect { received += it.clipboardBlobChunk.offset }
        }
        repeat(200) { offset ->
            assertTrue(TransportHub.dispatch(chunk(offset)))
            if (offset % 50 == 0) delay(5)
        }
        withTimeout(2_000) {
            while (received.size < 200) delay(10)
        }
        collector.cancel()
        assertEquals((0 until 200).toList(), received)
        assertTrue(TransportHub.droppedInbound(InboundFeature.CALLS) >= 10)
        TransportHub.detach()
    }

    @Test
    fun attachClearsInboxesFromThePreviousSession() = runBlocking {
        TransportHub.attach(client(FakeDuplexStream()))
        TransportHub.dispatch(dial("stale"))
        TransportHub.attach(client(FakeDuplexStream()))
        TransportHub.dispatch(dial("fresh"))
        val first = withTimeout(2_000) { TransportHub.incoming(InboundFeature.CALLS).first() }
        assertEquals("fresh", first.dialRequest.number)
        TransportHub.detach()
    }

    @Test
    fun routesFindRequestsToTheDeviceInboxAndGatesStatus() {
        val find = newEnvelope { setFindPhoneRequest(FindPhoneRequest.newBuilder().setRequestId("r")) }
        assertEquals(InboundFeature.DEVICE, InboundFeature.of(find))
        assertEquals(
            Capabilities.PHONE_STATUS,
            Capabilities.requiredFor(newEnvelope { setDeviceStatus(DeviceStatus.getDefaultInstance()) }),
        )
        assertEquals(
            Capabilities.FIND,
            Capabilities.requiredFor(newEnvelope { setFindPhoneState(FindPhoneState.getDefaultInstance()) }),
        )
        assertTrue(Capabilities.PHONE_STATUS in Capabilities.SUPPORTED)
        assertTrue(Capabilities.FIND in Capabilities.SUPPORTED)
    }

    @Test
    fun readySessionStartsAfterResumeAndIsNewForEverySession() {
        val first = client(FakeDuplexStream())
        TransportHub.attach(first, ReliableStream(), setOf(Capabilities.PHONE_STATUS), ready = false)
        assertEquals(null, TransportHub.readySession.value)
        TransportHub.markReady(first)
        val session = TransportHub.readySession.value
        assertEquals(setOf(Capabilities.PHONE_STATUS), session?.capabilities)

        TransportHub.attach(client(FakeDuplexStream()))
        val next = TransportHub.readySession.value
        assertTrue(next != null && next.id != session!!.id)
        TransportHub.detach()
        assertEquals(null, TransportHub.readySession.value)
    }

    private fun dial(number: String) = newEnvelope { setDialRequest(DialRequest.newBuilder().setNumber(number)) }

    private fun callAction(callId: String) = newEnvelope { setCallAction(CallAction.newBuilder().setCallId(callId)) }

    private fun chunk(offset: Int) = newEnvelope {
        setClipboardBlobChunk(ClipboardBlobChunk.newBuilder().setTransferId("t").setOffset(offset))
    }

    @Test
    fun detachOfStaleClientKeepsNewerSessionAttached() = runBlocking {
        val stale = client(FakeDuplexStream())
        val stream = FakeDuplexStream()
        val current = client(stream)
        TransportHub.attach(stale)
        TransportHub.attach(current)

        TransportHub.detach(stale)

        assertTrue(TransportHub.isConnected.value)
        assertTrue(TransportHub.send(newEnvelope { setPing(Ping.newBuilder().setSentAtMs(3)) }))
        withTimeout(2_000) {
            while (stream.written.isEmpty()) delay(10)
        }
        TransportHub.detach(current)
        assertFalse(TransportHub.isConnected.value)
    }

    @Test
    fun sendDropsPayloadsWhoseCapabilityWasNotNegotiated() {
        TransportHub.attach(client(FakeDuplexStream()), capabilities = setOf(Capabilities.NOTIFICATIONS))
        assertFalse(TransportHub.send(clipboardText("x")))
        assertTrue(TransportHub.send(newEnvelope { setPing(Ping.getDefaultInstance()) }))
        assertEquals(setOf(Capabilities.NOTIFICATIONS), TransportHub.capabilities.value)
        TransportHub.detach()
        assertEquals(emptySet<String>(), TransportHub.capabilities.value)
    }

    @Test
    fun featureTrafficWaitsForMarkReady() = runBlocking {
        val stream = FakeDuplexStream()
        val session = client(stream)
        TransportHub.attach(session, ready = false)
        assertTrue(TransportHub.send(newEnvelope { setPing(Ping.getDefaultInstance()) }))
        delay(50)
        assertTrue(stream.written.isEmpty())
        TransportHub.markReady(session)
        withTimeout(2_000) {
            while (stream.written.isEmpty()) delay(10)
        }
        TransportHub.detach()
    }

    @Test
    fun reliableEnvelopesAreHeldWhileDisconnected() {
        val reliable = ReliableStream(clock = { 0L })
        TransportHub.attach(client(FakeDuplexStream()), reliable, ready = false)
        TransportHub.send(clipboardText("queued"))
        TransportHub.detach()
        assertTrue(TransportHub.send(clipboardText("offline")))
        assertFalse(TransportHub.send(newEnvelope { setPing(Ping.getDefaultInstance()) }))
        val pending = reliable.onPeerResume(
            dev.southstar21.droidtosh.protocol.v1.Resume.newBuilder().setStreamId("mac").setPeerStreamId(reliable.streamId).build()
        )
        assertEquals(listOf("queued", "offline"), pending.map { it.clipboardText.text })
        TransportHub.releaseStream(reliable)
        assertFalse(TransportHub.send(clipboardText("gone")))
    }

    private fun clipboardText(text: String) = newEnvelope {
        setClipboardText(dev.southstar21.droidtosh.protocol.v1.ClipboardText.newBuilder().setText(text))
    }

    @Test
    fun isConnectedReflectsAttachAndDetach() {
        TransportHub.detach()
        assertFalse(TransportHub.isConnected.value)
        TransportHub.attach(client(FakeDuplexStream()))
        assertTrue(TransportHub.isConnected.value)
        TransportHub.detach()
        assertFalse(TransportHub.isConnected.value)
    }
}
