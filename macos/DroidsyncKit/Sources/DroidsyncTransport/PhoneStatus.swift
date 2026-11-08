import Foundation
import DroidsyncProtocol

/// Limits from PROTOCOL-v2.md §8.
public enum PhoneStatusLimits {
    /// Status older than this is shown greyed with "last seen" (the phone heartbeats every 5 min).
    public static let staleAfter: TimeInterval = 600
    /// Low battery thresholds, highest first (§8.2).
    public static let lowBatteryThresholds: [Int32] = [20, 10]
    /// A fired threshold re-arms once the battery is seen this far above it without a charging
    /// report in between, i.e. it was charged while the Mac wasn't looking.
    public static let rearmMargin: Int32 = 5
}

/// The phone's latest `DeviceStatus` and the low battery alert memory (§8).
///
/// Not thread-safe: use it from one queue (the main actor in the app). The clock is injected
/// so staleness can be tested.
///
/// The "discharge" memory (which thresholds already fired) deliberately survives session
/// ends: a flapping connection must not re-alert for the same discharge. Only charging, or
/// seeing the battery clearly above a threshold again, re-arms it.
public final class PhoneStatusTracker {
    public private(set) var status: DSDeviceStatus?
    /// When the Mac received `status` (Mac clock; `sampled_at_ms` is the phone's).
    public private(set) var receivedAt: Date?
    public private(set) var sessionActive = false
    /// Thresholds already alerted (or skipped) during the current discharge.
    public private(set) var firedThresholds: Set<Int32> = []

    private let clock: () -> Date
    private let staleAfter: TimeInterval
    private var awaitingFirstStatusOfSession = false

    public init(clock: @escaping () -> Date = Date.init, staleAfter: TimeInterval = PhoneStatusLimits.staleAfter) {
        self.clock = clock
        self.staleAfter = staleAfter
    }

    /// A phone session negotiated `phone.status` and finished the Resume exchange.
    public func sessionStarted() {
        sessionActive = true
        awaitingFirstStatusOfSession = true
    }

    /// The phone session ended; the last status stays, shown as "last seen".
    public func sessionEnded() {
        sessionActive = false
        awaitingFirstStatusOfSession = false
    }

    /// Forgets everything (unpair).
    public func reset() {
        status = nil
        receivedAt = nil
        sessionActive = false
        awaitingFirstStatusOfSession = false
        firedThresholds = []
    }

    /// True when there is a status but it is from an ended session or older than `staleAfter`.
    /// A new session's status replaces the old one shortly after Resume; until it arrives, the
    /// one on hand is still from the ended session.
    public func isStale(at now: Date? = nil) -> Bool {
        guard status != nil, let receivedAt else { return false }
        if !sessionActive || awaitingFirstStatusOfSession { return true }
        return (now ?? clock()).timeIntervalSince(receivedAt) > staleAfter
    }

    /// Stores a new status and returns the low battery threshold to alert for, if any.
    @discardableResult
    public func receive(_ newStatus: DSDeviceStatus) -> Int32? {
        status = newStatus
        receivedAt = clock()
        let firstOfSession = awaitingFirstStatusOfSession
        awaitingFirstStatusOfSession = false
        return evaluateLowBattery(newStatus, firstOfSession: firstOfSession)
    }

    private func evaluateLowBattery(_ status: DSDeviceStatus, firstOfSession: Bool) -> Int32? {
        let percent = status.batteryPercent
        if status.charging {
            firedThresholds = []
            return nil
        }
        guard percent >= 0, percent <= 100 else { return nil }
        for threshold in firedThresholds where percent >= threshold + PhoneStatusLimits.rearmMargin {
            firedThresholds.remove(threshold)
        }
        let crossed = PhoneStatusLimits.lowBatteryThresholds.filter { percent <= $0 && !firedThresholds.contains($0) }
        guard let lowest = crossed.min() else { return nil }
        // A reconnect mid-discharge that already alerted: record what was crossed while away
        // without a catch-up alert.
        let alreadyAlerted = !firedThresholds.isEmpty
        firedThresholds.formUnion(crossed)
        if firstOfSession && alreadyAlerted { return nil }
        return lowest
    }
}

/// Labels and SF Symbol names for a `DeviceStatus`, shared by the popover and the device page.
public enum PhoneStatusDisplay {
    public static func batteryText(_ status: DSDeviceStatus) -> String {
        let percent = status.batteryPercent
        guard percent >= 0, percent <= 100 else { return "—" }
        return "\(percent)%"
    }

    /// `battery.0percent` … `battery.100percent`, or `battery.100percent.bolt` while charging.
    public static func batterySymbol(_ status: DSDeviceStatus) -> String {
        if status.charging { return "battery.100percent.bolt" }
        let percent = status.batteryPercent
        guard percent >= 0 else { return "battery.0percent" }
        let bucket: Int
        switch percent {
        case ..<13: bucket = 0
        case ..<38: bucket = 25
        case ..<63: bucket = 50
        case ..<88: bucket = 75
        default: bucket = 100
        }
        return "battery.\(bucket)percent"
    }

    public static func isLowBattery(_ status: DSDeviceStatus) -> Bool {
        !status.charging && status.batteryPercent >= 0 && status.batteryPercent <= 20
    }

    public static func chargeSourceLabel(_ status: DSDeviceStatus) -> String? {
        switch status.chargeSource {
        case .ac: return "Charger"
        case .usb: return "USB"
        case .wireless: return "Wireless"
        case .dock: return "Dock"
        case .unspecified, .UNRECOGNIZED: return nil
        }
    }

    /// "Charging (Wireless)", "Charging", "On battery", or nil when the battery is unknown.
    public static func chargingText(_ status: DSDeviceStatus) -> String? {
        if status.charging {
            return chargeSourceLabel(status).map { "Charging (\($0))" } ?? "Charging"
        }
        return status.batteryPercent >= 0 ? "On battery" : nil
    }

    /// Signal bars 0–4 for the default network, or nil when unknown or not a radio network.
    public static func bars(_ status: DSDeviceStatus) -> Int? {
        let level: Int32
        switch status.networkType {
        case .wifi: level = status.wifiLevel
        case .cellular: level = status.cellularLevel
        default: return nil
        }
        guard level >= 0 else { return nil }
        return Int(min(level, 4))
    }

    /// Bars as the 0…1 `variableValue` of the `wifi` / `cellularbars` symbols.
    public static func signalFraction(_ status: DSDeviceStatus) -> Double? {
        bars(status).map { Double($0) / 4 }
    }

    /// "Wi-Fi", "5G", "LTE", "Cellular", "Ethernet", "Airplane mode", "No network", …
    public static func networkLabel(_ status: DSDeviceStatus) -> String {
        switch status.networkType {
        case .wifi:
            return "Wi-Fi"
        case .cellular:
            let generation = status.cellularGeneration.trimmingCharacters(in: .whitespaces)
            return generation.isEmpty ? "Cellular" : generation
        case .ethernet:
            return "Ethernet"
        case .other:
            return "Other network"
        case .none:
            return status.airplaneMode ? "Airplane mode" : "No network"
        case .unspecified, .UNRECOGNIZED:
            return status.airplaneMode ? "Airplane mode" : "—"
        }
    }

    public static func networkSymbol(_ status: DSDeviceStatus) -> String {
        switch status.networkType {
        case .wifi: return "wifi"
        case .cellular: return "cellularbars"
        case .ethernet: return "cable.connector"
        case .other: return "network"
        case .none, .unspecified, .UNRECOGNIZED: return status.airplaneMode ? "airplane" : "wifi.slash"
        }
    }

    /// Do Not Disturb wins over the ringer mode, since it is what silences the phone.
    public static func ringerLabel(_ status: DSDeviceStatus) -> String {
        if status.doNotDisturb { return "Do Not Disturb" }
        switch status.ringerMode {
        case .normal: return "Ringer on"
        case .vibrate: return "Vibrate"
        case .silent: return "Silent"
        case .unspecified, .UNRECOGNIZED: return "—"
        }
    }

    public static func ringerSymbol(_ status: DSDeviceStatus) -> String {
        if status.doNotDisturb { return "moon.fill" }
        switch status.ringerMode {
        case .normal: return "bell.fill"
        case .vibrate: return "iphone.radiowaves.left.and.right"
        case .silent: return "bell.slash.fill"
        case .unspecified, .UNRECOGNIZED: return "bell"
        }
    }

    /// "last seen just now", "last seen 7 min ago", "last seen 3 h ago", "last seen 2 days ago".
    public static func lastSeenText(_ receivedAt: Date, now: Date) -> String {
        let seconds = max(Int(now.timeIntervalSince(receivedAt)), 0)
        switch seconds {
        case ..<60: return "last seen just now"
        case ..<3600: return "last seen \(seconds / 60) min ago"
        case ..<86_400: return "last seen \(seconds / 3600) h ago"
        default:
            let days = seconds / 86_400
            return "last seen \(days) day\(days == 1 ? "" : "s") ago"
        }
    }
}
