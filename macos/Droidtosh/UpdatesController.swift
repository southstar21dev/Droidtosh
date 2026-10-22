import AppKit
import Foundation
import DroidsyncKit

/// Over-the-air updates from GitHub releases (docs/RELEASING.md): checks at launch and every 6
/// hours, and installs only when the user asks. An update is downloaded, checked against the
/// manifest's SHA-256 and the running app's code-signing requirement, swapped in place of this
/// bundle and relaunched.
@MainActor
final class UpdatesController: ObservableObject {
    enum State: Equatable {
        case idle
        case checking
        case upToDate
        case available(AppUpdates.Offer)
        case downloading(AppUpdates.Offer)
        case installing(AppUpdates.Offer)
        case failed(String)
    }

    static let checkInterval: TimeInterval = 6 * 60 * 60

    @Published private(set) var state: State = .idle
    @Published private(set) var lastChecked: Date?
    @Published var includeBeta: Bool = UserDefaults.standard.bool(forKey: "updates-include-beta") {
        didSet {
            guard includeBeta != oldValue else { return }
            UserDefaults.standard.set(includeBeta, forKey: "updates-include-beta")
            check()
        }
    }

    var offer: AppUpdates.Offer? {
        switch state {
        case .available(let offer), .downloading(let offer), .installing(let offer): return offer
        case .idle, .checking, .upToDate, .failed: return nil
        }
    }

    private var timer: Timer?
    private var checkTask: Task<Void, Never>?
    private var installTask: Task<Void, Never>?

    func start() {
        #if DEBUG
        if SnapshotRenderer.isActive { return }
        #endif
        check()
        timer = Timer.scheduledTimer(withTimeInterval: Self.checkInterval, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.check(quietly: true) }
        }
    }

    /// `quietly` keeps an offer or an error on screen instead of flashing "Checking…".
    func check(quietly: Bool = false) {
        switch state {
        case .downloading, .installing: return
        default: break
        }
        guard let repo = BuildConfig.updateRepo else {
            state = .failed("No update source is configured for this build")
            return
        }
        checkTask?.cancel()
        if !quietly { state = .checking }
        let includeBeta = includeBeta
        let installed = BuildConfig.semVer
        checkTask = Task { [weak self] in
            do {
                let releases = try await AppUpdates.releases(repo: repo, fetch: Self.fetch)
                let offer = await AppUpdates.select(from: releases, includeBeta: includeBeta, installed: installed) { url in
                    try AppUpdates.decodeManifest(try await Self.fetch(url))
                }
                guard !Task.isCancelled, let self else { return }
                self.lastChecked = Date()
                self.state = offer.map { .available($0) } ?? .upToDate
                if let offer { DebugLog.log("updates: \(offer.version) (\(offer.build)) available") }
            } catch {
                guard !Task.isCancelled, let self else { return }
                DebugLog.log("updates: check failed: \(error)")
                if !quietly || self.offer == nil { self.state = .failed(Self.describe(error)) }
            }
        }
    }

    func install() {
        guard case .available(let offer) = state else { return }
        guard let requirement = AppUpdates.runningAppRequirement() else {
            state = .failed("This build isn't signed, so updates can't be verified")
            return
        }
        state = .downloading(offer)
        installTask = Task { [weak self] in
            do {
                let archive = try await Self.download(offer.download)
                self?.state = .installing(offer)
                let newApp = try await Task.detached {
                    try Self.prepare(archive: archive, offer: offer, requirement: requirement)
                }.value
                try Self.swapAndRelaunch(with: newApp)
                NSApp.terminate(nil)
            } catch {
                DebugLog.log("updates: install failed: \(error)")
                self?.state = .failed(Self.describe(error))
            }
        }
    }

    // MARK: Steps

    nonisolated private static func fetch(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("Droidtosh/\(BuildConfig.build)", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw AppUpdates.HTTPError(status: http.statusCode)
        }
        return data
    }

    nonisolated private static func download(_ url: URL) async throws -> URL {
        let (file, response) = try await URLSession.shared.download(from: url)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw AppUpdates.HTTPError(status: http.statusCode)
        }
        // The system deletes `file` once this returns; keep a copy until it is unpacked.
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent("droidtosh-update-\(UUID().uuidString).zip")
        try FileManager.default.moveItem(at: file, to: destination)
        return destination
    }

    /// Verifies the archive and unpacks it next to the running app (same volume, so the swap is a
    /// rename); returns the unpacked bundle.
    nonisolated private static func prepare(archive: URL, offer: AppUpdates.Offer, requirement: SecRequirement) throws -> URL {
        defer { try? FileManager.default.removeItem(at: archive) }
        guard try AppUpdates.sha256Hex(ofFileAt: archive) == offer.sha256 else {
            throw AppUpdates.VerificationError.checksumMismatch
        }
        let current = Bundle.main.bundleURL
        let staging = current.deletingLastPathComponent().appendingPathComponent(".droidtosh-update-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        let ditto = Process()
        ditto.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        ditto.arguments = ["-x", "-k", archive.path, staging.path]
        try ditto.run()
        ditto.waitUntilExit()
        guard ditto.terminationStatus == 0,
              let app = try FileManager.default.contentsOfDirectory(at: staging, includingPropertiesForKeys: nil)
                .first(where: { $0.pathExtension == "app" }) else {
            try? FileManager.default.removeItem(at: staging)
            throw AppUpdates.VerificationError.notABundle
        }
        do {
            try AppUpdates.verifyBundle(
                at: app,
                bundleIdentifier: Bundle.main.bundleIdentifier ?? "",
                installedBuild: BuildConfig.build,
                requirement: requirement
            )
        } catch {
            try? FileManager.default.removeItem(at: staging)
            throw error
        }
        return app
    }

    /// A detached shell script waits for this process to exit, moves the new bundle into place
    /// (restoring the old one if that fails) and opens it.
    private static func swapAndRelaunch(with newApp: URL) throws {
        let current = Bundle.main.bundleURL.path
        let backup = current + ".old-\(UUID().uuidString)"
        let script = """
        while kill -0 \(ProcessInfo.processInfo.processIdentifier) 2>/dev/null; do sleep 0.2; done
        if mv "$CURRENT" "$BACKUP" && mv "$NEW" "$CURRENT"; then
          rm -rf "$BACKUP" "$STAGING"
        else
          [ -d "$BACKUP" ] && [ ! -d "$CURRENT" ] && mv "$BACKUP" "$CURRENT"
        fi
        open "$CURRENT"
        """
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        process.arguments = ["-c", script]
        process.environment = [
            "CURRENT": current,
            "BACKUP": backup,
            "NEW": newApp.path,
            "STAGING": newApp.deletingLastPathComponent().path,
        ]
        try process.run()
    }

    nonisolated private static func describe(_ error: Error) -> String {
        switch error {
        case let error as AppUpdates.VerificationError: return error.description
        case let error as AppUpdates.HTTPError:
            switch error.status {
            case 404: return "No releases found (the update repository may be private)"
            case 403: return "GitHub is rate-limiting update checks; try again later"
            default: return "GitHub answered \(error.status)"
            }
        default: return error.localizedDescription
        }
    }
}
