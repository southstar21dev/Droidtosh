// Headless Mac peer for the end-to-end harness (e2e/). It drives the real RelayTransport —
// outer relay TLS with trust on first use, inner TLS server, PSK auth, v2 negotiation and the
// reliable stream — the way ConnectionController does in the app, and speaks line-delimited
// JSON: commands on stdin, events on stdout. Test tooling only; not part of the app.
//
// Environment: E2E_MODE (relay | local | both, default relay), E2E_RELAY_HOST and
// E2E_RELAY_PORT (unless local), E2E_LOCAL_PORT (local listener port for local | both; 0 or unset
// picks a free one, announced as {"event":"localListening","port"}), E2E_ROOM, E2E_PSK
// (base64url), optional
// E2E_RELAY_FP (pin instead of trusting on first use), E2E_TTL_S (reliable outbox TTL),
// E2E_FILES_DIR (where received files are saved; a temp dir by default) and
// E2E_FILE_STALL_S (file transfer stall timeout), E2E_IDENTITY_P12 (file that keeps the TLS
// identity across peer processes; a fresh identity per process without it).
//
// File transfer: command {"cmd":"send_file","path":…[,"name":…]} queues a file and answers
// {"event":"file_queued","id":…}; a finished incoming file emits
// {"event":"file_received","id","path","sha256","size"}; every finished transfer emits
// {"event":"file_complete","id","direction":"outgoing"|"incoming","ok","error"}, and a resumed
// transfer emits {"event":"file_resumed"}. Command {"cmd":"send_folder","path":…} zips the folder as
// the app does (FolderArchive, off the main queue), emits {"event":"folder_zipped","id","name",
// "archive","sha256","size"} and queues the archive, which is deleted once its transfer finishes;
// a folder that can't be zipped emits {"event":"folder_failed","error"}.
//
// Phone status and Find my phone (§8, §9), run through the app's kit models: every change of
// the status or its staleness emits {"event":"device_status", <DeviceStatus fields in
// snake_case, enums by proto name>, "stale"}; a low battery alert the app would post emits
// {"event":"low_battery","threshold","battery_percent"}. Command {"cmd":"find","action":
// "start"|"stop"} sends a FindPhoneRequest, and every change of the find state emits
// {"event":"find_ui","state","request_id","text","button","reason"} with state idle | waiting |
// requested | ringing | no_response | ended. E2E_FIND_TIMEOUT_S shortens the 15 s response
// timeout.

import CryptoKit
import DroidsyncNotifications
import DroidsyncProtocol
import DroidsyncTransport
import Foundation

let environment = ProcessInfo.processInfo.environment
let output = FileHandle.standardOutput
let outputLock = NSLock()

func emit(_ event: String, _ fields: [String: Any] = [:]) {
    var object = fields
    object["event"] = event
    guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else { return }
    outputLock.lock()
    output.write(data)
    output.write(Data("\n".utf8))
    outputLock.unlock()
}

func fail(_ message: String) -> Never {
    emit("fatal", ["message": message])
    exit(2)
}

func required(_ key: String) -> String {
    guard let value = environment[key], !value.isEmpty else { fail("missing \(key)") }
    return value
}

func sha256Hex(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

/// SHA-256 of a file, read in 1 MiB pieces so large files don't land in memory.
func sha256Hex(fileAt url: URL) -> (String, Int) {
    guard let handle = try? FileHandle(forReadingFrom: url) else { return ("", 0) }
    defer { try? handle.close() }
    var hasher = SHA256()
    var size = 0
    while true {
        // Each read is autoreleased; drain per piece or the whole file accumulates.
        let more: Bool = autoreleasepool {
            guard let piece = try? handle.read(upToCount: 1 << 20), !piece.isEmpty else { return false }
            hasher.update(data: piece)
            size += piece.count
            return true
        }
        if !more { break }
    }
    return (hasher.finalize().map { String(format: "%02x", $0) }.joined(), size)
}

/// Deterministic test bytes (xorshift64*), so both peers can produce and check large blobs.
func testBytes(size: Int, seed: UInt64) -> Data {
    var state = seed == 0 ? 0x9E37_79B9_7F4A_7C15 : seed
    var data = Data(count: size)
    data.withUnsafeMutableBytes { raw in
        let bytes = raw.bindMemory(to: UInt8.self)
        var index = 0
        while index < size {
            state ^= state >> 12
            state ^= state << 25
            state ^= state >> 27
            var word = state &* 0x2545_F491_4F6C_DD1D
            for _ in 0..<8 where index < size {
                bytes[index] = UInt8(truncatingIfNeeded: word)
                word >>= 8
                index += 1
            }
        }
    }
    return data
}

func describe(_ state: RelayTransport.State) -> [String: Any] {
    switch state {
    case .stopped: return ["state": "stopped"]
    case .connecting: return ["state": "connecting"]
    case .waitingForPhone: return ["state": "waitingForPhone"]
    case .connected: return ["state": "connected"]
    case .failed(let message): return ["state": "failed", "detail": message]
    case .relayCertificateMismatch(let fingerprint): return ["state": "relayCertificateMismatch", "detail": fingerprint]
    case .relayRequiresUpgrade: return ["state": "relayRequiresUpgrade"]
    }
}

func payloadName(_ payload: DSEnvelope.OneOf_Payload?) -> String {
    guard let payload else { return "none" }
    // The case label ("clipboardBlobChunk"). Not String(describing:): that text-formats the
    // associated message, which for a 256 KiB file chunk costs more than the transfer itself.
    return Mirror(reflecting: payload).children.first?.label ?? "unknown"
}

final class MacPeer {
    let mode = ConnectionMode(rawValue: environment["E2E_MODE"] ?? "relay") ?? .relay
    lazy var host = required("E2E_RELAY_HOST")
    lazy var port = Int(required("E2E_RELAY_PORT")) ?? 0
    let room = required("E2E_ROOM")
    var localPort: Int?
    let psk: Data
    let identity: TLSIdentity
    let reliableStream: ReliableStream
    let diagnostics = DiagnosticsRecorder()
    var relayFingerprint: String?
    var transport: RelayTransport?
    var assembler = ClipboardAssembler()
    var authentications = 0
    /// File transfer engine; used on the main queue only.
    var files: FileTransferEngine!
    var zipDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("droidsync-e2e-zips")
    var fileTimer: DispatchSourceTimer?
    /// Phone status and Find my phone models; used on the main queue only.
    let statusTracker = PhoneStatusTracker()
    let findSession = FindPhoneSession(
        responseTimeout: environment["E2E_FIND_TIMEOUT_S"].flatMap(TimeInterval.init) ?? FindPhoneLimits.responseTimeout
    )
    var lastDeviceStatus: (DSDeviceStatus, Bool)?
    var lastFindUI: [String: String]?
    var deviceTimer: DispatchSourceTimer?

    init() {
        guard let psk = Base64URL.decode(required("E2E_PSK")), psk.count == 32 else { fail("bad E2E_PSK") }
        self.psk = psk
        do {
            identity = try Self.loadIdentity()
        } catch {
            fail("identity: \(error)")
        }
        let ttl = environment["E2E_TTL_S"].flatMap(TimeInterval.init) ?? ReliableStream.ttl
        reliableStream = ReliableStream(ttl: ttl)
        relayFingerprint = environment["E2E_RELAY_FP"].flatMap { $0.isEmpty ? nil : $0 }
    }

    /// With E2E_IDENTITY_P12 the identity is kept in that file, so a restarted peer process
    /// presents the same certificate the phone pinned, as the app does from its secret store.
    static func loadIdentity() throws -> TLSIdentity {
        guard let path = environment["E2E_IDENTITY_P12"], !path.isEmpty else {
            return try TLSIdentityGenerator.generate()
        }
        let url = URL(fileURLWithPath: path)
        if let data = try? Data(contentsOf: url) { return try TLSIdentity(pkcs12: data) }
        let pkcs12 = try TLSIdentityGenerator.generatePKCS12()
        try pkcs12.write(to: url, options: .atomic)
        return try TLSIdentity(pkcs12: pkcs12)
    }

    func setUpFiles() {
        let base = environment["E2E_FILES_DIR"].flatMap { $0.isEmpty ? nil : URL(fileURLWithPath: $0) }
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("droidsync-e2e-mac-\(UUID().uuidString)")
        var configuration = FileTransferEngine.Configuration(
            downloadsDirectory: base.appendingPathComponent("received"),
            partialDirectory: base.appendingPathComponent("partial")
        )
        if let stall = environment["E2E_FILE_STALL_S"].flatMap(TimeInterval.init) {
            configuration.stallTimeout = stall
        }
        zipDirectory = base.appendingPathComponent("zips")
        files = FileTransferEngine(configuration: configuration) { [weak self] payload in
            self?.send(payload)
        }
        files.onLog = { message in emit("log", ["message": message]) }
        files.onOutcome = { outcome in
            if case .resumed = outcome { emit("file_resumed") }
        }
        files.onChange = { snapshot in
            guard snapshot.phase.isFinished else { return }
            let ok = snapshot.phase == .completed
            var error = ""
            switch snapshot.phase {
            case .failed(let reason), .cancelled(let reason): error = reason
            default: break
            }
            if ok, snapshot.direction == .incoming, let url = snapshot.localURL {
                let (digest, size) = sha256Hex(fileAt: url)
                emit("file_received", [
                    "id": snapshot.id,
                    "path": url.path,
                    "savedAs": snapshot.savedName ?? "",
                    "sha256": digest,
                    "size": size,
                ])
            }
            emit("file_complete", [
                "id": snapshot.id,
                "direction": snapshot.direction == .incoming ? "incoming" : "outgoing",
                "ok": ok,
                "error": error,
            ])
        }
        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in self?.files.tick() }
        timer.resume()
        fileTimer = timer
    }

    func setUpDevice() {
        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now() + 0.5, repeating: 0.5)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            self.findSession.tick()
            self.emitDeviceChanges()
        }
        timer.resume()
        deviceTimer = timer
        lastFindUI = findUI()
    }

    /// Emits `device_status` and `find_ui` when what the app would show changed.
    func emitDeviceChanges() {
        if let status = statusTracker.status {
            let stale = statusTracker.isStale()
            if lastDeviceStatus.map({ $0.0 != status || $0.1 != stale }) ?? true {
                lastDeviceStatus = (status, stale)
                var fields = deviceStatusFields(status)
                fields["stale"] = stale
                emit("device_status", fields)
            }
        }
        let ui = findUI()
        if ui != lastFindUI {
            lastFindUI = ui
            emit("find_ui", ui)
        }
    }

    /// What the popover's Find My Phone control shows. `reason` is set once ended:
    /// stopped_on_phone | stopped_from_mac | timeout | failed[:<error>] | expired | unspecified.
    func findUI() -> [String: String] {
        var reason = ""
        if case .ended(let end) = findSession.phase {
            reason = end.code
            if case .failed(let error) = end, !error.isEmpty { reason += ":\(error)" }
        }
        return [
            "state": findSession.phaseCode,
            "request_id": findSession.requestID ?? "",
            "text": findSession.statusText ?? "",
            "button": findSession.buttonTitle,
            "reason": reason,
        ]
    }

    func handleDevice(_ payload: DSEnvelope.OneOf_Payload) {
        switch payload {
        case .deviceStatus(let status):
            if let threshold = statusTracker.receive(status) {
                emit("low_battery", ["threshold": Int(threshold), "battery_percent": Int(status.batteryPercent)])
            }
        case .findPhoneState(let state):
            findSession.receive(state)
        default:
            return
        }
        emitDeviceChanges()
    }

    func start() {
        let relay = mode.usesRelay ? RelayEndpoint(host: host, port: UInt16(clamping: port), room: room) : nil
        let local = mode.usesLocal
            ? LocalListenerOptions(port: environment["E2E_LOCAL_PORT"].flatMap { Int($0) } ?? 0, advertisement: nil)
            : nil
        let transport = RelayTransport(
            relay: relay,
            local: local,
            identity: identity,
            pskProvider: { [psk] in [psk] },
            relayFingerprint: relayFingerprint,
            reliableStream: reliableStream,
            diagnostics: diagnostics,
            onStateChange: { state in emit("state", describe(state)) },
            onClientAuthenticated: { [weak self] session in
                self?.authentications += 1
                emit("authenticated", [
                    "version": Int(session.negotiation.version),
                    "capabilities": session.negotiation.capabilities,
                    "path": session.path.rawValue,
                ])
                let filesSupported = session.negotiation.supports(ProtocolV2.Capability.files)
                let statusSupported = session.negotiation.supports(ProtocolV2.Capability.phoneStatus)
                let findSupported = session.negotiation.supports(ProtocolV2.Capability.find)
                DispatchQueue.main.async {
                    if statusSupported {
                        self?.statusTracker.sessionStarted()
                    } else {
                        self?.statusTracker.sessionEnded()
                    }
                    self?.findSession.setConnected(findSupported)
                    self?.emitDeviceChanges()
                    if filesSupported {
                        self?.files.sessionStarted()
                    } else {
                        self?.files.sessionStartedWithoutFiles()
                    }
                }
            },
            onClientDisconnected: { [weak self] in
                emit("phoneDisconnected")
                DispatchQueue.main.async {
                    self?.files.sessionEnded()
                    self?.statusTracker.sessionEnded()
                    self?.findSession.setConnected(false)
                    self?.emitDeviceChanges()
                }
            },
            onEnvelope: { [weak self] envelope in self?.handle(envelope) }
        )
        transport.onRelayFingerprintLearned = { [weak self] fingerprint in
            guard let self else { return }
            self.relayFingerprint = fingerprint
            emit("relayFingerprintLearned", ["fingerprint": fingerprint])
            self.emitPairing()
        }
        transport.onLocalStateChange = { [weak self] state in
            guard let self, case .listening(let port) = state else { return }
            let announce = self.localPort == nil
            self.localPort = port
            emit("localListening", ["port": port])
            if announce { self.emitPairing() }
        }
        self.transport = transport
        transport.start()
        if relayFingerprint != nil { emitPairing() }
    }

    /// Emitted once everything the mode needs is known: the relay pin and/or the listening port.
    func emitPairing() {
        if mode.usesRelay && relayFingerprint == nil { return }
        if mode.usesLocal && localPort == nil { return }
        let payload = PairingPayload(
            deviceID: "e2e-mac",
            deviceName: "E2E Mac",
            host: mode.usesRelay ? host : nil,
            port: mode.usesRelay ? UInt16(clamping: port) : nil,
            room: room,
            fingerprint: identity.fingerprint,
            relayFingerprint: relayFingerprint,
            psk: psk,
            mode: mode,
            localHost: mode.usesLocal ? "127.0.0.1" : nil,
            localPort: localPort.map { UInt16(clamping: $0) }
        )
        emit("pairing", ["uri": payload.uri()])
    }

    func handle(_ envelope: DSEnvelope) {
        var fields: [String: Any] = [
            "type": payloadName(envelope.payload),
            "seq": Int(envelope.seq),
            "ack": Int(envelope.ack),
            "messageId": envelope.messageID,
        ]
        switch envelope.payload {
        case .fileOffer, .fileReject, .fileChunk, .fileProgress, .fileEnd, .fileComplete, .fileCancel:
            let payload = envelope.payload!
            DispatchQueue.main.async { [weak self] in self?.files.handle(payload) }
            if case .fileChunk = payload { return }
            if case .fileProgress = payload { return }
        case .deviceStatus(let status):
            DispatchQueue.main.async { [weak self] in self?.handleDevice(.deviceStatus(status)) }
        case .findPhoneState(let state):
            fields["requestId"] = state.requestID
            fields["ringing"] = state.ringing
            DispatchQueue.main.async { [weak self] in self?.handleDevice(.findPhoneState(state)) }
        case .clipboardBlobStart(let start):
            _ = assembler.start(transferID: start.transferID, mimeType: start.mimeType, totalSize: start.totalSize)
            return
        case .clipboardBlobChunk(let chunk):
            let result = assembler.append(transferID: chunk.transferID, offset: Int(chunk.offset), data: chunk.data)
            if result == .rejected { emit("imageRejected", ["transferId": chunk.transferID]) }
            return
        case .clipboardBlobEnd(let end):
            if let data = assembler.end(transferID: end.transferID) {
                emit("imageReceived", ["sha256": sha256Hex(data), "size": data.count])
            } else {
                emit("imageRejected", ["transferId": end.transferID])
            }
            return
        case .fileAccept(let accept):
            fields["transferId"] = accept.transferID
            fields["resumeOffset"] = Int(accept.resumeOffset)
            DispatchQueue.main.async { [weak self] in self?.files.handle(.fileAccept(accept)) }
        case .actionResult(let result):
            fields["requestId"] = result.requestID
            fields["ok"] = result.ok
        case .clipboardText(let text):
            fields["text"] = text.text
        case .hello(let hello):
            fields["deviceName"] = hello.deviceName
        case .snapshotReply(let reply):
            // Convert every record the way NotificationBridge does before presenting it.
            let pending = reply.notifications.map(NotificationBridge.pending(from:))
            fields["count"] = reply.notifications.count
            fields["frameBytes"] = (try? envelope.serializedData().count) ?? -1
            fields["icons"] = reply.notifications.filter { !$0.iconPng.isEmpty }.count
            fields["converted"] = pending.filter { !$0.identifier.isEmpty }.count
            fields["keys"] = reply.notifications.map(\.key)
        default:
            break
        }
        emit("recv", fields)
    }

    func run(_ command: [String: Any]) {
        guard let name = command["cmd"] as? String else { return }
        switch name {
        case "notificationAction":
            var action = DSNotificationAction()
            action.key = command["key"] as? String ?? "k"
            action.actionIndex = 0
            action.requestID = command["requestId"] as? String ?? UUID().uuidString
            send(.notificationAction(action))
        case "callAction":
            var action = DSCallAction()
            action.callID = command["callId"] as? String ?? "cellular:e2e"
            action.action = .callActionDecline
            action.requestID = command["requestId"] as? String ?? UUID().uuidString
            send(.callAction(action))
        case "clipboardText":
            var text = DSClipboardText()
            text.text = command["text"] as? String ?? ""
            send(.clipboardText(text))
        case "send_file":
            guard let path = command["path"] as? String else {
                emit("error", ["message": "send_file needs path"])
                return
            }
            do {
                let id = try files.enqueue(fileAt: URL(fileURLWithPath: path), name: command["name"] as? String)
                emit("file_queued", ["id": id, "path": path])
            } catch {
                emit("error", ["message": "send_file: \(error)"])
            }
        case "send_folder":
            guard let path = command["path"] as? String else {
                emit("error", ["message": "send_folder needs path"])
                return
            }
            let folder = URL(fileURLWithPath: path)
            let zipDirectory = zipDirectory
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                do {
                    let archive = try FolderArchive.zip(folder: folder, temporaryDirectory: zipDirectory)
                    let (digest, size) = sha256Hex(fileAt: archive)
                    DispatchQueue.main.async {
                        guard let self else { return }
                        let id = UUID().uuidString
                        let name = FolderArchive.archiveName(for: folder)
                        emit("folder_zipped", ["id": id, "name": name, "archive": archive.path, "sha256": digest, "size": size])
                        do {
                            try self.files.enqueue(fileAt: archive, name: name, id: id, removingWhenFinished: archive.deletingLastPathComponent())
                        } catch {
                            try? FileManager.default.removeItem(at: archive.deletingLastPathComponent())
                            emit("folder_failed", ["error": "\(error)"])
                        }
                    }
                } catch {
                    emit("folder_failed", ["error": "\(error)"])
                }
            }
        case "find":
            switch command["action"] as? String {
            case "start":
                send(.findPhoneRequest(findSession.start()))
            case "stop":
                if let stop = findSession.stop() {
                    // As the app does: a START still only queued is withdrawn instead.
                    let withdrawn = transport?.withdrawUnsent { FindPhoneSession.isStart($0, requestID: stop.requestID) } ?? false
                    if withdrawn {
                        emit("find_withdrawn", ["request_id": stop.requestID])
                    } else {
                        send(.findPhoneRequest(stop))
                    }
                }
            default:
                emit("error", ["message": "find needs action start|stop"])
                return
            }
            emitDeviceChanges()
        case "cancel_file":
            files.cancel(id: command["id"] as? String ?? "")
        case "snapshotRequest":
            send(.snapshotRequest(DSSnapshotRequest()))
        case "sendImage":
            sendImage(size: command["size"] as? Int ?? 1024, seed: UInt64(command["seed"] as? Int ?? 1))
        case "sleep":
            // ConnectionController.handleSleep
            if transport?.state == .connected {
                var status = DSHostStatus()
                status.sleeping = true
                send(.hostStatus(status))
            }
            transport?.suspend()
            emit("slept")
        case "wake":
            transport?.resume()
            emit("woke")
        case "stats":
            let counters = reliableStream.counters
            emit("stats", [
                "outbox": reliableStream.pendingCount,
                "evicted": counters.evicted,
                "expired": counters.expired,
                "gaps": counters.gaps,
                "duplicates": counters.duplicates,
                "retransmits": counters.retransmits,
                "authentications": authentications,
                "state": describe(transport?.state ?? .stopped)["state"] ?? "",
            ])
        case "quit":
            transport?.stop()
            emit("bye")
            exit(0)
        default:
            emit("error", ["message": "unknown command \(name)"])
        }
    }

    func send(_ payload: DSEnvelope.OneOf_Payload) {
        transport?.send(protocolEnvelope(payload))
    }

    func sendImage(size: Int, seed: UInt64) {
        let data = testBytes(size: size, seed: seed)
        let transferID = UUID().uuidString
        var start = DSClipboardBlobStart()
        start.transferID = transferID
        start.mimeType = "image/png"
        start.totalSize = UInt64(data.count)
        send(.clipboardBlobStart(start))
        for chunk in ClipboardChunker.chunks(for: data) {
            var message = DSClipboardBlobChunk()
            message.transferID = transferID
            message.offset = UInt32(chunk.offset)
            message.data = chunk.data
            send(.clipboardBlobChunk(message))
        }
        var end = DSClipboardBlobEnd()
        end.transferID = transferID
        send(.clipboardBlobEnd(end))
        emit("imageSent", ["sha256": sha256Hex(data), "size": data.count])
    }
}

/// A `DeviceStatus` as JSON: proto field names, enums by their proto names.
func deviceStatusFields(_ status: DSDeviceStatus) -> [String: Any] {
    func name(_ names: [String], _ value: Int) -> String {
        names.indices.contains(value) ? names[value] : "UNRECOGNIZED_\(value)"
    }
    return [
        "battery_percent": Int(status.batteryPercent),
        "charging": status.charging,
        "charge_source": name(
            ["CHARGE_SOURCE_UNSPECIFIED", "CHARGE_SOURCE_AC", "CHARGE_SOURCE_USB", "CHARGE_SOURCE_WIRELESS", "CHARGE_SOURCE_DOCK"],
            status.chargeSource.rawValue
        ),
        "power_save": status.powerSave,
        "network_type": name(
            ["NETWORK_TYPE_UNSPECIFIED", "NETWORK_TYPE_NONE", "NETWORK_TYPE_WIFI", "NETWORK_TYPE_CELLULAR",
             "NETWORK_TYPE_ETHERNET", "NETWORK_TYPE_OTHER"],
            status.networkType.rawValue
        ),
        "wifi_level": Int(status.wifiLevel),
        "cellular_level": Int(status.cellularLevel),
        "cellular_generation": status.cellularGeneration,
        "carrier": status.carrier,
        "airplane_mode": status.airplaneMode,
        "ringer_mode": name(
            ["RINGER_MODE_UNSPECIFIED", "RINGER_MODE_NORMAL", "RINGER_MODE_VIBRATE", "RINGER_MODE_SILENT"],
            status.ringerMode.rawValue
        ),
        "do_not_disturb": status.doNotDisturb,
        "sampled_at_ms": status.sampledAtMs,
    ]
}

let peer = MacPeer()
peer.setUpFiles()
peer.setUpDevice()
DispatchQueue.main.async { peer.start() }

Thread.detachNewThread {
    while let line = readLine(strippingNewline: true) {
        guard let data = line.data(using: .utf8),
              let command = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            emit("error", ["message": "bad command line"])
            continue
        }
        DispatchQueue.main.async { peer.run(command) }
    }
    // stdin closed: the orchestrator is gone.
    DispatchQueue.main.async {
        peer.transport?.stop()
        exit(0)
    }
}

emit("ready")
dispatchMain()
