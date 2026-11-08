import CryptoKit
import Foundation
import UniformTypeIdentifiers
import DroidsyncProtocol

/// Limits from PROTOCOL-v2.md §7.2.
public enum FileTransferLimits {
    public static let maxFileSize: UInt64 = 2 * 1024 * 1024 * 1024
    public static let chunkSize = 256 * 1024
    public static let window: UInt64 = 4 * 1024 * 1024
    public static let progressInterval: UInt64 = 1024 * 1024
    public static let stallTimeout: TimeInterval = 30
    public static let partialRetention: TimeInterval = 600
    public static let maxNameBytes = 200
    /// Concurrent incoming transfers kept (active or resumable); more are rejected as `busy`.
    public static let maxIncoming = 8
}

/// Turns a peer-supplied file name into a safe single path component (§7.3).
public enum FileNameSanitizer {
    public static let fallback = "file"
    private static let maxExtensionBytes = 20

    public static func sanitize(_ name: String) -> String {
        var scalars = String.UnicodeScalarView()
        // NFC first, so a name decomposed by the sending file system ("e" + U+0301) is measured,
        // capped and compared the same way on both sides.
        for scalar in name.precomposedStringWithCanonicalMapping.unicodeScalars where !isStripped(scalar) {
            scalars.append(scalar)
        }
        var result = String(scalars).trimmingCharacters(in: .whitespacesAndNewlines)
        // A leading dot would hide the file (and "." / ".." are not names at all).
        while result.hasPrefix(".") { result.removeFirst() }
        result = result.trimmingCharacters(in: .whitespacesAndNewlines)
        if result.isEmpty { return fallback }
        return truncated(result, toBytes: FileTransferLimits.maxNameBytes)
    }

    /// `directory/name`, or `name (1).ext`, `name (2).ext`, … when it is taken.
    public static func uniqueURL(for name: String, in directory: URL, fileManager: FileManager = .default) -> URL {
        let candidate = directory.appendingPathComponent(name)
        guard fileManager.fileExists(atPath: candidate.path) else { return candidate }
        let (base, ext) = split(name)
        for n in 1...9_999 {
            let numbered = ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)"
            let url = directory.appendingPathComponent(numbered)
            if !fileManager.fileExists(atPath: url.path) { return url }
        }
        let unique = ext.isEmpty ? "\(base) \(UUID().uuidString)" : "\(base) \(UUID().uuidString).\(ext)"
        return directory.appendingPathComponent(unique)
    }

    private static func isStripped(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar {
        case "/", "\\", ":":
            return true
        default:
            break
        }
        switch scalar.value {
        case 0x202A...0x202E, 0x2066...0x2069, 0x200E, 0x200F:
            // Bidi controls can disguise an extension ("txt.exe" shown as "exe.txt").
            return true
        default:
            break
        }
        switch scalar.properties.generalCategory {
        case .control, .lineSeparator, .paragraphSeparator:
            return true
        default:
            return false
        }
    }

    /// Splits off a short final extension; `"archive.tar.gz"` → `("archive.tar", "gz")`.
    static func split(_ name: String) -> (base: String, ext: String) {
        guard let dot = name.lastIndex(of: "."), dot != name.startIndex else { return (name, "") }
        let ext = String(name[name.index(after: dot)...])
        guard !ext.isEmpty, ext.utf8.count <= maxExtensionBytes else { return (name, "") }
        return (String(name[..<dot]), ext)
    }

    /// Cuts on character boundaries, keeping the extension when there is one.
    private static func truncated(_ name: String, toBytes limit: Int) -> String {
        guard name.utf8.count > limit else { return name }
        let (base, ext) = split(name)
        let suffix = ext.isEmpty ? "" : "." + ext
        let budget = limit - suffix.utf8.count
        var kept = ""
        var used = 0
        for character in base {
            let size = String(character).utf8.count
            if used + size > budget { break }
            kept.append(character)
            used += size
        }
        let trimmed = kept.trimmingCharacters(in: .whitespacesAndNewlines)
        return (trimmed.isEmpty ? fallback : trimmed) + suffix
    }
}

/// Where an incoming transfer's bytes are written before verification.
public protocol FilePartialSink: AnyObject {
    func write(_ data: Data) throws
    func close()
}

final class FileHandlePartialSink: FilePartialSink {
    private let handle: FileHandle

    init(url: URL, append: Bool) throws {
        let fileManager = FileManager.default
        if !append || !fileManager.fileExists(atPath: url.path) {
            guard fileManager.createFile(atPath: url.path, contents: nil, attributes: [.posixPermissions: 0o600]) else {
                throw CocoaError(.fileWriteUnknown)
            }
        }
        handle = try FileHandle(forWritingTo: url)
        if append { try handle.seekToEnd() }
    }

    func write(_ data: Data) throws {
        try handle.write(contentsOf: data)
    }

    func close() {
        try? handle.close()
    }
}

public enum FileTransferError: Error, Equatable {
    case notARegularFile
    case tooLarge
    case unreadable
    /// Zipping a folder was cancelled.
    case cancelled
    /// Zipping a folder failed; the string is ditto's message.
    case compressionFailed(String)
}

/// What the UI shows for one transfer.
public struct FileTransferSnapshot: Equatable, Sendable, Identifiable {
    public enum Direction: Equatable, Sendable {
        case outgoing
        case incoming
    }

    public enum Phase: Equatable, Sendable {
        /// A folder is being zipped before it's queued (the owner shows it; the engine never
        /// reports it).
        case compressing
        case queued
        case offered
        case transferring
        case waitingForReconnect
        case completed
        case failed(String)
        case cancelled(String)

        public var isFinished: Bool {
            switch self {
            case .completed, .failed, .cancelled: return true
            default: return false
            }
        }
    }

    public let id: String
    public let direction: Direction
    public let name: String
    public let size: UInt64
    public var transferred: UInt64
    public var phase: Phase
    /// Outgoing: the name the phone saved it under. Incoming: the saved file's name.
    public var savedName: String?
    /// Outgoing: the source file. Incoming: the saved file once completed.
    public var localURL: URL?

    public init(
        id: String,
        direction: Direction,
        name: String,
        size: UInt64,
        transferred: UInt64,
        phase: Phase,
        savedName: String? = nil,
        localURL: URL? = nil
    ) {
        self.id = id
        self.direction = direction
        self.name = name
        self.size = size
        self.transferred = transferred
        self.phase = phase
        self.savedName = savedName
        self.localURL = localURL
    }

    public var fraction: Double {
        size == 0 ? (phase == .completed ? 1 : 0) : min(Double(transferred) / Double(size), 1)
    }
}

/// Counted outcomes for diagnostics.
public enum FileTransferOutcome: Sendable {
    case sent
    case received
    case failed
    case resumed
}

/// The file transfer protocol of PROTOCOL-v2.md §7 for both directions.
///
/// Not thread-safe: confine every call to one serial queue (it does blocking file I/O and
/// hashing, so not the main thread). It sends through `send` and reports through `onChange`
/// and `onOutcome` on that same queue. The clock is injected so stall and retention timing
/// can be tested; call `tick()` periodically (about once a second).
///
/// Choices the spec leaves open (Android matches these):
/// - The sender acts on `FileAccept` only while its offer is outstanding; an accept for a
///   transfer that is already sending (an answer to a duplicate offer) is ignored.
/// - A stall (no progress for 30 s) parks the transfer until the next session, which
///   re-offers it with the same id.
/// - A `FileEnd` that arrives before all bytes (a retransmitted End after a reconnect) is
///   ignored; the re-offer resumes the transfer and a fresh End follows.
/// - A chunk below the written offset is a duplicate and ignored; one above it cancels the
///   transfer with `FileCancel{reason:"error"}`.
/// - A repeated offer or End for an already completed transfer is answered with the same
///   `FileComplete` again.
/// - Partial files are kept for 10 minutes after the last chunk; partials from a previous
///   process run are deleted at start-up.
public final class FileTransferEngine {
    public typealias Send = (DSEnvelope.OneOf_Payload) -> Void

    public struct Configuration {
        public var downloadsDirectory: URL
        public var partialDirectory: URL
        public var maxFileSize = FileTransferLimits.maxFileSize
        public var chunkSize = FileTransferLimits.chunkSize
        public var window = FileTransferLimits.window
        public var progressInterval = FileTransferLimits.progressInterval
        public var stallTimeout = FileTransferLimits.stallTimeout
        public var partialRetention = FileTransferLimits.partialRetention

        public init(downloadsDirectory: URL, partialDirectory: URL) {
            self.downloadsDirectory = downloadsDirectory
            self.partialDirectory = partialDirectory
        }
    }

    public var onChange: ((FileTransferSnapshot) -> Void)?
    public var onOutcome: ((FileTransferOutcome) -> Void)?
    public var onLog: ((String) -> Void)?
    /// Whether incoming offers are accepted.
    public var isReceivingEnabled: () -> Bool = { true }
    /// Free bytes at the downloads directory, or nil when unknown.
    public var availableCapacity: (URL) -> Int64? = { url in
        let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage
    }
    /// Opens the partial file for an incoming transfer (injectable to test write failures).
    public var makeSink: (URL, _ append: Bool) throws -> FilePartialSink = { url, append in
        try FileHandlePartialSink(url: url, append: append)
    }

    private struct Outgoing {
        var snapshot: FileTransferSnapshot
        let url: URL
        let mimeType: String
        let modifiedAtMs: UInt64
        var handle: FileHandle?
        var nextOffset: UInt64 = 0
        var acked: UInt64 = 0
        var hasher = SHA256()
        var endSent = false
        var lastActivity: Date
        var everOffered = false
    }

    private struct Incoming {
        var snapshot: FileTransferSnapshot
        let partialURL: URL
        let modifiedAtMs: UInt64
        var sink: FilePartialSink?
        var written: UInt64 = 0
        var hasher = SHA256()
        var lastProgressSent: UInt64 = 0
        var lastActivity: Date
    }

    private let configuration: Configuration
    private let send: Send
    private let clock: () -> Date
    private let fileManager = FileManager.default
    private var pending: [Outgoing] = []
    private var active: Outgoing?
    private var incoming: [String: Incoming] = [:]
    /// Answers to replay when a completed transfer's offer or End arrives again.
    private var finishedIncoming: [(id: String, complete: DSFileComplete)] = []
    private var sessionUp = false
    /// Files to delete when the outgoing transfer with that id finishes.
    private var cleanups: [String: URL] = [:]
    /// The current session didn't negotiate `files`: outgoing transfers fail instead of waiting.
    private var peerLacksFiles = false

    public init(configuration: Configuration, clock: @escaping () -> Date = Date.init, send: @escaping Send) {
        self.configuration = configuration
        self.clock = clock
        self.send = send
        try? fileManager.removeItem(at: configuration.partialDirectory)
        try? fileManager.createDirectory(
            at: configuration.partialDirectory,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
    }

    /// Reports a snapshot, first deleting the transfer's temporary file once it has finished.
    private func report(_ snapshot: FileTransferSnapshot) {
        if snapshot.phase.isFinished, let cleanup = cleanups.removeValue(forKey: snapshot.id) {
            try? fileManager.removeItem(at: cleanup)
        }
        onChange?(snapshot)
    }

    // MARK: Session

    /// A phone session is up and the Resume exchange is done: re-offer the interrupted
    /// transfer, if any, otherwise start the next queued one.
    public func sessionStarted() {
        sessionUp = true
        peerLacksFiles = false
        if active != nil {
            offerActive()
        } else {
            startNextIfIdle()
        }
    }

    /// A phone session is up but the phone didn't negotiate `files`: fail the queued and
    /// parked outgoing transfers, and any queued during this session, as `unsupported`.
    public func sessionStartedWithoutFiles() {
        sessionUp = false
        peerLacksFiles = true
        failOutgoingUnsupported()
    }

    /// The phone session ended: park the active transfers until the next session.
    public func sessionEnded() {
        sessionUp = false
        peerLacksFiles = false
        if var transfer = active, !transfer.snapshot.phase.isFinished {
            try? transfer.handle?.close()
            transfer.handle = nil
            transfer.snapshot.phase = .waitingForReconnect
            active = transfer
            report(transfer.snapshot)
        }
        for (id, var transfer) in incoming {
            transfer.sink?.close()
            transfer.sink = nil
            transfer.snapshot.phase = .waitingForReconnect
            incoming[id] = transfer
            report(transfer.snapshot)
        }
    }

    /// Stall detection and partial-file expiry.
    public func tick() {
        let now = clock()
        if var transfer = active, sessionUp,
           transfer.snapshot.phase == .offered || transfer.snapshot.phase == .transferring,
           now.timeIntervalSince(transfer.lastActivity) > configuration.stallTimeout {
            onLog?("file: \(transfer.snapshot.id) stalled, waiting for a reconnect")
            try? transfer.handle?.close()
            transfer.handle = nil
            transfer.snapshot.phase = .waitingForReconnect
            active = transfer
            report(transfer.snapshot)
        }
        for (id, transfer) in incoming
        where now.timeIntervalSince(transfer.lastActivity) > configuration.partialRetention {
            onLog?("file: dropping expired partial \(id)")
            discardIncoming(id, phase: .failed("expired"))
            onOutcome?(.failed)
        }
    }

    // MARK: Sending

    /// Queues a file for the phone; it is offered once no other transfer is active. `name` is
    /// the name offered to the phone (the file's own name by default), `id` the transfer id (a
    /// new one by default). `removingWhenFinished` is deleted once the transfer completes,
    /// fails or is cancelled: the temporary archive of a folder. If this throws, the caller
    /// still owns it.
    @discardableResult
    public func enqueue(
        fileAt url: URL,
        name: String? = nil,
        id: String = UUID().uuidString,
        removingWhenFinished cleanup: URL? = nil
    ) throws -> String {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey])
        guard values.isRegularFile == true else { throw FileTransferError.notARegularFile }
        let size = UInt64(max(values.fileSize ?? 0, 0))
        guard size <= configuration.maxFileSize else { throw FileTransferError.tooLarge }
        guard fileManager.isReadableFile(atPath: url.path) else { throw FileTransferError.unreadable }
        if let cleanup { cleanups[id] = cleanup }
        let modified = values.contentModificationDate.map { UInt64(max($0.timeIntervalSince1970, 0) * 1000) } ?? 0
        let transfer = Outgoing(
            snapshot: FileTransferSnapshot(
                id: id, direction: .outgoing, name: name ?? url.lastPathComponent, size: size,
                transferred: 0, phase: .queued, savedName: nil, localURL: url
            ),
            url: url,
            mimeType: UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "",
            modifiedAtMs: modified,
            lastActivity: clock()
        )
        pending.append(transfer)
        report(transfer.snapshot)
        if peerLacksFiles {
            failOutgoingUnsupported()
        } else {
            startNextIfIdle()
        }
        return id
    }

    private func failOutgoingUnsupported() {
        var failed = pending
        pending.removeAll()
        if let transfer = active {
            try? transfer.handle?.close()
            failed.insert(transfer, at: 0)
            active = nil
        }
        for var transfer in failed {
            transfer.snapshot.phase = .failed("unsupported")
            onOutcome?(.failed)
            report(transfer.snapshot)
        }
    }

    /// Cancels a transfer in either direction.
    public func cancel(id: String) {
        if var transfer = active, transfer.snapshot.id == id {
            if transfer.everOffered { sendCancel(id, reason: "user") }
            try? transfer.handle?.close()
            transfer.snapshot.phase = .cancelled("user")
            active = nil
            report(transfer.snapshot)
            startNextIfIdle()
        } else if let index = pending.firstIndex(where: { $0.snapshot.id == id }) {
            var transfer = pending.remove(at: index)
            transfer.snapshot.phase = .cancelled("user")
            report(transfer.snapshot)
        } else if incoming[id] != nil {
            sendCancel(id, reason: "user")
            discardIncoming(id, phase: .cancelled("user"))
        }
    }

    private func startNextIfIdle() {
        guard sessionUp, active == nil, !pending.isEmpty else { return }
        active = pending.removeFirst()
        offerActive()
    }

    private func offerActive() {
        guard var transfer = active else { return }
        try? transfer.handle?.close()
        transfer.handle = nil
        transfer.endSent = false
        transfer.everOffered = true
        transfer.snapshot.phase = .offered
        transfer.lastActivity = clock()
        active = transfer
        var offer = DSFileOffer()
        offer.transferID = transfer.snapshot.id
        offer.name = transfer.snapshot.name
        offer.mimeType = transfer.mimeType
        offer.size = transfer.snapshot.size
        offer.modifiedAtMs = transfer.modifiedAtMs
        send(.fileOffer(offer))
        report(transfer.snapshot)
    }

    private func handleAccept(_ accept: DSFileAccept) {
        guard var transfer = active, transfer.snapshot.id == accept.transferID,
              transfer.snapshot.phase == .offered else { return }
        let resume = min(accept.resumeOffset, transfer.snapshot.size)
        do {
            let size = try transfer.url.resourceValues(forKeys: [.fileSizeKey]).fileSize.map { UInt64(max($0, 0)) }
            guard size == transfer.snapshot.size else { throw FileTransferError.unreadable }
            let handle = try FileHandle(forReadingFrom: transfer.url)
            var hasher = SHA256()
            var hashed: UInt64 = 0
            while hashed < resume {
                // FileHandle reads are autoreleased: without a pool per piece, re-hashing a large
                // resume offset holds every piece (hundreds of MiB) until the caller's pool drains.
                try autoreleasepool {
                    let want = Int(min(UInt64(1024 * 1024), resume - hashed))
                    guard let data = try handle.read(upToCount: want), !data.isEmpty else {
                        throw FileTransferError.unreadable
                    }
                    hasher.update(data: data)
                    hashed += UInt64(data.count)
                }
            }
            transfer.handle = handle
            transfer.hasher = hasher
        } catch {
            failActive(transfer, reason: "source_changed")
            return
        }
        if resume > 0 { onOutcome?(.resumed) }
        transfer.nextOffset = resume
        transfer.acked = resume
        transfer.snapshot.transferred = resume
        transfer.snapshot.phase = .transferring
        transfer.lastActivity = clock()
        active = transfer
        report(transfer.snapshot)
        pump()
    }

    private func pump() {
        guard var transfer = active, sessionUp, transfer.snapshot.phase == .transferring,
              let handle = transfer.handle else { return }
        let size = transfer.snapshot.size
        while transfer.nextOffset < size, transfer.nextOffset - transfer.acked < configuration.window {
            let room = configuration.window - (transfer.nextOffset - transfer.acked)
            let want = Int(min(UInt64(configuration.chunkSize), size - transfer.nextOffset, room))
            let data: Data
            do {
                guard let read = try handle.read(upToCount: want), read.count == want else {
                    throw FileTransferError.unreadable
                }
                data = read
            } catch {
                failActive(transfer, reason: "source_changed")
                return
            }
            transfer.hasher.update(data: data)
            var chunk = DSFileChunk()
            chunk.transferID = transfer.snapshot.id
            chunk.offset = transfer.nextOffset
            chunk.data = data
            send(.fileChunk(chunk))
            transfer.nextOffset += UInt64(data.count)
        }
        if transfer.nextOffset == size, !transfer.endSent {
            var end = DSFileEnd()
            end.transferID = transfer.snapshot.id
            end.sha256 = Data(transfer.hasher.finalize())
            send(.fileEnd(end))
            transfer.endSent = true
        }
        active = transfer
    }

    private func handleProgress(_ progress: DSFileProgress) {
        guard var transfer = active, transfer.snapshot.id == progress.transferID,
              transfer.snapshot.phase == .transferring else { return }
        let received = min(progress.received, transfer.nextOffset)
        guard received >= transfer.acked else { return }
        transfer.acked = received
        transfer.snapshot.transferred = received
        transfer.lastActivity = clock()
        active = transfer
        report(transfer.snapshot)
        pump()
    }

    private func handleComplete(_ complete: DSFileComplete) {
        guard var transfer = active, transfer.snapshot.id == complete.transferID else { return }
        try? transfer.handle?.close()
        active = nil
        if complete.ok {
            transfer.snapshot.transferred = transfer.snapshot.size
            transfer.snapshot.phase = .completed
            transfer.snapshot.savedName = complete.savedAs.isEmpty ? nil : complete.savedAs
            onOutcome?(.sent)
        } else {
            transfer.snapshot.phase = .failed(complete.error.isEmpty ? "failed" : complete.error)
            onOutcome?(.failed)
        }
        report(transfer.snapshot)
        startNextIfIdle()
    }

    private func handleReject(_ reject: DSFileReject) {
        guard var transfer = active, transfer.snapshot.id == reject.transferID else { return }
        try? transfer.handle?.close()
        active = nil
        transfer.snapshot.phase = .failed(reject.reason.isEmpty ? "rejected" : reject.reason)
        onOutcome?(.failed)
        report(transfer.snapshot)
        startNextIfIdle()
    }

    private func failActive(_ transfer: Outgoing, reason: String) {
        var transfer = transfer
        sendCancel(transfer.snapshot.id, reason: "error")
        try? transfer.handle?.close()
        active = nil
        transfer.snapshot.phase = .failed(reason)
        onOutcome?(.failed)
        report(transfer.snapshot)
        startNextIfIdle()
    }

    // MARK: Receiving

    public func handle(_ payload: DSEnvelope.OneOf_Payload) {
        switch payload {
        case .fileOffer(let offer): handleOffer(offer)
        case .fileAccept(let accept): handleAccept(accept)
        case .fileReject(let reject): handleReject(reject)
        case .fileChunk(let chunk): handleChunk(chunk)
        case .fileProgress(let progress): handleProgress(progress)
        case .fileEnd(let end): handleEnd(end)
        case .fileComplete(let complete): handleComplete(complete)
        case .fileCancel(let cancel): handlePeerCancel(cancel)
        default: break
        }
    }

    /// Transfer ids become file names, so only UUID-like ids are accepted.
    static func isValidTransferID(_ id: String) -> Bool {
        !id.isEmpty && id.utf8.count <= 64 && id.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.contains($0) && $0.isASCII || $0 == "-" || $0 == "_"
        }
    }

    private func handleOffer(_ offer: DSFileOffer) {
        let id = offer.transferID
        guard Self.isValidTransferID(id) else {
            sendReject(id, reason: "invalid")
            return
        }
        if let finished = finishedIncoming.first(where: { $0.id == id }) {
            send(.fileComplete(finished.complete))
            return
        }
        if var transfer = incoming[id] {
            if transfer.snapshot.size == offer.size {
                resumeIncoming(&transfer)
                return
            }
            discardIncoming(id, phase: .failed("restarted"))
        }
        guard isReceivingEnabled() else {
            sendReject(id, reason: "disabled")
            return
        }
        guard offer.size <= configuration.maxFileSize else {
            sendReject(id, reason: "too_large")
            return
        }
        if let free = availableCapacity(configuration.downloadsDirectory), free < Int64(clamping: offer.size) {
            sendReject(id, reason: "disk_full")
            return
        }
        guard incoming.count < FileTransferLimits.maxIncoming else {
            sendReject(id, reason: "busy")
            return
        }
        let partialURL = configuration.partialDirectory.appendingPathComponent(id + ".part")
        let sink: FilePartialSink
        do {
            sink = try makeSink(partialURL, false)
        } catch {
            sendReject(id, reason: isDiskFull(error) ? "disk_full" : "invalid")
            return
        }
        let transfer = Incoming(
            snapshot: FileTransferSnapshot(
                id: id, direction: .incoming, name: FileNameSanitizer.sanitize(offer.name), size: offer.size,
                transferred: 0, phase: .transferring, savedName: nil, localURL: nil
            ),
            partialURL: partialURL,
            modifiedAtMs: offer.modifiedAtMs,
            sink: sink,
            lastActivity: clock()
        )
        incoming[id] = transfer
        sendAccept(id, resumeOffset: 0)
        report(transfer.snapshot)
    }

    /// A repeated offer for a transfer we hold: resume from what is on disk.
    private func resumeIncoming(_ transfer: inout Incoming) {
        let id = transfer.snapshot.id
        let wasParked = transfer.snapshot.phase == .waitingForReconnect
        if transfer.sink == nil {
            do {
                transfer.sink = try makeSink(transfer.partialURL, true)
            } catch {
                discardIncoming(id, phase: .failed("write_failed"))
                sendReject(id, reason: "invalid")
                onOutcome?(.failed)
                return
            }
        }
        if wasParked, transfer.written > 0 { onOutcome?(.resumed) }
        transfer.snapshot.phase = .transferring
        transfer.lastActivity = clock()
        transfer.lastProgressSent = transfer.written
        incoming[id] = transfer
        sendAccept(id, resumeOffset: transfer.written)
        report(transfer.snapshot)
    }

    private func handleChunk(_ chunk: DSFileChunk) {
        let id = chunk.transferID
        guard var transfer = incoming[id], transfer.snapshot.phase == .transferring else { return }
        if chunk.offset < transfer.written { return }
        guard chunk.offset == transfer.written,
              transfer.written + UInt64(chunk.data.count) <= transfer.snapshot.size,
              let sink = transfer.sink else {
            sendCancel(id, reason: "error")
            discardIncoming(id, phase: .failed("out_of_order"))
            onOutcome?(.failed)
            return
        }
        do {
            try sink.write(chunk.data)
        } catch {
            if isDiskFull(error) {
                sendCancel(id, reason: "disk_full")
                discardIncoming(id, phase: .failed("disk_full"))
            } else {
                sendComplete(id, ok: false, error: "write_failed", savedAs: "")
                discardIncoming(id, phase: .failed("write_failed"))
            }
            onOutcome?(.failed)
            return
        }
        transfer.hasher.update(data: chunk.data)
        transfer.written += UInt64(chunk.data.count)
        transfer.snapshot.transferred = transfer.written
        transfer.lastActivity = clock()
        let reachedEnd = transfer.written == transfer.snapshot.size
        if transfer.written - transfer.lastProgressSent >= configuration.progressInterval || reachedEnd {
            var progress = DSFileProgress()
            progress.transferID = id
            progress.received = transfer.written
            send(.fileProgress(progress))
            transfer.lastProgressSent = transfer.written
            incoming[id] = transfer
            report(transfer.snapshot)
        } else {
            incoming[id] = transfer
        }
    }

    private func handleEnd(_ end: DSFileEnd) {
        let id = end.transferID
        if let finished = finishedIncoming.first(where: { $0.id == id }) {
            send(.fileComplete(finished.complete))
            return
        }
        guard var transfer = incoming[id] else { return }
        guard transfer.written == transfer.snapshot.size else {
            onLog?("file: \(id) End before all bytes (\(transfer.written)/\(transfer.snapshot.size)); waiting for a re-offer")
            return
        }
        transfer.sink?.close()
        transfer.sink = nil
        incoming[id] = nil
        guard Data(transfer.hasher.finalize()) == end.sha256 else {
            try? fileManager.removeItem(at: transfer.partialURL)
            finishIncoming(transfer, ok: false, error: "checksum_mismatch", saved: nil)
            return
        }
        do {
            try fileManager.createDirectory(at: configuration.downloadsDirectory, withIntermediateDirectories: true)
            let destination = FileNameSanitizer.uniqueURL(for: transfer.snapshot.name, in: configuration.downloadsDirectory)
            try fileManager.moveItem(at: transfer.partialURL, to: destination)
            try? fileManager.setAttributes([.posixPermissions: 0o644], ofItemAtPath: destination.path)
            if transfer.modifiedAtMs > 0 {
                let date = Date(timeIntervalSince1970: TimeInterval(transfer.modifiedAtMs) / 1000)
                try? fileManager.setAttributes([.modificationDate: date], ofItemAtPath: destination.path)
            }
            finishIncoming(transfer, ok: true, error: "", saved: destination)
        } catch {
            try? fileManager.removeItem(at: transfer.partialURL)
            finishIncoming(transfer, ok: false, error: "write_failed", saved: nil)
        }
    }

    private func finishIncoming(_ transfer: Incoming, ok: Bool, error: String, saved: URL?) {
        var transfer = transfer
        // File URLs decompose names (NFD); report the saved name in NFC, as it was sanitized.
        let savedName = saved?.lastPathComponent.precomposedStringWithCanonicalMapping
        let complete = sendComplete(transfer.snapshot.id, ok: ok, error: error, savedAs: savedName ?? "")
        finishedIncoming.append((transfer.snapshot.id, complete))
        if finishedIncoming.count > 64 { finishedIncoming.removeFirst(finishedIncoming.count - 64) }
        if ok {
            transfer.snapshot.phase = .completed
            transfer.snapshot.savedName = savedName
            transfer.snapshot.localURL = saved
            onOutcome?(.received)
        } else {
            transfer.snapshot.phase = .failed(error)
            onOutcome?(.failed)
        }
        report(transfer.snapshot)
    }

    private func handlePeerCancel(_ cancel: DSFileCancel) {
        let reason = cancel.reason.isEmpty ? "cancelled" : cancel.reason
        if var transfer = active, transfer.snapshot.id == cancel.transferID {
            try? transfer.handle?.close()
            active = nil
            transfer.snapshot.phase = .cancelled(reason)
            onOutcome?(.failed)
            report(transfer.snapshot)
            startNextIfIdle()
        } else if incoming[cancel.transferID] != nil {
            discardIncoming(cancel.transferID, phase: .cancelled(reason))
            onOutcome?(.failed)
        }
    }

    private func discardIncoming(_ id: String, phase: FileTransferSnapshot.Phase) {
        guard var transfer = incoming.removeValue(forKey: id) else { return }
        transfer.sink?.close()
        try? fileManager.removeItem(at: transfer.partialURL)
        transfer.snapshot.phase = phase
        report(transfer.snapshot)
    }

    // MARK: Wire helpers

    private func sendAccept(_ id: String, resumeOffset: UInt64) {
        var accept = DSFileAccept()
        accept.transferID = id
        accept.resumeOffset = resumeOffset
        send(.fileAccept(accept))
    }

    private func sendReject(_ id: String, reason: String) {
        var reject = DSFileReject()
        reject.transferID = id
        reject.reason = reason
        send(.fileReject(reject))
    }

    private func sendCancel(_ id: String, reason: String) {
        var cancel = DSFileCancel()
        cancel.transferID = id
        cancel.reason = reason
        send(.fileCancel(cancel))
    }

    @discardableResult
    private func sendComplete(_ id: String, ok: Bool, error: String, savedAs: String) -> DSFileComplete {
        var complete = DSFileComplete()
        complete.transferID = id
        complete.ok = ok
        complete.error = error
        complete.savedAs = savedAs
        send(.fileComplete(complete))
        return complete
    }

    private func isDiskFull(_ error: Error) -> Bool {
        let nsError = error as NSError
        if nsError.domain == NSPOSIXErrorDomain, nsError.code == Int(ENOSPC) { return true }
        if nsError.domain == NSCocoaErrorDomain, nsError.code == NSFileWriteOutOfSpaceError { return true }
        if let underlying = nsError.userInfo[NSUnderlyingErrorKey] as? Error { return isDiskFull(underlying) }
        return false
    }
}
