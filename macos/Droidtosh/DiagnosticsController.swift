import AppKit
import Foundation
import UniformTypeIdentifiers
import DroidsyncKit

/// Publishes a coalesced copy of the transport's diagnostics while the Diagnostics view is
/// visible (once a second), and builds the redacted report for copying and exporting.
@MainActor
final class DiagnosticsController: ObservableObject {
    @Published private(set) var snapshot: DiagnosticsSnapshot
    @Published private(set) var context: DiagnosticsContext

    private let connection: ConnectionController
    private let pairing: PairingController
    private var timer: Timer?
    private var viewers = 0
    #if DEBUG
    /// Replaces the live context while `SnapshotRenderer` draws the view.
    var snapshotContext: DiagnosticsContext? {
        didSet { if let snapshotContext { context = snapshotContext } }
    }
    #endif

    init(connection: ConnectionController, pairing: PairingController) {
        self.connection = connection
        self.pairing = pairing
        snapshot = connection.diagnostics.snapshot()
        context = Self.makeContext(connection: connection, pairing: pairing)
    }

    /// Starts refreshing; balanced by `stopUpdating` when the view disappears.
    func startUpdating() {
        viewers += 1
        refresh()
        guard timer == nil else { return }
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func stopUpdating() {
        viewers = max(viewers - 1, 0)
        guard viewers == 0 else { return }
        timer?.invalidate()
        timer = nil
    }

    func refresh() {
        let latest = connection.diagnostics.snapshot()
        var latestContext = Self.makeContext(connection: connection, pairing: pairing)
        #if DEBUG
        if let snapshotContext { latestContext = snapshotContext }
        #endif
        if latest != snapshot { snapshot = latest }
        if latestContext != context { context = latestContext }
    }

    func reconnectNow() {
        connection.reconnectNow()
        refresh()
    }

    /// The plain-text report; secrets that slip into event messages are redacted too.
    func report() -> String {
        refresh()
        return DiagnosticsRedaction.redact(
            DiagnosticsReport.render(snapshot, context: context),
            secrets: secrets
        )
    }

    func copyReport() {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(report(), forType: .string)
    }

    /// Saves the report followed by the redacted debug log (current and rotated) as one text file.
    func exportLogs() {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.plainText]
        let stamp = ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":", with: "-")
        panel.nameFieldStringValue = "Droidtosh-diagnostics-\(stamp).txt"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        var text = report()
        for logURL in [DebugLog.rotatedFileURL, DebugLog.fileURL] {
            guard let log = try? String(contentsOf: logURL, encoding: .utf8) else { continue }
            text += "\n===== \(logURL.lastPathComponent) =====\n"
            text += DiagnosticsRedaction.redact(log, secrets: secrets)
        }
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
        } catch {
            DebugLog.log("diagnostics: export failed: \(error)")
            NSAlert(error: error).runModal()
        }
    }

    private var secrets: [String] {
        var secrets = pairing.redactionSecrets
        if let fingerprint = context.relayFingerprint { secrets.append(fingerprint) }
        if let fingerprint = context.macFingerprint { secrets.append(fingerprint) }
        return secrets
    }

    private static func makeContext(connection: ConnectionController, pairing: PairingController) -> DiagnosticsContext {
        let negotiation = connection.currentNegotiation
        return DiagnosticsContext(
            appVersion: BuildConfig.version,
            macOSVersion: ProcessInfo.processInfo.operatingSystemVersionString,
            relayHost: connection.relayHost,
            relayPort: connection.relayPortText,
            relayFingerprint: connection.pinnedRelayFingerprint,
            macFingerprint: pairing.macFingerprint,
            negotiatedVersion: negotiation?.version,
            capabilities: negotiation?.capabilities ?? [],
            reliability: connection.reliabilityCounters,
            outboxDepth: connection.outboxDepth
        )
    }
}
