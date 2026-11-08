import Foundation
import DroidsyncKit

/// Owns clipboard sync: polling the pasteboard, sending text and images to the phone, and
/// assembling and applying the phone's clipboard.
@MainActor
final class ClipboardController: ObservableObject {
    @Published var enabled = UserDefaults.standard.object(forKey: "clipboard-enabled") as? Bool ?? true {
        didSet {
            guard enabled != oldValue else { return }
            UserDefaults.standard.set(enabled, forKey: "clipboard-enabled")
        }
    }
    @Published private(set) var phoneMetered = true
    private(set) var pendingImage: Data?

    private let connection: ConnectionController
    private let presenter: NotificationPresenter
    private let systemPasteboard = SystemPasteboard()
    private var assembler = ClipboardAssembler()
    private var timer: Timer?
    private lazy var monitor: ClipboardMonitor = {
        let monitor = ClipboardMonitor(pasteboard: systemPasteboard)
        monitor.isEnabled = { [weak self] in self?.enabled ?? false }
        monitor.isConnected = { [weak self] in self?.connection.relayConnected ?? false }
        monitor.isPhoneMetered = { [weak self] in self?.phoneMetered ?? true }
        monitor.onEvent = { [weak self] event in self?.handleClipboardEvent(event) }
        return monitor
    }()

    init(connection: ConnectionController, presenter: NotificationPresenter) {
        self.connection = connection
        self.presenter = presenter
    }

    func start() {
        timer?.invalidate()
        let timer = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.monitor.poll()
                if self.assembler.expireIfNeeded() {
                    self.connection.diagnostics.recordClipboardTransfer(.timedOut)
                }
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func phoneDisconnected() {
        phoneMetered = true
    }

    func handle(_ envelope: DSEnvelope) {
        switch envelope.payload {
        case .clipboardText(let message)?:
            DebugLog.log("clipboard: applying text bytes=\(message.text.utf8.count)")
            monitor.noteApplied(Data(message.text.utf8))
            systemPasteboard.writeText(message.text)
        case .clipboardBlobStart(let start)?:
            let accepted = assembler.start(
                transferID: start.transferID,
                mimeType: start.mimeType,
                totalSize: start.totalSize
            )
            DebugLog.log("clipboard: blob start id=\(start.transferID) mime=\(start.mimeType) bytes=\(start.totalSize) accepted=\(accepted)")
            if !accepted { connection.diagnostics.recordClipboardTransfer(.rejected) }
        case .clipboardBlobChunk(let chunk)?:
            let result = assembler.append(
                transferID: chunk.transferID,
                offset: Int(chunk.offset),
                data: chunk.data
            )
            DebugLog.log("clipboard: blob chunk id=\(chunk.transferID) offset=\(chunk.offset) bytes=\(chunk.data.count) result=\(result)")
            if result == .rejected { connection.diagnostics.recordClipboardTransfer(.rejected) }
        case .clipboardBlobEnd(let end)?:
            applyImage(transferID: end.transferID)
        case .phoneStatus(let status)?:
            phoneMetered = status.metered
            DebugLog.log("clipboard: phone metered=\(status.metered)")
        default:
            break
        }
    }

    func sendPendingImage() {
        guard let data = pendingImage else {
            DebugLog.log("clipboard: send action with no pending image")
            return
        }
        guard sendImage(data) else { return }
        presenter.remove(identifiers: ["clipboard.sendImage"])
    }

    private func applyImage(transferID: String) {
        let mimeType = assembler.activeMimeType
        guard let blob = assembler.end(transferID: transferID) else {
            DebugLog.log("clipboard: blob end rejected id=\(transferID)")
            connection.diagnostics.recordClipboardTransfer(.rejected)
            return
        }
        // The pasteboard is written as public.png, so non-PNG images (JPEG) are re-encoded first.
        guard let data = mimeType == "image/png" ? blob : SystemPasteboard.pngData(from: blob) else {
            DebugLog.log("clipboard: blob end undecodable id=\(transferID) mime=\(mimeType ?? "nil")")
            connection.diagnostics.recordClipboardTransfer(.rejected)
            return
        }
        DebugLog.log("clipboard: applying image bytes=\(data.count)")
        connection.diagnostics.recordClipboardTransfer(.completed)
        monitor.noteApplied(data)
        systemPasteboard.writeImage(png: data)
    }

    private func handleClipboardEvent(_ event: ClipboardMonitor.Event) {
        switch event {
        case .text(let text):
            let textBytes = Data(text.utf8)
            guard textBytes.count <= ClipboardTransfer.maxTextBytes else {
                DebugLog.log("clipboard: skipped oversized text bytes=\(textBytes.count) cap=\(ClipboardTransfer.maxTextBytes)")
                return
            }
            var message = DSClipboardText()
            message.text = text
            message.copiedAtMs = Self.nowMs()
            connection.send(protocolEnvelope(.clipboardText(message)))
            DebugLog.log("clipboard: sent text bytes=\(textBytes.count)")
        case .image(let data):
            sendImage(data)
        case .deferredImage(let data):
            pendingImage = data
            DebugLog.log("clipboard: deferred image bytes=\(data.count) metered=true")
            presenter.postLocal(
                title: "Image not sent",
                body: "Phone is on cellular — send to phone?",
                identifier: "clipboard.sendImage",
                categoryIdentifier: "clipboard.sendImage"
            )
        }
    }

    @discardableResult
    private func sendImage(_ data: Data) -> Bool {
        guard !data.isEmpty else { return false }
        guard data.count <= ClipboardTransfer.maxBlobSize else {
            DebugLog.log("clipboard: skipped oversized image bytes=\(data.count) cap=\(ClipboardTransfer.maxBlobSize)")
            presenter.postLocal(
                title: "Image too large to sync",
                body: "Clipboard images are limited to 32 MiB.",
                identifier: "clipboard.tooLarge"
            )
            return false
        }
        guard connection.isTransportConnected else {
            DebugLog.log("clipboard: image not sent, relay not connected bytes=\(data.count)")
            return false
        }
        let transferID = UUID().uuidString
        let chunks = ClipboardChunker.chunks(for: data)
        var start = DSClipboardBlobStart()
        start.transferID = transferID
        start.mimeType = "image/png"
        start.totalSize = UInt64(data.count)
        start.copiedAtMs = Self.nowMs()
        connection.send(protocolEnvelope(.clipboardBlobStart(start)))
        for chunk in chunks {
            var message = DSClipboardBlobChunk()
            message.transferID = transferID
            message.offset = UInt32(chunk.offset)
            message.data = chunk.data
            connection.send(protocolEnvelope(.clipboardBlobChunk(message)))
        }
        var end = DSClipboardBlobEnd()
        end.transferID = transferID
        connection.send(protocolEnvelope(.clipboardBlobEnd(end)))
        monitor.noteSent(data)
        pendingImage = nil
        DebugLog.log("clipboard: sent image bytes=\(data.count) chunks=\(chunks.count)")
        return true
    }

    private static func nowMs() -> UInt64 {
        UInt64(Date().timeIntervalSince1970 * 1000)
    }
}
