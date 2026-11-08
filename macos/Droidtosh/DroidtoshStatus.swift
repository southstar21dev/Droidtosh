import SwiftUI

/// The connection state shown in the popover, the sidebar and the overview, derived from the
/// connection's status line.
enum DroidtoshStatus: Equatable {
    case connected
    case connecting
    case offline
    case notPaired

    init(statusText: String) {
        let lower = statusText.lowercased()
        if lower == "connected" {
            self = .connected
        } else if lower.contains("not paired") || lower.contains("not configured") {
            self = .notPaired
        } else if lower.contains("disconnected") || lower.contains("offline")
            || lower.contains("failed") || lower.contains("error")
            || lower.contains("unreachable") || lower == "stopped"
            || lower.contains("relay certificate") || lower.contains("relay needs")
            || lower.contains("invalid") {
            self = .offline
        } else {
            self = .connecting
        }
    }

    var title: String {
        switch self {
        case .connected: return "Connected"
        case .connecting: return "Connecting"
        case .offline: return "Offline"
        case .notPaired: return "Not paired"
        }
    }

    var color: Color {
        switch self {
        case .connected: return .green
        case .connecting: return .orange
        case .offline: return .red
        case .notPaired: return .secondary
        }
    }

    var symbol: String {
        switch self {
        case .connected: return "checkmark.circle.fill"
        case .connecting: return "arrow.triangle.2.circlepath"
        case .offline: return "exclamationmark.circle.fill"
        case .notPaired: return "link.badge.plus"
        }
    }
}
