import Foundation

public struct DedupConfig: Equatable {
    public static let presets: [String: [String]] = [
        "com.whatsapp": ["net.whatsapp.WhatsApp"],
        "org.telegram.messenger": ["ru.keepcoder.Telegram", "org.telegram.desktop", "com.tdesktop.Telegram"],
    ]

    /// Android apps whose calls and notifications are never suppressed, whatever the mappings
    /// say. WhatsApp Business has no Mac app: the regular WhatsApp desktop app doesn't ring for
    /// Business calls, so mapping it there would hide them completely. Packages are matched
    /// exactly, so `com.whatsapp` is not a prefix of it.
    public static let neverSuppressed: Set<String> = ["com.whatsapp.w4b"]

    public var enabled: Bool
    public var mappings: [String: [String]]
    public var disabledPackages: Set<String>

    public init(
        enabled: Bool = true,
        mappings: [String: [String]] = DedupConfig.presets,
        disabledPackages: Set<String> = []
    ) {
        self.enabled = enabled
        self.mappings = mappings
        self.disabledPackages = disabledPackages
    }
}

public final class DedupEngine {
    public typealias RunningAppsProvider = () -> Set<String>

    private var config: DedupConfig
    private let runningApps: RunningAppsProvider

    public init(config: DedupConfig = DedupConfig(), runningApps: @escaping RunningAppsProvider) {
        self.config = config
        self.runningApps = runningApps
    }

    public func update(config: DedupConfig) {
        self.config = config
    }

    public func shouldSuppress(androidPackage: String) -> Bool {
        guard config.enabled,
              !DedupConfig.neverSuppressed.contains(androidPackage),
              !config.disabledPackages.contains(androidPackage) else { return false }
        guard let bundleIDs = config.mappings[androidPackage] else { return false }
        let running = runningApps()
        return bundleIDs.contains { running.contains($0) }
    }
}
