import Foundation

/// A `tel:` link opened while the phone is not connected yet. Right after launch the phone needs
/// seconds to connect, so the link waits (latest one only) instead of failing at once.
public struct PendingDial: Equatable, Sendable {
    /// How long a dial waits for the phone, and how long after launch a link counts as a start-up link.
    public static let grace: TimeInterval = 20

    public let link: String
    public let deadline: Date

    public init(link: String, deadline: Date) {
        self.link = link
        self.deadline = deadline
    }

    /// Whether a link that arrives now, with no phone connected, should wait for the phone: only
    /// while the app is still starting up and has not had a phone yet.
    public static func shouldWait(now: Date, launchedAt: Date, hasHadPhone: Bool) -> Bool {
        !hasHadPhone && now.timeIntervalSince(launchedAt) < grace
    }

    /// The link to dial, or nil when the wait has expired.
    public func usable(now: Date) -> String? {
        now <= deadline ? link : nil
    }
}
