package dev.southstar21.droidtosh.files

import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.Envelope.PayloadCase
import dev.southstar21.droidtosh.protocol.v1.FileChunk
import dev.southstar21.droidtosh.protocol.v1.FileOffer
import dev.southstar21.droidtosh.transport.newEnvelope
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random

class FileTransferEngineTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = 1_000L
    private val chunk = 1024
    private val window = 4L * chunk
    private val progressEvery = 2L * chunk

    private class MemorySource(
        override val name: String,
        val data: ByteArray,
        override val mimeType: String = "application/octet-stream",
    ) : FileSource {
        override val size: Long get() = data.size.toLong()
        override val modifiedAtMs: Long = 0
        var opens = 0
        var closed = false
        override fun open(offset: Long): InputStream {
            opens += 1
            return ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
        }
        override fun close() {
            closed = true
        }
    }

    private class DirStore(val partials: File, val downloads: File, var space: Long = Long.MAX_VALUE) : IncomingFileStore {
        var failCommit = false
        override fun partialFile(transferId: String) = File(partials, "$transferId.part")
        override fun usableSpace() = space
        override fun commit(partial: File, name: String, mimeType: String): SavedFile {
            if (failCommit) throw java.io.IOException("read-only")
            downloads.mkdirs()
            val finalName = FileNames.unique(name) { File(downloads, it).exists() }
            val target = File(downloads, finalName)
            partial.copyTo(target)
            return SavedFile(finalName, target.path)
        }
        override fun deleteOrphans(keep: Set<File>) {
            partials.listFiles()?.filter { it !in keep }?.forEach { it.delete() }
        }
    }

    /** Two engines joined by in-memory queues; nothing moves until the test delivers it. */
    private inner class Link(receiveEnabled: Boolean = true, space: Long = Long.MAX_VALUE) {
        val toMac = ArrayDeque<Envelope>()
        val toPhone = ArrayDeque<Envelope>()
        var phoneCanSend = true
        val finished = mutableListOf<TransferInfo>()
        val resumed = mutableListOf<TransferInfo>()
        val macStore = DirStore(temp.newFolder(), temp.newFolder(), space)
        val listener = object : FileTransferEngine.Listener {
            override fun onFinished(info: TransferInfo) { finished += info }
            override fun onResumed(info: TransferInfo) { resumed += info }
        }
        val phone = engine({ if (phoneCanSend) toMac.addLast(it).let { true } else false }, DirStore(temp.newFolder(), temp.newFolder()))
        val mac = engine({ toPhone.addLast(it); true }, macStore, receiveEnabled)

        private fun engine(send: (Envelope) -> Boolean, store: IncomingFileStore, enabled: Boolean = true) =
            FileTransferEngine(
                send = send,
                store = store,
                receiveEnabled = { enabled },
                clock = { now },
                listener = listener,
                chunkSize = chunk,
                windowBytes = window,
                progressEveryBytes = progressEvery,
                maxFileSize = 1L shl 20,
            )

        fun connect() {
            phone.onConnected()
            mac.onConnected()
        }

        fun disconnect() {
            toMac.clear()
            toPhone.clear()
            phone.onDisconnected()
            mac.onDisconnected()
        }

        /** Pumps and delivers until both sides are idle. */
        fun run(maxRounds: Int = 10_000, onMac: (Envelope) -> Envelope? = { it }) {
            repeat(maxRounds) {
                var moved = false
                while (phone.pump()) moved = true
                while (mac.pump()) moved = true
                while (toMac.isNotEmpty()) {
                    onMac(toMac.removeFirst())?.let(mac::onEnvelope)
                    moved = true
                }
                while (toPhone.isNotEmpty()) {
                    phone.onEnvelope(toPhone.removeFirst())
                    moved = true
                }
                if (!moved) return
            }
            error("transfer did not settle")
        }

        fun saved(name: String): File = File(macStore.downloads, name)
    }

    private fun bytes(size: Int) = Random(size).nextBytes(size)

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)

    @Test
    fun sendsMultiChunkFileIntact() {
        val link = Link()
        link.connect()
        val data = bytes(10 * chunk + 17)
        val source = MemorySource("report.pdf", data)
        val id = link.phone.enqueue(source)
        link.run()
        assertArrayEquals(data, link.saved("report.pdf").readBytes())
        val sent = link.finished.single { it.id == id && it.direction == TransferDirection.OUTGOING }
        assertEquals(TransferStatus.COMPLETED, sent.status)
        assertEquals("report.pdf", sent.saved?.displayName)
        assertTrue(source.closed)
        assertEquals(TransferStatus.COMPLETED, link.finished.single { it.direction == TransferDirection.INCOMING }.status)
    }

    @Test
    fun failsOutgoingFilesWhenTheMacLacksTheCapability() {
        val link = Link()
        val queued = MemorySource("queued.txt", bytes(10))
        val queuedId = link.phone.enqueue(queued)
        link.phone.onConnectedWithoutFiles()
        val later = MemorySource("later.txt", bytes(10))
        val laterId = link.phone.enqueue(later)
        assertFalse(link.phone.pump())
        assertTrue(link.toMac.isEmpty())
        for ((id, source) in listOf(queuedId to queued, laterId to later)) {
            val info = link.finished.single { it.id == id }
            assertEquals(TransferStatus.FAILED, info.status)
            assertEquals(FileTransferEngine.NOT_SUPPORTED, info.error)
            assertTrue(source.closed)
        }
        assertTrue(link.phone.transfers.value.all { it.finished })
        // A later session that has the capability sends normally again.
        link.connect()
        link.phone.enqueue(MemorySource("ok.txt", bytes(10)))
        link.run()
        assertEquals(10, link.saved("ok.txt").length())
    }

    @Test
    fun filesQueuedWhileDisconnectedKeepWaiting() {
        val link = Link()
        link.phone.onDisconnected()
        val id = link.phone.enqueue(MemorySource("wait.txt", bytes(10)))
        assertEquals(TransferStatus.QUEUED, link.phone.transfers.value.single { it.id == id }.status)
        assertTrue(link.finished.isEmpty())
    }

    @Test
    fun sendsEmptyFile() {
        val link = Link()
        link.connect()
        link.phone.enqueue(MemorySource("empty.txt", ByteArray(0)))
        link.run()
        assertEquals(0, link.saved("empty.txt").length())
    }

    @Test
    fun neverExceedsTheWindow() {
        val link = Link()
        link.connect()
        link.phone.enqueue(MemorySource("big.bin", bytes(20 * chunk)))
        // Offer, then accept.
        assertTrue(link.phone.pump())
        link.mac.onEnvelope(link.toMac.removeFirst())
        link.phone.onEnvelope(link.toPhone.removeFirst())
        var sentEnd = 0L
        var acked = 0L
        var maxUnacked = 0L
        var rounds = 0
        while (rounds++ < 1000) {
            while (link.phone.pump()) {
                val sent = link.toMac.last()
                if (sent.hasFileChunk()) sentEnd = maxOf(sentEnd, sent.fileChunk.offset + sent.fileChunk.data.size())
                maxUnacked = maxOf(maxUnacked, sentEnd - acked)
            }
            if (link.toMac.isEmpty()) break
            // One envelope at a time, so progress trickles back as slowly as possible.
            link.mac.onEnvelope(link.toMac.removeFirst())
            while (link.toPhone.isNotEmpty()) {
                val reply = link.toPhone.removeFirst()
                if (reply.hasFileProgress()) acked = reply.fileProgress.received
                link.phone.onEnvelope(reply)
            }
        }
        assertEquals(20L * chunk, sentEnd)
        assertTrue("max unacked $maxUnacked", maxUnacked <= window)
    }

    @Test
    fun windowStopsSendingUntilProgressArrives() {
        val link = Link()
        link.connect()
        link.phone.enqueue(MemorySource("big.bin", bytes(20 * chunk)))
        link.phone.pump()
        link.mac.onEnvelope(link.toMac.removeFirst())
        link.phone.onEnvelope(link.toPhone.removeFirst())
        var chunks = 0
        while (link.phone.pump()) chunks += 1
        assertEquals((window / chunk).toInt(), chunks)
    }

    @Test
    fun resumesAfterReconnectFromWhatTheReceiverHas() {
        val link = Link()
        link.connect()
        val data = bytes(12 * chunk)
        link.phone.enqueue(MemorySource("photo.jpg", data))
        // Let 6 chunks through, then drop the connection.
        var delivered = 0
        link.run(onMac = { envelope ->
            if (envelope.hasFileChunk()) {
                if (delivered >= 6) return@run null
                delivered += 1
            }
            envelope
        })
        link.disconnect()
        val chunkBytesAfterResume = mutableListOf<Long>()
        link.connect()
        link.run(onMac = { envelope ->
            if (envelope.hasFileChunk()) chunkBytesAfterResume += envelope.fileChunk.offset
            envelope
        })
        assertArrayEquals(data, link.saved("photo.jpg").readBytes())
        assertEquals(6L * chunk, chunkBytesAfterResume.first())
        assertEquals(2, link.resumed.size) // once per side
    }

    @Test
    fun duplicateOfferIsAResumeRequest() {
        val link = Link()
        link.connect()
        val offer = newEnvelope {
            setFileOffer(FileOffer.newBuilder().setTransferId("t1").setName("a.txt").setSize(3L * chunk))
        }
        link.mac.onEnvelope(offer)
        assertEquals(0L, link.toPhone.removeFirst().fileAccept.resumeOffset)
        link.mac.onEnvelope(chunkEnvelope("t1", 0, ByteArray(chunk) { 1 }))
        link.toPhone.clear()
        link.mac.onEnvelope(offer)
        assertEquals(chunk.toLong(), link.toPhone.removeFirst().fileAccept.resumeOffset)
    }

    @Test
    fun checksumMismatchIsReportedAndNothingIsSaved() {
        val link = Link()
        link.connect()
        val data = bytes(3 * chunk)
        link.phone.enqueue(MemorySource("x.bin", data))
        link.run(onMac = { envelope ->
            if (envelope.hasFileChunk() && envelope.fileChunk.offset == chunk.toLong()) {
                val corrupted = envelope.fileChunk.data.toByteArray().also { it[0] = (it[0] + 1).toByte() }
                chunkEnvelope(envelope.fileChunk.transferId, envelope.fileChunk.offset, corrupted)
            } else {
                envelope
            }
        })
        val sent = link.finished.single { it.direction == TransferDirection.OUTGOING }
        assertEquals(TransferStatus.FAILED, sent.status)
        assertEquals("checksum_mismatch", sent.error)
        assertFalse(link.saved("x.bin").exists())
        assertTrue(link.macStore.partials.listFiles().isNullOrEmpty())
    }

    @Test
    fun senderCancelRemovesThePartial() {
        val link = Link()
        link.connect()
        val id = link.phone.enqueue(MemorySource("x.bin", bytes(10 * chunk)))
        link.phone.pump()
        link.mac.onEnvelope(link.toMac.removeFirst())
        link.phone.onEnvelope(link.toPhone.removeFirst())
        link.phone.pump()
        link.mac.onEnvelope(link.toMac.removeFirst())
        link.phone.cancel(id)
        link.run()
        assertEquals(TransferStatus.CANCELLED, link.finished.first { it.direction == TransferDirection.INCOMING }.status)
        assertTrue(link.macStore.partials.listFiles().isNullOrEmpty())
        assertFalse(link.phone.pump())
    }

    @Test
    fun receiverCancelStopsTheSender() {
        val link = Link()
        link.connect()
        val source = MemorySource("x.bin", bytes(10 * chunk))
        val id = link.phone.enqueue(source)
        link.phone.pump()
        link.mac.onEnvelope(link.toMac.removeFirst())
        link.phone.onEnvelope(link.toPhone.removeFirst())
        link.mac.cancel(id)
        link.run()
        val sent = link.finished.first { it.direction == TransferDirection.OUTGOING }
        assertEquals(TransferStatus.CANCELLED, sent.status)
        assertEquals("user", sent.error)
        assertTrue(source.closed)
    }

    @Test
    fun rejectsWhenDisabledTooLargeOrNoSpace() {
        val disabled = Link(receiveEnabled = false)
        disabled.connect()
        disabled.phone.enqueue(MemorySource("a", bytes(10)))
        disabled.run()
        assertEquals("disabled", disabled.finished.first { it.direction == TransferDirection.OUTGOING }.error)

        val full = Link(space = 100)
        full.connect()
        full.phone.enqueue(MemorySource("a", bytes(1000)))
        full.run()
        assertEquals("disk_full", full.finished.first { it.direction == TransferDirection.OUTGOING }.error)

        val big = Link()
        big.connect()
        big.mac.onEnvelope(newEnvelope {
            setFileOffer(FileOffer.newBuilder().setTransferId("t").setName("a").setSize((1L shl 20) + 1))
        })
        assertEquals("too_large", big.toPhone.single().fileReject.reason)
    }

    @Test
    fun senderRefusesFilesOverTheLimit() {
        val link = Link()
        val id = link.phone.enqueue(MemorySource("a", ByteArray((1 shl 20) + 1)))
        assertEquals("too_large", link.finished.single { it.id == id }.error)
    }

    @Test
    fun rejectsTransferIdsThatCouldEscapeThePartialDirectory() {
        val link = Link()
        link.mac.onEnvelope(newEnvelope {
            setFileOffer(FileOffer.newBuilder().setTransferId("../../evil").setName("a").setSize(1))
        })
        assertEquals("invalid", link.toPhone.single().fileReject.reason)
    }

    @Test
    fun dropsPartialsAfterRetention() {
        val link = Link()
        link.connect()
        link.mac.onEnvelope(newEnvelope {
            setFileOffer(FileOffer.newBuilder().setTransferId("t").setName("a").setSize(2L * chunk))
        })
        link.mac.onEnvelope(chunkEnvelope("t", 0, ByteArray(chunk)))
        link.disconnect()
        now += FileTransferEngine.RETENTION_MS - 1
        link.mac.tick()
        assertEquals(1, link.macStore.partials.listFiles()!!.size)
        now += 2
        link.mac.tick()
        assertTrue(link.macStore.partials.listFiles().isNullOrEmpty())
        assertEquals("timeout", link.finished.single().error)
    }

    @Test
    fun aGapInTheChunksCancelsTheTransfer() {
        val link = Link()
        link.connect()
        val source = MemorySource("gap.bin", bytes(6 * chunk))
        link.phone.enqueue(source)
        var dropped = false
        link.run(onMac = { envelope ->
            if (!dropped && envelope.hasFileChunk() && envelope.fileChunk.offset == 2L * chunk) {
                dropped = true
                null
            } else {
                envelope
            }
        })
        val sent = link.finished.first { it.direction == TransferDirection.OUTGOING }
        assertEquals(TransferStatus.CANCELLED, sent.status)
        assertEquals("error", sent.error)
        assertTrue(source.closed)
        assertTrue(link.macStore.partials.listFiles().isNullOrEmpty())
    }

    @Test
    fun orphanedPartialsAreDeletedOnlyWhenAskedAndActiveOnesKept() {
        val store = DirStore(temp.newFolder(), temp.newFolder())
        val stale = store.partialFile("old").apply { writeBytes(ByteArray(3)) }
        val engine = FileTransferEngine(send = { true }, store = store, clock = { now }, chunkSize = chunk)
        // Construction doesn't touch storage (it may run on the main thread).
        assertTrue(stale.exists())
        engine.onConnected()
        engine.onEnvelope(newEnvelope {
            setFileOffer(FileOffer.newBuilder().setTransferId("t").setName("a").setSize(2L * chunk))
        })
        engine.onEnvelope(chunkEnvelope("t", 0, ByteArray(chunk) { 1 }))
        engine.deleteOrphanedPartials()
        assertFalse(stale.exists())
        assertEquals(chunk.toLong(), store.partialFile("t").length())
    }

    @Test
    fun aFolderIsListedAsCompressingThenSentZippedAndItsArchiveDeleted() {
        val link = Link()
        val folder = File(temp.newFolder(), "Docs")
        File(folder, "sub").mkdirs()
        File(folder, "sub/a.txt").writeText("hello")
        val work = temp.newFolder()
        var seenWhileZipping: List<TransferInfo> = emptyList()
        val root = object : FolderNode by LocalFolderNode(folder) {
            override fun children(): List<FolderNode> {
                seenWhileZipping = link.phone.transfers.value
                return LocalFolderNode(folder).children()
            }
        }
        val id = FolderSender.send(link.phone, root, work)
        assertEquals(TransferStatus.COMPRESSING, seenWhileZipping.single().status)
        assertEquals("Docs.zip", seenWhileZipping.single().name)
        assertEquals(id, seenWhileZipping.single().id)
        assertEquals(TransferStatus.QUEUED, link.phone.transfers.value.single { it.id == id }.status)
        link.connect()
        link.run()
        assertEquals(TransferStatus.COMPLETED, link.finished.single { it.id == id && it.direction == TransferDirection.OUTGOING }.status)
        java.util.zip.ZipFile(link.saved("Docs.zip")).use { zip ->
            assertEquals("hello", zip.getInputStream(zip.getEntry("Docs/sub/a.txt")).readBytes().decodeToString())
        }
        assertTrue("archive deleted", work.listFiles().isNullOrEmpty())
    }

    @Test
    fun aFolderCancelledWhileCompressingLeavesNothing() {
        val link = Link()
        val folder = File(temp.newFolder(), "Docs").apply { mkdirs() }
        File(folder, "a.txt").writeText("hello")
        val work = temp.newFolder()
        val root = object : FolderNode by LocalFolderNode(folder) {
            override fun children(): List<FolderNode> {
                link.phone.cancel(link.phone.transfers.value.single().id)
                return LocalFolderNode(folder).children()
            }
        }
        val id = FolderSender.send(link.phone, root, work)
        val info = link.phone.transfers.value.single()
        assertEquals(id, info.id)
        assertEquals(TransferStatus.CANCELLED, info.status)
        assertTrue(work.listFiles().isNullOrEmpty())
        link.connect()
        link.run()
        assertTrue(link.toMac.isEmpty())
    }

    @Test
    fun aFolderOverTheLimitFailsClearly() {
        val link = Link()
        val folder = File(temp.newFolder(), "Big").apply { mkdirs() }
        File(folder, "a.bin").writeBytes(Random(1).nextBytes(10_000))
        val work = temp.newFolder()
        val id = FolderSender.send(link.phone, LocalFolderNode(folder), work, maxSize = 5_000)
        val info = link.finished.single { it.id == id }
        assertEquals(TransferStatus.FAILED, info.status)
        assertEquals(FolderSender.ZIP_TOO_LARGE, info.error)
        assertTrue(work.listFiles().isNullOrEmpty())
    }

    @Test
    fun duplicateChunksAreIgnored() {
        val link = Link()
        link.mac.onEnvelope(newEnvelope {
            setFileOffer(FileOffer.newBuilder().setTransferId("t").setName("a").setSize(2L * chunk))
        })
        val first = chunkEnvelope("t", 0, ByteArray(chunk) { 1 })
        link.mac.onEnvelope(first)
        link.mac.onEnvelope(first)
        link.mac.onEnvelope(chunkEnvelope("t", chunk.toLong(), ByteArray(chunk) { 2 }))
        assertEquals(2L * chunk, link.macStore.partialFile("t").length())
    }

    @Test
    fun anEndBeforeAllBytesArrivedWaitsForTheReoffer() {
        val link = Link()
        link.connect()
        val data = bytes(4 * chunk)
        link.phone.enqueue(MemorySource("late.bin", data))
        // Lose the last chunk, as a dropped session would.
        link.run(onMac = { envelope ->
            if (envelope.hasFileChunk() && envelope.fileChunk.offset == 3L * chunk) null else envelope
        })
        assertTrue(link.finished.isEmpty())
        assertEquals(3L * chunk, link.macStore.partialFile(link.phone.transfers.value.first().id).length())
        link.disconnect()
        link.connect()
        link.run()
        assertArrayEquals(data, link.saved("late.bin").readBytes())
    }

    @Test
    fun extraAcceptsAreIgnoredWhileStreaming() {
        val link = Link()
        link.connect()
        val data = bytes(6 * chunk)
        val id = link.phone.enqueue(MemorySource("a.bin", data))
        link.phone.pump()
        link.mac.onEnvelope(link.toMac.removeFirst())
        val accept = link.toPhone.removeFirst()
        link.phone.onEnvelope(accept)
        repeat(3) { assertTrue(link.phone.pump()) }
        link.phone.onEnvelope(accept) // a stale answer to a retransmitted offer
        link.phone.pump()
        assertEquals(3L * chunk, link.toMac.last().fileChunk.offset)
        link.run()
        assertEquals(TransferStatus.COMPLETED, link.finished.single { it.id == id && it.direction == TransferDirection.OUTGOING }.status)
    }

    @Test
    fun aReofferAfterCompletionGetsTheStoredCompletion() {
        val link = Link()
        link.connect()
        link.phone.enqueue(MemorySource("a", bytes(2 * chunk)))
        var offer: Envelope? = null
        link.run(onMac = { envelope ->
            if (envelope.hasFileOffer()) offer = envelope
            envelope
        })
        link.mac.onEnvelope(offer!!)
        val reply = link.toPhone.single()
        assertTrue(reply.fileComplete.ok)
    }

    @Test
    fun aReofferWithADifferentSizeStartsOver() {
        val link = Link()
        link.mac.onEnvelope(newEnvelope {
            setFileOffer(FileOffer.newBuilder().setTransferId("t").setName("a").setSize(2L * chunk))
        })
        link.mac.onEnvelope(chunkEnvelope("t", 0, ByteArray(chunk)))
        link.toPhone.clear()
        link.mac.onEnvelope(newEnvelope {
            setFileOffer(FileOffer.newBuilder().setTransferId("t").setName("a").setSize(3L * chunk))
        })
        assertEquals(0L, link.toPhone.single().fileAccept.resumeOffset)
        assertEquals(0L, link.macStore.partialFile("t").length())
    }

    @Test
    fun aSourceThatChangedSizeFailsOnResume() {
        val link = Link()
        link.connect()
        var size = 4L * chunk
        val source = object : FileSource {
            val data = bytes(4 * chunk)
            override val name = "grows.log"
            override val mimeType = ""
            override val size = data.size.toLong()
            override val modifiedAtMs = 0L
            override fun open(offset: Long): InputStream = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
            override fun currentSize() = size
        }
        val id = link.phone.enqueue(source)
        link.run(onMac = { envelope -> if (envelope.hasFileChunk() && envelope.fileChunk.offset >= chunk) null else envelope })
        link.disconnect()
        size += 10
        link.connect()
        link.run()
        assertEquals("source_changed", link.finished.first { it.id == id && it.direction == TransferDirection.OUTGOING }.error)
        assertEquals(TransferStatus.CANCELLED, link.finished.first { it.direction == TransferDirection.INCOMING }.status)
    }

    @Test
    fun aFailedSaveEndsWithAFailedCompletion() {
        val link = Link()
        link.macStore.failCommit = true
        link.connect()
        link.phone.enqueue(MemorySource("a.bin", bytes(3 * chunk)))
        link.run()
        val sent = link.finished.first { it.direction == TransferDirection.OUTGOING }
        assertEquals("write_failed", sent.error)
        assertTrue(link.macStore.partials.listFiles().isNullOrEmpty())
    }

    @Test
    fun retriesAChunkTheTransportHadNoRoomFor() {
        val link = Link()
        link.connect()
        val data = bytes(3 * chunk)
        link.phone.enqueue(MemorySource("retry.bin", data))
        link.phone.pump()
        link.mac.onEnvelope(link.toMac.removeFirst())
        link.phone.onEnvelope(link.toPhone.removeFirst())
        link.phoneCanSend = false
        assertTrue(link.phone.pump()) // read the chunk, couldn't send it
        assertFalse(link.phone.pump())
        link.phoneCanSend = true
        link.run()
        assertArrayEquals(data, link.saved("retry.bin").readBytes())
    }

    @Test
    fun collidingNamesGetSuffixes() {
        val link = Link()
        link.connect()
        link.phone.enqueue(MemorySource("same.txt", bytes(5)))
        link.phone.enqueue(MemorySource("same.txt", bytes(6)))
        link.run()
        assertTrue(link.saved("same.txt").exists())
        assertTrue(link.saved("same (1).txt").exists())
        assertEquals(
            "same (1).txt",
            link.finished.last { it.direction == TransferDirection.OUTGOING }.saved?.displayName,
        )
    }

    @Test
    fun queuesFilesOneAtATime() {
        val link = Link()
        link.connect()
        link.phone.enqueue(MemorySource("one", bytes(3 * chunk)))
        link.phone.enqueue(MemorySource("two", bytes(3 * chunk)))
        val offers = mutableListOf<String>()
        link.run(onMac = { envelope ->
            if (envelope.hasFileOffer()) offers += envelope.fileOffer.name
            if (envelope.hasFileOffer() && envelope.fileOffer.name == "two") {
                assertTrue(link.saved("one").exists())
            }
            envelope
        })
        assertEquals(listOf("one", "two"), offers)
    }

    @Test
    fun nothingIsSentWhileDisconnected() {
        val link = Link()
        link.phone.enqueue(MemorySource("a", bytes(10)))
        assertFalse(link.phone.pump())
        assertEquals(TransferStatus.QUEUED, link.phone.transfers.value.single().status)
        assertNull(link.toMac.firstOrNull())
    }

    @Test
    fun repliesToARetransmittedEndWithTheSameCompletion() {
        val link = Link()
        link.connect()
        val data = bytes(2 * chunk)
        link.phone.enqueue(MemorySource("a", data))
        var end: Envelope? = null
        link.run(onMac = { envelope ->
            if (envelope.payloadCase == PayloadCase.FILE_END) end = envelope
            envelope
        })
        link.mac.onEnvelope(end!!)
        val reply = link.toPhone.single()
        assertTrue(reply.fileComplete.ok)
        assertEquals("a", reply.fileComplete.savedAs)
    }

    private fun chunkEnvelope(id: String, offset: Long, data: ByteArray) = newEnvelope {
        setFileChunk(FileChunk.newBuilder().setTransferId(id).setOffset(offset).setData(ByteString.copyFrom(data)))
    }
}
