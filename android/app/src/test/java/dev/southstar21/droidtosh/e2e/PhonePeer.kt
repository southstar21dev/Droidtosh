package dev.southstar21.droidtosh.e2e

import dev.southstar21.droidtosh.clipboard.ClipboardAssembler
import dev.southstar21.droidtosh.clipboard.ClipboardChunker
import dev.southstar21.droidtosh.data.PairingCredentials
import dev.southstar21.droidtosh.device.Alarm
import dev.southstar21.droidtosh.device.DeviceStatusEngine
import dev.southstar21.droidtosh.device.DeviceStatusRunner
import dev.southstar21.droidtosh.device.DeviceStatusSource
import dev.southstar21.droidtosh.device.FindPhoneController
import dev.southstar21.droidtosh.device.FindPhoneRunner
import dev.southstar21.droidtosh.device.PhoneStatusSnapshot
import dev.southstar21.droidtosh.files.FileNames
import dev.southstar21.droidtosh.files.FileSource
import dev.southstar21.droidtosh.files.FileTransferEngine
import dev.southstar21.droidtosh.files.FileTransferRunner
import dev.southstar21.droidtosh.files.FolderSender
import dev.southstar21.droidtosh.files.IncomingFileStore
import dev.southstar21.droidtosh.files.LocalFolderNode
import dev.southstar21.droidtosh.files.SavedFile
import dev.southstar21.droidtosh.files.TransferDirection
import dev.southstar21.droidtosh.files.TransferInfo
import dev.southstar21.droidtosh.files.TransferStatus
import dev.southstar21.droidtosh.notifications.SnapshotBudget
import dev.southstar21.droidtosh.protocol.v1.ActionInfo
import dev.southstar21.droidtosh.protocol.v1.ActionResult
import dev.southstar21.droidtosh.protocol.v1.ChargeSource
import dev.southstar21.droidtosh.protocol.v1.ClipboardBlobChunk
import dev.southstar21.droidtosh.protocol.v1.ClipboardBlobEnd
import dev.southstar21.droidtosh.protocol.v1.ClipboardBlobStart
import dev.southstar21.droidtosh.protocol.v1.ClipboardText
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.Hello
import dev.southstar21.droidtosh.protocol.v1.NetworkType
import dev.southstar21.droidtosh.protocol.v1.NotificationRecord
import dev.southstar21.droidtosh.protocol.v1.RingerMode
import dev.southstar21.droidtosh.protocol.v1.SnapshotReply
import dev.southstar21.droidtosh.transport.Capabilities
import dev.southstar21.droidtosh.transport.ConnectionState
import dev.southstar21.droidtosh.transport.InboundFeature
import dev.southstar21.droidtosh.transport.PairingPayloadParser
import dev.southstar21.droidtosh.transport.PhoneConnectionLoop
import dev.southstar21.droidtosh.transport.ReconnectPolicy
import dev.southstar21.droidtosh.transport.ReliableStream
import dev.southstar21.droidtosh.transport.TransportHub
import dev.southstar21.droidtosh.transport.TransportLog
import dev.southstar21.droidtosh.transport.WaitModePolicy
import dev.southstar21.droidtosh.transport.newEnvelope
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Headless phone peer for the end-to-end harness (e2e/). It runs the real [PhoneConnectionLoop]
 * (relay TLS, inner TLS, handshake, Resume, reliable stream, backoff, Mac-asleep wait) and the real
 * [TransportHub] on a plain JVM, standing in for the Android features with scripted replies.
 * Line-delimited JSON: commands on stdin, events on stdout. Test tooling only.
 *
 * Environment: optional E2E_TTL_MS (reliable outbox TTL), E2E_WAIT_MS (Mac-asleep / WAIT probe
 * delay), E2E_BACKOFF_MAX_MS (reconnect backoff cap), E2E_FILES_DIR (received files go to
 * <dir>/received; a temporary directory by default), E2E_FILE_STALL_S (file transfer stall timeout),
 * E2E_DISABLE_CAPS (comma-separated capabilities not to advertise), E2E_STATUS_COALESCE_MS,
 * E2E_STATUS_FAST_MS and E2E_STATUS_HEARTBEAT_MS (phone status timers), E2E_FIND_RING_MS and
 * E2E_FIND_STALE_MS (Find my phone ring length and stale cutoff).
 * File commands and events match the Mac peer's: send_file / send_folder / cancel_file, file_queued,
 * folder_zipped, folder_failed, file_received, file_complete. Phone status and Find my phone run the real engines with a fake
 * source and alarm: device_set, find_alarm_mode, find_set, find_stop_on_phone; device_status_sent,
 * alarm_started, alarm_stopped, find_state_sent.
 */
object PhonePeer {
    private val env = System.getenv()
    private val out = System.out
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val waitSignal = Channel<Unit>(Channel.CONFLATED)
    private val reliableStream = ReliableStream(ttlMs = env["E2E_TTL_MS"]?.toLongOrNull() ?: ReliableStream.TTL_MS)
    private val connectAttempts = AtomicInteger()
    private val waitingStates = AtomicInteger()

    @Volatile
    private var credentials: PairingCredentials? = null

    @Volatile
    private var loop: PhoneConnectionLoop? = null
    private var loopJob: Job? = null

    @Volatile
    private var snapshotSpec = SnapshotSpec(count = 0, iconBytes = 0, textBytes = 0)

    @Volatile
    private var lastState = ""

    @Volatile
    private var lastPath = ""

    /** Capabilities to advertise; E2E_DISABLE_CAPS (comma-separated) removes some, to test gating. */
    private val advertisedCapabilities: List<String> = run {
        val disabled = env["E2E_DISABLE_CAPS"].orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        Capabilities.SUPPORTED.filter { it !in disabled }
    }

    private data class SnapshotSpec(val count: Int, val iconBytes: Int, val textBytes: Int)

    @JvmStatic
    fun main(args: Array<String>) {
        TransportLog.sink = TransportLog.Sink { priority, tag, message, error ->
            System.err.println("$priority/$tag: $message${error?.let { " ($it)" } ?: ""}")
        }
        InboundFeature.entries.filter { it != InboundFeature.FILES && it != InboundFeature.DEVICE }.forEach { feature ->
            scope.launch { TransportHub.incoming(feature).collect(::handleIncoming) }
        }
        fileRunner.start(scope)
        statusRunner.start(scope)
        findRunner.start(scope)
        emit("ready")
        while (true) {
            val line = readlnOrNull() ?: break
            val command = try {
                Json.parseObject(line)
            } catch (error: Exception) {
                emit("error", "message" to "bad command: ${error.message}")
                continue
            }
            runCatching { run(command) }.onFailure { emit("error", "message" to "${it.javaClass.simpleName}: ${it.message}") }
        }
        stopLoop()
        exitProcess(0)
    }

    private fun run(command: Map<String, Any?>) {
        when (val name = command["cmd"] as? String) {
            "pair" -> pair(command["uri"] as String)
            "unpair" -> {
                stopLoop()
                credentials = null
            }
            "setSnapshot" -> snapshotSpec = SnapshotSpec(
                count = command.int("count"),
                iconBytes = command.int("iconBytes"),
                textBytes = command.int("textBytes"),
            )
            "actionResult" -> send(newEnvelope {
                setActionResult(ActionResult.newBuilder().setRequestId(command["requestId"] as String).setOk(true))
            })
            "clipboardText" -> send(newEnvelope {
                setClipboardText(ClipboardText.newBuilder().setText(command["text"] as String))
            })
            "sendImage" -> sendImage(command.int("size"), command.int("seed").toLong())
            "send_file" -> sendFile(command["path"] as String, command["name"] as? String)
            "send_folder" -> sendFolder(command["path"] as String)
            "cancel_file" -> {
                files.cancel(command["id"] as String)
                fileRunner.poke()
            }
            "device_set" -> {
                deviceSource.current = applyDeviceFields(deviceSource.current, command)
                statusRunner.poke()
            }
            "find_alarm_mode" -> alarm.works = when (val mode = command["mode"]) {
                "ok" -> true
                "fail" -> false
                else -> error("unknown alarm mode $mode")
            }
            "find_set" -> findEnabled = command["enabled"] as? Boolean ?: error("find_set needs enabled")
            "find_stop_on_phone" -> {
                find.stopOnPhone()
                findRunner.poke()
            }
            "stats" -> {
                val stats = reliableStream.stats()
                emit(
                    "stats",
                    "outbox" to stats.outboxSize,
                    "evicted" to stats.evicted,
                    "expired" to stats.expired,
                    "gaps" to stats.gaps,
                    "duplicates" to stats.duplicates,
                    "retransmits" to stats.retransmits,
                    "connectAttempts" to connectAttempts.get(),
                    "waits" to waitingStates.get(),
                    "connected" to TransportHub.isConnected.value,
                    "capabilities" to TransportHub.capabilities.value.sorted(),
                    "state" to lastState,
                    "path" to lastPath,
                )
            }
            "quit" -> {
                stopLoop()
                emit("bye")
                exitProcess(0)
            }
            else -> emit("error", "message" to "unknown command $name")
        }
    }

    private fun pair(uri: String) {
        stopLoop()
        val payload = try {
            PairingPayloadParser.parse(uri)
        } catch (error: IllegalArgumentException) {
            emit("pairingRejected", "message" to (error.message ?: error.javaClass.simpleName))
            return
        }
        credentials = PairingCredentials(
            host = payload.host,
            port = payload.port,
            room = payload.room,
            fingerprint = payload.fingerprint,
            relayFingerprint = payload.relayFingerprint,
            psk = payload.psk,
            mode = payload.mode,
            localHost = payload.localHost,
            localPort = payload.localPort,
        )
        val connection = PhoneConnectionLoop(
            host = loopHost,
            scope = scope,
            reliableStream = reliableStream,
            waitPolicy = WaitModePolicy(fcmEnabled = false, overrideMs = env["E2E_WAIT_MS"]?.toLongOrNull()),
            waitSignal = waitSignal,
            reconnectPolicy = ReconnectPolicy(maxDelayMs = env["E2E_BACKOFF_MAX_MS"]?.toLongOrNull() ?: 30_000),
            capabilities = advertisedCapabilities,
        )
        loop = connection
        loopJob = scope.launch { connection.run() }
        emit("paired", "host" to payload.host, "port" to payload.port, "mode" to payload.mode.wire)
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        loop?.activeClient?.let { runCatching { it.close() } }
        loop = null
    }

    private val loopHost = object : PhoneConnectionLoop.Host {
        override fun loadCredentials(): PairingCredentials? = credentials
        override fun fcmToken(): String? = null
        override fun deviceName(): String = "E2E Phone"
        override fun hello(deviceName: String): Hello = Hello.newBuilder()
            .setDeviceName(deviceName)
            .setAndroidVersion("jvm")
            .setAppVersion("e2e")
            .addAllCapabilities(advertisedCapabilities)
            .build()
        override fun onState(state: ConnectionState, statusText: String?) {
            val label = when (state) {
                ConnectionState.NotPaired -> "notPaired"
                ConnectionState.Connecting -> "connecting".also { connectAttempts.incrementAndGet() }
                is ConnectionState.Connected -> "connected"
                is ConnectionState.Disconnected -> "disconnected".also {
                    if (state.reason == "Waiting for Mac") waitingStates.incrementAndGet()
                }
            }
            lastState = label
            // The default localCandidates (the pairing's lhost:lport) is the peer's only local candidate.
            lastPath = (state as? ConnectionState.Connected)?.path?.label ?: ""
            val detail = (state as? ConnectionState.Disconnected)?.reason ?: ""
            emit("state", "state" to label, "detail" to detail, "path" to lastPath)
        }
        override fun onNotPaired() = Unit
        override val isDestroyed: Boolean get() = false
    }

    private fun send(envelope: Envelope) {
        val accepted = TransportHub.send(envelope)
        if (!accepted) emit("sendRejected", "type" to envelope.payloadCase.name)
    }

    // File transfer: the real engine and runner, saving into a plain directory instead of MediaStore.
    private val filesDir: File = File(
        env["E2E_FILES_DIR"]?.let(::File) ?: kotlin.io.path.createTempDirectory("phone-peer-files").toFile(),
        "received",
    )

    private val fileStore = object : IncomingFileStore {
        private val partials = File(filesDir.parentFile, ".partial")
        override fun partialFile(transferId: String) = File(partials, "$transferId.part")
        // Like the app's store: usableSpace is 0 for a directory that doesn't exist yet.
        override fun usableSpace() = filesDir.also { it.mkdirs() }.usableSpace
        override fun commit(partial: File, name: String, mimeType: String): SavedFile {
            filesDir.mkdirs()
            val target = File(filesDir, FileNames.unique(name) { File(filesDir, it).exists() })
            partial.copyTo(target)
            return SavedFile(target.name, target.path)
        }
        override fun deleteOrphans(keep: Set<File>) {
            partials.listFiles()?.filter { it !in keep }?.forEach { it.delete() }
        }
    }

    private val files = FileTransferEngine(
        send = TransportHub::send,
        store = fileStore,
        stallMs = env["E2E_FILE_STALL_S"]?.toLongOrNull()?.times(1_000) ?: FileTransferEngine.STALL_MS,
        listener = object : FileTransferEngine.Listener {
            override fun onFinished(info: TransferInfo) = onFileFinished(info)
            override fun onResumed(info: TransferInfo) {
                emit("file_resumed", "id" to info.id, "direction" to info.direction.name.lowercase(), "offset" to info.transferred)
            }
        },
    )
    private val fileRunner = FileTransferRunner(files.also { it.deleteOrphanedPartials() })

    private class PathSource(private val file: File, offeredName: String?) : FileSource {
        override val name: String = offeredName ?: file.name
        override val mimeType: String = ""
        override val size: Long = file.length()
        override val modifiedAtMs: Long = file.lastModified()
        override fun open(offset: Long): InputStream = FileInputStream(file).also { it.channel.position(offset) }
        override fun currentSize(): Long = file.length()
    }

    private fun sendFile(path: String, name: String?) {
        val file = File(path)
        if (!file.isFile) {
            emit("error", "message" to "no such file $path")
            return
        }
        val id = files.enqueue(PathSource(file, name))
        fileRunner.poke()
        emit("file_queued", "id" to id, "path" to path, "size" to file.length())
    }

    /** Zips [path] with the app's [FolderSender] on an I/O thread, as the app does, and sends it. */
    private fun sendFolder(path: String) {
        val folder = File(path)
        if (!folder.isDirectory) {
            emit("error", "message" to "no such folder $path")
            return
        }
        val work = File(filesDir.parentFile, "zips")
        scope.launch {
            val id = FolderSender.send(files, LocalFolderNode(folder), work) { id, archive ->
                emit(
                    "folder_zipped",
                    "id" to id,
                    "name" to archive.name,
                    "archive" to archive.path,
                    "sha256" to sha256(archive),
                    "size" to archive.length(),
                )
            }
            files.transfers.value.firstOrNull { it.id == id && it.status == TransferStatus.FAILED }
                ?.let { emit("folder_failed", "id" to id, "error" to it.error) }
            fileRunner.poke()
        }
    }

    private fun onFileFinished(info: TransferInfo) {
        val saved = info.saved
        if (info.direction == TransferDirection.INCOMING && info.status == TransferStatus.COMPLETED && saved != null) {
            val file = File(saved.location)
            emit(
                "file_received",
                "id" to info.id,
                "path" to file.path,
                "savedAs" to saved.displayName,
                "sha256" to sha256(file),
                "size" to file.length(),
            )
        }
        emit(
            "file_complete",
            "id" to info.id,
            "direction" to info.direction.name.lowercase(),
            "ok" to (info.status == TransferStatus.COMPLETED),
            "error" to (info.error ?: ""),
            "savedAs" to (saved?.displayName ?: ""),
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // Phone status and Find my phone: the real engines and runners, a scripted source and alarm.
    private class FakeDeviceSource : DeviceStatusSource {
        @Volatile
        var current = PhoneStatusSnapshot(
            batteryPercent = 80,
            chargeSource = ChargeSource.CHARGE_SOURCE_UNSPECIFIED,
            networkType = NetworkType.NETWORK_TYPE_WIFI,
            wifiLevel = 4,
            ringerMode = RingerMode.RINGER_MODE_NORMAL,
        )
        override fun snapshot() = current
    }

    private val deviceSource = FakeDeviceSource()

    private val statusEngine = DeviceStatusEngine(
        source = deviceSource,
        send = { envelope ->
            TransportHub.send(envelope).also { accepted ->
                if (accepted) emit("device_status_sent", *statusFields(envelope.deviceStatus))
            }
        },
        coalesceMs = env["E2E_STATUS_COALESCE_MS"]?.toLongOrNull() ?: DeviceStatusEngine.COALESCE_MS,
        fastDelayMs = env["E2E_STATUS_FAST_MS"]?.toLongOrNull() ?: DeviceStatusEngine.FAST_DELAY_MS,
        heartbeatMs = env["E2E_STATUS_HEARTBEAT_MS"]?.toLongOrNull() ?: DeviceStatusEngine.HEARTBEAT_MS,
    )
    private val statusRunner = DeviceStatusRunner(statusEngine)

    private class FakeAlarm(private val emit: (String) -> Unit) : Alarm {
        @Volatile
        var works = true
        override fun start(): Boolean {
            if (!works) return false
            emit("alarm_started")
            return true
        }
        override fun stop() = emit("alarm_stopped")
    }

    private val alarm = FakeAlarm { emit(it) }

    /** The "Allow Find my phone" setting; find_set toggles it. */
    @Volatile
    private var findEnabled = true

    private val find = FindPhoneController(
        send = { envelope ->
            val state = envelope.findPhoneState
            emit(
                "find_state_sent",
                "request_id" to state.requestId,
                "ringing" to state.ringing,
                "ended" to state.ended.name,
                "error" to state.error,
            )
            TransportHub.send(envelope)
        },
        alarm = alarm,
        enabled = { findEnabled },
        ringMs = env["E2E_FIND_RING_MS"]?.toLongOrNull() ?: FindPhoneController.RING_MS,
        staleMs = env["E2E_FIND_STALE_MS"]?.toLongOrNull() ?: FindPhoneController.STALE_MS,
    )
    private val findRunner = FindPhoneRunner(find)

    private fun statusFields(status: dev.southstar21.droidtosh.protocol.v1.DeviceStatus): Array<Pair<String, Any?>> = arrayOf(
        "battery_percent" to status.batteryPercent,
        "charging" to status.charging,
        "charge_source" to status.chargeSource.name,
        "power_save" to status.powerSave,
        "network_type" to status.networkType.name,
        "wifi_level" to status.wifiLevel,
        "cellular_level" to status.cellularLevel,
        "cellular_generation" to status.cellularGeneration,
        "carrier" to status.carrier,
        "airplane_mode" to status.airplaneMode,
        "ringer_mode" to status.ringerMode.name,
        "do_not_disturb" to status.doNotDisturb,
        "sampled_at_ms" to status.sampledAtMs,
    )

    /** Applies any subset of DeviceStatus fields (snake_case; enums by full or short name, or number). */
    private fun applyDeviceFields(base: PhoneStatusSnapshot, command: Map<String, Any?>): PhoneStatusSnapshot {
        var next = base
        for ((key, value) in command) {
            next = when (key) {
                "cmd" -> next
                "battery_percent" -> next.copy(batteryPercent = (value as Number).toInt())
                "charging" -> next.copy(charging = value as Boolean)
                "charge_source" -> next.copy(chargeSource = enumValue(value, "CHARGE_SOURCE_", ChargeSource::valueOf, ChargeSource::forNumber))
                "power_save" -> next.copy(powerSave = value as Boolean)
                "network_type" -> next.copy(networkType = enumValue(value, "NETWORK_TYPE_", NetworkType::valueOf, NetworkType::forNumber))
                "wifi_level" -> next.copy(wifiLevel = (value as Number).toInt())
                "cellular_level" -> next.copy(cellularLevel = (value as Number).toInt())
                "cellular_generation" -> next.copy(cellularGeneration = value as String)
                "carrier" -> next.copy(carrier = value as String)
                "airplane_mode" -> next.copy(airplaneMode = value as Boolean)
                "ringer_mode" -> next.copy(ringerMode = enumValue(value, "RINGER_MODE_", RingerMode::valueOf, RingerMode::forNumber))
                "do_not_disturb" -> next.copy(doNotDisturb = value as Boolean)
                else -> error("unknown device field $key")
            }
        }
        return next
    }

    private fun <T> enumValue(value: Any?, prefix: String, byName: (String) -> T, byNumber: (Int) -> T?): T = when (value) {
        is Number -> byNumber(value.toInt()) ?: error("unknown value $value")
        is String -> value.uppercase().let { byName(if (it.startsWith(prefix)) it else prefix + it) }
        else -> error("bad value $value")
    }

    private val assembler = ClipboardAssembler()

    private fun handleIncoming(envelope: Envelope) {
        when (envelope.payloadCase) {
            Envelope.PayloadCase.CLIPBOARD_BLOB_START -> {
                val start = envelope.clipboardBlobStart
                assembler.start(start.transferId, start.mimeType, start.totalSize)
                return
            }
            Envelope.PayloadCase.CLIPBOARD_BLOB_CHUNK -> {
                val chunk = envelope.clipboardBlobChunk
                val result = assembler.append(chunk.transferId, chunk.offset, chunk.data.toByteArray())
                if (result == ClipboardAssembler.ChunkResult.REJECTED) emit("imageRejected", "transferId" to chunk.transferId)
                return
            }
            Envelope.PayloadCase.CLIPBOARD_BLOB_END -> {
                val data = assembler.end(envelope.clipboardBlobEnd.transferId)
                if (data == null) {
                    emit("imageRejected", "transferId" to envelope.clipboardBlobEnd.transferId)
                } else {
                    emit("imageReceived", "sha256" to sha256(data), "size" to data.size)
                }
                return
            }
            else -> Unit
        }
        val fields = mutableListOf<Pair<String, Any?>>(
            "type" to envelope.payloadCase.name,
            "seq" to envelope.seq,
            "ack" to envelope.ack,
            "messageId" to envelope.messageId,
        )
        when (envelope.payloadCase) {
            Envelope.PayloadCase.NOTIFICATION_ACTION -> fields += "requestId" to envelope.notificationAction.requestId
            Envelope.PayloadCase.CALL_ACTION -> fields += "requestId" to envelope.callAction.requestId
            Envelope.PayloadCase.CLIPBOARD_TEXT -> fields += "text" to envelope.clipboardText.text
            Envelope.PayloadCase.SNAPSHOT_REQUEST -> replySnapshot()
            else -> Unit
        }
        emit("recv", *fields.toTypedArray())
    }

    /** Answers a snapshot request through the same budget the notification listener uses. */
    private fun replySnapshot() {
        val spec = snapshotSpec
        val records = (0 until spec.count).asSequence().map { index ->
            NotificationRecord.newBuilder()
                .setKey("0|com.example.app$index|$index|null|10001")
                .setPackageName("com.example.app$index")
                .setAppLabel("App $index")
                .setTitle("Title $index")
                .setText("Text $index")
                .setBigText("x".repeat(spec.textBytes))
                .setPostedAtMs(System.currentTimeMillis())
                .addActions(ActionInfo.newBuilder().setIndex(0).setTitle("Reply").setHasRemoteInput(true))
                .addActions(ActionInfo.newBuilder().setIndex(1).setTitle("Mark as read"))
                .setIconPng(ByteString.copyFrom(testBytes(spec.iconBytes, index.toLong() + 1)))
                .build()
        }
        val fitted = SnapshotBudget.fit(records)
        val reply = SnapshotReply.newBuilder().addAllNotifications(fitted.records).build()
        send(newEnvelope { setSnapshotReply(reply) })
        emit(
            "snapshotSent",
            "count" to fitted.records.size,
            "iconsDropped" to fitted.iconsDropped,
            "recordsDropped" to fitted.recordsDropped,
            "bytes" to reply.serializedSize,
        )
    }

    private fun sendImage(size: Int, seed: Long) {
        // Same envelope sequence as ClipboardSyncManager.sendImage, including waiting for queue space.
        val data = testBytes(size, seed)
        val transferId = UUID.randomUUID().toString()
        runBlocking {
            TransportHub.sendAwait(newEnvelope {
                setClipboardBlobStart(
                    ClipboardBlobStart.newBuilder()
                        .setTransferId(transferId)
                        .setMimeType("image/png")
                        .setTotalSize(data.size.toLong())
                )
            })
            for ((offset, chunk) in ClipboardChunker.chunks(data)) {
                TransportHub.sendAwait(newEnvelope {
                    setClipboardBlobChunk(
                        ClipboardBlobChunk.newBuilder()
                            .setTransferId(transferId)
                            .setOffset(offset)
                            .setData(ByteString.copyFrom(chunk))
                    )
                })
            }
            TransportHub.sendAwait(newEnvelope {
                setClipboardBlobEnd(ClipboardBlobEnd.newBuilder().setTransferId(transferId))
            })
        }
        emit("imageSent", "sha256" to sha256(data), "size" to data.size)
    }

    private fun emit(event: String, vararg fields: Pair<String, Any?>) {
        val line = Json.encode(linkedMapOf<String, Any?>("event" to event).apply { putAll(fields) })
        synchronized(out) {
            out.println(line)
            out.flush()
        }
    }

    private fun Map<String, Any?>.int(key: String): Int = (this[key] as? Number)?.toInt() ?: 0

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** Same generator as the Mac peer (xorshift64*), so either side can verify the other's blobs. */
    fun testBytes(size: Int, seed: Long): ByteArray {
        var state = if (seed == 0L) -0x61c8864680b583ebL else seed
        val bytes = ByteArray(size)
        var index = 0
        while (index < size) {
            state = state xor (state ushr 12)
            state = state xor (state shl 25)
            state = state xor (state ushr 27)
            var word = state * 0x2545F4914F6CDD1DL
            var k = 0
            while (k < 8 && index < size) {
                bytes[index] = word.toByte()
                word = word ushr 8
                index += 1
                k += 1
            }
        }
        return bytes
    }
}

/** Just enough JSON for the control protocol: flat objects of strings, numbers, booleans, lists. */
internal object Json {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Int, is Long -> value.toString()
        is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> quote(k.toString()) + ":" + encode(v) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> quote(value.toString())
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c < ' ' || c.code > 0x7e -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    fun parseObject(text: String): Map<String, Any?> {
        val parser = Parser(text)
        @Suppress("UNCHECKED_CAST")
        return parser.value() as Map<String, Any?>
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun value(): Any? {
            skip()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $i")
            }
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++
            skip()
            if (s[i] == '}') return map.also { i++ }
            while (true) {
                skip()
                val key = string()
                skip()
                check(s[i++] == ':')
                map[key] = value()
                skip()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> error("bad object at $i")
                }
            }
        }

        private fun array(): List<Any?> {
            val list = ArrayList<Any?>()
            i++
            skip()
            if (s[i] == ']') return list.also { i++ }
            while (true) {
                list += value()
                skip()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> error("bad array at $i")
                }
            }
        }

        private fun string(): String {
            check(s[i++] == '"')
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'u' -> sb.append(s.substring(i, i + 4).toInt(16).toChar()).also { i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: text.toDouble()
        }

        private fun literal(word: String, result: Any?): Any? {
            check(s.startsWith(word, i))
            i += word.length
            return result
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
