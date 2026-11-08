import AppKit
import Foundation
import DroidsyncKit

/// Owns file transfer with the phone (PROTOCOL-v2.md §7): files dropped or picked on the Mac
/// go to the phone, and files from the phone land in ~/Downloads.
///
/// The engine does blocking file I/O and hashing, so it lives on its own serial queue and
/// sends straight to the transport; the UI gets coalesced snapshots on the main actor.
@MainActor
final class FilesController: ObservableObject {
    @Published var receivingEnabled = UserDefaults.standard.object(forKey: "files-receive-enabled") as? Bool ?? true {
        didSet {
            guard receivingEnabled != oldValue else { return }
            UserDefaults.standard.set(receivingEnabled, forKey: "files-receive-enabled")
            let enabled = receivingEnabled
            queue.async { [state] in state.receivingEnabled = enabled }
        }
    }
    /// Newest first; finished transfers are kept until cleared (at most `historyLimit`).
    @Published private(set) var transfers: [FileTransferSnapshot] = []

    let downloadsDirectory: URL
    private static let historyLimit = 50

    private let connection: ConnectionController
    private let presenter: NotificationPresenter
    private let queue = DispatchQueue(label: "dev.southstar21.droidtosh.files", qos: .userInitiated)
    /// Zips folders, off `queue` so transfers keep moving meanwhile.
    private let zipQueue = DispatchQueue(label: "dev.southstar21.droidtosh.files.zip", qos: .userInitiated)
    /// Temporary folder archives; each lives in its own subdirectory until its transfer finishes.
    private let zipDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("Droidtosh-folders", isDirectory: true)
    /// Folders being zipped, by the transfer id they'll be sent under.
    private var compressing: [String: CancelFlag] = [:]
    /// Everything below is touched only on `queue`.
    private let state: QueueState
    private var tickTimer: DispatchSourceTimer?

    /// Set from the main actor to stop a folder being zipped.
    private final class CancelFlag: @unchecked Sendable {
        private let lock = NSLock()
        private var cancelled = false
        var isCancelled: Bool { lock.withLock { cancelled } }
        func cancel() { lock.withLock { cancelled = true } }
    }

    /// State confined to `queue`.
    private final class QueueState: @unchecked Sendable {
        var engine: FileTransferEngine!
        var receivingEnabled = true
        var pendingSnapshots: [String: FileTransferSnapshot] = [:]
        var flushScheduled = false
    }

    init(connection: ConnectionController, presenter: NotificationPresenter) {
        self.connection = connection
        self.presenter = presenter
        let fileManager = FileManager.default
        downloadsDirectory = fileManager.urls(for: .downloadsDirectory, in: .userDomainMask).first
            ?? fileManager.homeDirectoryForCurrentUser.appendingPathComponent("Downloads")
        let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first ?? fileManager.temporaryDirectory
        let configuration = FileTransferEngine.Configuration(
            downloadsDirectory: downloadsDirectory,
            partialDirectory: caches.appendingPathComponent("Droidtosh/partial-files")
        )
        let state = QueueState()
        state.receivingEnabled = UserDefaults.standard.object(forKey: "files-receive-enabled") as? Bool ?? true
        self.state = state
        let sender = connection.sender
        let diagnostics = connection.diagnostics
        queue.async { [weak self] in
            let engine = FileTransferEngine(configuration: configuration) { payload in
                sender.send(protocolEnvelope(payload))
            }
            engine.isReceivingEnabled = { [state] in state.receivingEnabled }
            engine.onOutcome = { outcome in diagnostics.recordFileTransfer(outcome) }
            engine.onLog = { message in DebugLog.log(message) }
            engine.onChange = { [weak self] snapshot in self?.queueSnapshot(snapshot) }
            state.engine = engine
        }
        // Archives left by a previous run whose transfers never finished.
        zipQueue.async { [zipDirectory] in try? FileManager.default.removeItem(at: zipDirectory) }
    }

    /// Whether the connected phone negotiated file transfer.
    var phoneSupportsFiles: Bool {
        connection.phoneConnected && connection.negotiatedCapabilities.contains(ProtocolV2.Capability.files)
    }

    var activeTransfers: [FileTransferSnapshot] { transfers.filter { !$0.phase.isFinished } }

    func start() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [state] in state.engine?.tick() }
        timer.resume()
        tickTimer = timer
    }

    /// The phone session is up (after the Resume exchange).
    func phoneConnected(capabilities: [String]) {
        let supported = capabilities.contains(ProtocolV2.Capability.files)
        queue.async { [state] in
            if supported {
                state.engine?.sessionStarted()
            } else {
                state.engine?.sessionStartedWithoutFiles()
            }
        }
    }

    func phoneDisconnected() {
        queue.async { [state] in state.engine?.sessionEnded() }
    }

    /// Queues files for the phone; folders are zipped first. Unreadable files are reported and skipped.
    func send(urls: [URL]) {
        for url in urls {
            if FolderArchive.isFolder(url) {
                sendFolder(url)
                continue
            }
            queue.async { [weak self, state] in
                do {
                    _ = try state.engine.enqueue(fileAt: url)
                } catch {
                    let reason: String
                    switch error as? FileTransferError {
                    case .notARegularFile?: reason = "Only files and folders can be sent."
                    case .tooLarge?: reason = "Files are limited to 2 GB."
                    default: reason = "The file couldn't be read."
                    }
                    DebugLog.log("file: not sending \(url.lastPathComponent): \(error)")
                    DispatchQueue.main.async {
                        self?.presenter.postLocal(
                            title: "Can't send \(url.lastPathComponent)",
                            body: reason,
                            identifier: "file.error.\(UUID().uuidString)"
                        )
                    }
                }
            }
        }
    }

    /// Zips `folder` to `<folder>.zip` on `zipQueue`, showing it as compressing meanwhile, then
    /// queues the archive; it's deleted once the transfer finishes or is cancelled.
    private func sendFolder(_ folder: URL) {
        let id = UUID().uuidString
        let name = FolderArchive.archiveName(for: folder)
        let flag = CancelFlag()
        compressing[id] = flag
        apply([FileTransferSnapshot(
            id: id, direction: .outgoing, name: name, size: 0, transferred: 0,
            phase: .compressing, savedName: nil, localURL: folder
        )])
        DebugLog.log("file: zipping \(folder.lastPathComponent)")
        zipQueue.async { [weak self, queue, state, zipDirectory] in
            let archive: URL
            do {
                archive = try FolderArchive.zip(folder: folder, temporaryDirectory: zipDirectory, isCancelled: { flag.isCancelled })
            } catch {
                DebugLog.log("file: not sending folder \(folder.lastPathComponent): \(error)")
                let phase: FileTransferSnapshot.Phase
                switch error as? FileTransferError {
                case .cancelled?: phase = .cancelled("user")
                case .tooLarge?: phase = .failed("zip_too_large")
                default: phase = .failed("zip_failed")
                }
                DispatchQueue.main.async { MainActor.assumeIsolated { self?.finishCompressing(id: id, name: name, folder: folder, phase: phase) } }
                return
            }
            queue.async {
                var phase: FileTransferSnapshot.Phase?
                if flag.isCancelled {
                    phase = .cancelled("user")
                } else {
                    do {
                        // From here the engine reports it under the same id, and deletes the archive.
                        try state.engine.enqueue(fileAt: archive, name: name, id: id, removingWhenFinished: archive.deletingLastPathComponent())
                    } catch {
                        DebugLog.log("file: not sending \(name): \(error)")
                        phase = .failed(error as? FileTransferError == .tooLarge ? "zip_too_large" : "unreadable")
                    }
                }
                if phase != nil { try? FileManager.default.removeItem(at: archive.deletingLastPathComponent()) }
                DispatchQueue.main.async {
                    MainActor.assumeIsolated {
                        if let phase {
                            self?.finishCompressing(id: id, name: name, folder: folder, phase: phase)
                        } else {
                            self?.compressing[id] = nil
                        }
                    }
                }
            }
        }
    }

    private func finishCompressing(id: String, name: String, folder: URL, phase: FileTransferSnapshot.Phase) {
        compressing[id] = nil
        apply([FileTransferSnapshot(
            id: id, direction: .outgoing, name: name, size: 0, transferred: 0,
            phase: phase, savedName: nil, localURL: folder
        )])
    }

    /// Opens a picker and sends the chosen files and folders.
    func pickAndSend() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = true
        panel.prompt = "Send to Phone"
        NSApp.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK else { return }
        send(urls: panel.urls)
    }

    func cancel(id: String) {
        // A folder still being zipped stops; one just zipped isn't queued (see `sendFolder`).
        compressing[id]?.cancel()
        queue.async { [state] in state.engine.cancel(id: id) }
    }

    func clearFinished() {
        transfers.removeAll { $0.phase.isFinished }
    }

    func reveal(id: String) {
        guard let url = transfers.first(where: { $0.id == id })?.localURL else {
            NSWorkspace.shared.open(downloadsDirectory)
            return
        }
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    /// Opens the folder files from the phone are saved to.
    func showDownloads() {
        NSWorkspace.shared.open(downloadsDirectory)
    }

    /// Handles a click on a file notification (`file:<id>`).
    func handleNotificationResponse(key: String) {
        reveal(id: String(key.dropFirst("file:".count)))
    }

    func handle(_ envelope: DSEnvelope) {
        guard let payload = envelope.payload else { return }
        queue.async { [state] in state.engine.handle(payload) }
    }

    // MARK: Snapshot delivery

    /// On `queue`: coalesces progress updates to a few per second; finished states go out at once.
    private nonisolated func queueSnapshot(_ snapshot: FileTransferSnapshot) {
        state.pendingSnapshots[snapshot.id] = snapshot
        if snapshot.phase.isFinished {
            flushSnapshots()
            return
        }
        guard !state.flushScheduled else { return }
        state.flushScheduled = true
        queue.asyncAfter(deadline: .now() + 0.2) { [weak self] in self?.flushSnapshots() }
    }

    private nonisolated func flushSnapshots() {
        state.flushScheduled = false
        let batch = Array(state.pendingSnapshots.values)
        state.pendingSnapshots.removeAll()
        guard !batch.isEmpty else { return }
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated { self?.apply(batch) }
        }
    }

    private func apply(_ batch: [FileTransferSnapshot]) {
        for snapshot in batch {
            let previous = transfers.firstIndex { $0.id == snapshot.id }
            let wasFinished = previous.map { transfers[$0].phase.isFinished } ?? false
            if let previous {
                transfers[previous] = snapshot
            } else {
                transfers.insert(snapshot, at: 0)
            }
            if snapshot.phase.isFinished, !wasFinished { notifyFinished(snapshot) }
        }
        if transfers.count > Self.historyLimit {
            let excess = transfers.count - Self.historyLimit
            let finishedIDs = transfers.reversed().filter { $0.phase.isFinished }.prefix(excess).map(\.id)
            transfers.removeAll { finishedIDs.contains($0.id) }
        }
    }

    private func notifyFinished(_ snapshot: FileTransferSnapshot) {
        let title: String
        let body: String
        switch (snapshot.direction, snapshot.phase) {
        case (.incoming, .completed):
            title = "File received"
            body = "\(snapshot.savedName ?? snapshot.name) — saved to Downloads"
        case (.outgoing, .completed):
            title = "Sent to phone"
            body = snapshot.savedName.map { "Saved as \($0)" } ?? snapshot.name
        case (_, .cancelled("user")):
            return
        case (_, .failed(let reason)), (_, .cancelled(let reason)):
            title = snapshot.direction == .incoming ? "File from phone failed" : "Couldn't send file"
            body = "\(snapshot.name): \(Self.describe(reason))"
        default:
            return
        }
        presenter.postLocal(title: title, body: body, identifier: "file:\(snapshot.id)")
    }

    static func describe(_ reason: String) -> String {
        switch reason {
        case "too_large": return "too large"
        case "disk_full": return "not enough space"
        case "disabled": return "receiving files is turned off"
        case "busy": return "too many transfers at once"
        case "checksum_mismatch": return "the file arrived damaged"
        case "write_failed": return "couldn't be saved"
        case "source_changed": return "the file changed while sending"
        case "expired": return "timed out waiting for the phone"
        case "unsupported": return "not supported by the phone"
        case "zip_too_large": return "over 2 GB when zipped"
        case "zip_failed": return "the folder couldn't be zipped"
        default: return reason.replacingOccurrences(of: "_", with: " ")
        }
    }
}

#if DEBUG
extension FilesController {
    /// Fake transfers for `SnapshotRenderer`.
    func applySnapshotFixture(transfers: [FileTransferSnapshot]) {
        self.transfers = transfers
    }
}
#endif
