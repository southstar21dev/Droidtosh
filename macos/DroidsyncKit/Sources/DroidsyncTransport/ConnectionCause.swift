import Foundation

/// Why the Mac has no phone connected, in plain words, derived from the transport's state and the
/// diagnostics log. The same idea as the phone's disconnect cause.
public struct ConnectionCause: Equatable, Sendable {
    public enum Kind: String, Sendable {
        case waitingForPhone
        case phoneNotOnNetwork
        case relayUnreachable
        case relayCertificateChanged
        case relayNeedsUpdate
        case localNetworkUnavailable
        case pairingExpired
    }

    public let kind: Kind
    public let headline: String
    public let detail: String

    /// A phone failing the pairing-key check this recently, and not connecting since, means its pairing is stale.
    public static let pairingExpiredWindow: TimeInterval = 600

    /// The cause for `state`, or nil when connected, stopped, or merely connecting.
    /// `hasRelay` is false in local-only mode; `localListening` is whether the LAN listener is up.
    public static func derive(
        state: RelayTransport.State,
        hasRelay: Bool,
        localListening: Bool,
        pairingExpired: Bool
    ) -> ConnectionCause? {
        switch state {
        case .stopped, .connected:
            return nil
        case .connecting, .waitingForPhone:
            if pairingExpired {
                return ConnectionCause(
                    kind: .pairingExpired,
                    headline: "Pairing expired",
                    detail: "The phone's pairing key no longer matches. Pair the phone again."
                )
            }
            if !hasRelay {
                return ConnectionCause(
                    kind: .phoneNotOnNetwork,
                    headline: "Phone not on this network (local mode)",
                    detail: "Connect the phone to the same Wi-Fi as this Mac, or choose a relay."
                )
            }
            guard state == .waitingForPhone else { return nil }
            return ConnectionCause(
                kind: .waitingForPhone,
                headline: "Mac is waiting for the phone",
                detail: "The phone connects when it is awake and online."
            )
        case .failed:
            if !hasRelay {
                return ConnectionCause(
                    kind: .localNetworkUnavailable,
                    headline: "Local network unavailable",
                    detail: "Droidtosh could not listen on the local network. It keeps retrying."
                )
            }
            return ConnectionCause(
                kind: .relayUnreachable,
                headline: "Relay unreachable",
                detail: localListening
                    ? "The phone can still connect directly on the local network."
                    : "Check this Mac's internet connection and the relay address."
            )
        case .relayCertificateMismatch:
            return ConnectionCause(
                kind: .relayCertificateChanged,
                headline: "Relay certificate changed",
                detail: "Check the fingerprint on the Connection page, then reconnect."
            )
        case .relayRequiresUpgrade:
            return ConnectionCause(
                kind: .relayNeedsUpdate,
                headline: "Relay needs a newer Droidtosh",
                detail: "Update the app, then reconnect."
            )
        }
    }

    /// True when the log shows a phone failing the pairing-key check within `window` and no phone
    /// session starting after it.
    public static func pairingExpiredRecently(
        events: [DiagnosticsEvent],
        now: Date,
        window: TimeInterval = pairingExpiredWindow
    ) -> Bool {
        guard let failure = events.last(where: { $0.kind == .authFailure }),
              now.timeIntervalSince(failure.date) <= window
        else { return false }
        return !events.contains { $0.date > failure.date && $0.message == "Phone session started" }
    }
}
