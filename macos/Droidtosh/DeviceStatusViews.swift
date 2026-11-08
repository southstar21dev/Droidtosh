import SwiftUI
import DroidsyncKit

/// Battery, network and sound at a glance. Greyed with "last seen …" when the status is stale.
struct PhoneStatusChips: View {
    @ObservedObject var device: DeviceController
    /// Text in `Brand.onHero`, for the hero surface.
    var onHero = false

    var body: some View {
        if let status = device.status {
            HStack(spacing: 14) {
                chip(
                    Text(PhoneStatusDisplay.batteryText(status)).monospacedDigit(),
                    icon: Image(systemName: PhoneStatusDisplay.batterySymbol(status)),
                    tint: batteryTint(status)
                )
                .help(PhoneStatusDisplay.chargingText(status) ?? "Battery unknown")
                .accessibilityLabel("Battery \(PhoneStatusDisplay.batteryText(status)), \(PhoneStatusDisplay.chargingText(status) ?? "unknown")")
                chip(Text(PhoneStatusDisplay.networkLabel(status)), icon: networkImage(status), tint: nil)
                    .help(networkHelp(status))
                    .accessibilityLabel("Network: \(networkHelp(status))")
                chip(Text(shortRingerLabel(status)), icon: Image(systemName: PhoneStatusDisplay.ringerSymbol(status)), tint: nil)
                    .help(PhoneStatusDisplay.ringerLabel(status))
                    .accessibilityLabel("Sound: \(PhoneStatusDisplay.ringerLabel(status))")
                Spacer(minLength: 0)
                if device.isStale {
                    LastSeenText(device: device)
                        .font(.caption)
                }
            }
            .font(.callout)
            .foregroundStyle(foreground)
            .opacity(device.isStale ? 0.65 : 1)
        } else {
            Text(device.phoneSupportsStatus ? "Waiting for phone status…" : "Phone status not available")
                .font(.callout)
                .foregroundStyle(onHero ? AnyShapeStyle(Brand.onHero.opacity(0.8)) : AnyShapeStyle(.secondary))
        }
    }

    private var foreground: AnyShapeStyle {
        if onHero { return AnyShapeStyle(Brand.onHero) }
        return device.isStale ? AnyShapeStyle(.secondary) : AnyShapeStyle(.primary)
    }

    private func chip(_ title: some View, icon: Image, tint: Color?) -> some View {
        HStack(spacing: 4) {
            icon
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(tint.map { AnyShapeStyle($0) } ?? foreground)
            title
        }
        .accessibilityElement(children: .ignore)
    }

    private func batteryTint(_ status: DSDeviceStatus) -> Color? {
        if device.isStale || onHero { return nil }
        if status.charging { return .green }
        return PhoneStatusDisplay.isLowBattery(status) ? .red : nil
    }

    private func shortRingerLabel(_ status: DSDeviceStatus) -> String {
        if status.doNotDisturb { return "DND" }
        let label = PhoneStatusDisplay.ringerLabel(status)
        return label == "Ringer on" ? "Ring" : label
    }

    private func networkHelp(_ status: DSDeviceStatus) -> String {
        var parts = [PhoneStatusDisplay.networkLabel(status)]
        if let bars = PhoneStatusDisplay.bars(status) { parts.append("\(bars) of 4 bars") }
        if status.networkType == .cellular, !status.carrier.isEmpty { parts.append(status.carrier) }
        return parts.joined(separator: " · ")
    }
}

/// The network symbol, filled to the signal level when there is one.
func networkImage(_ status: DSDeviceStatus) -> Image {
    let symbol = PhoneStatusDisplay.networkSymbol(status)
    if let fraction = PhoneStatusDisplay.signalFraction(status) {
        return Image(systemName: symbol, variableValue: fraction)
    }
    return Image(systemName: symbol)
}

/// "last seen 7 min ago", refreshed while visible.
struct LastSeenText: View {
    @ObservedObject var device: DeviceController

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            if let receivedAt = device.receivedAt {
                Text(PhoneStatusDisplay.lastSeenText(receivedAt, now: context.date))
            }
        }
    }
}

/// Find My Phone's state line under the button: ringing, no response, or queued for reconnect.
enum FindPhoneText {
    @MainActor
    static func status(_ device: DeviceController) -> String? {
        if let text = device.findStatusText { return text }
        return device.phoneSupportsFind ? nil : "Will ring when the phone reconnects"
    }

    @MainActor
    static func tint(_ device: DeviceController) -> Color {
        switch device.findPhase {
        case .noResponse, .ended(.failed), .ended(.expired): return .orange
        default: return .secondary
        }
    }

    @MainActor
    static func symbol(_ device: DeviceController) -> String {
        device.findCanStop ? "speaker.slash.fill" : "speaker.wave.3.fill"
    }
}

/// The overview's Find My Phone card.
struct FindPhoneCard: View {
    @ObservedObject var device: DeviceController

    var body: some View {
        Card {
            CardHeader("Find My Phone", symbol: "location.magnifyingglass")
            Text("Rings your phone so you can find it nearby.")
                .font(.callout)
                .foregroundStyle(.secondary)
            HStack(spacing: 10) {
                Button {
                    device.toggleFind()
                } label: {
                    Label(device.findButtonTitle, systemImage: FindPhoneText.symbol(device))
                        .frame(minWidth: 130)
                }
                .controlSize(.large)
                .droidtoshProminentButton()
                .tint(device.findCanStop ? .red : nil)
                .disabled(!device.showsFind)
                if let text = FindPhoneText.status(device), device.showsFind {
                    Text(text)
                        .font(.callout)
                        .foregroundStyle(FindPhoneText.tint(device))
                        .lineLimit(2)
                }
            }
            if !device.showsFind {
                Text("Available when your phone's Droidtosh app supports it.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// The overview's phone card: everything the phone reports.
struct PhoneDetailsCard: View {
    @ObservedObject var device: DeviceController

    var body: some View {
        Card {
            CardHeader("Phone", symbol: "iphone.gen3") {
                if device.isStale {
                    LastSeenText(device: device)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            if let status = device.status {
                details(status)
                    .foregroundStyle(device.isStale ? .secondary : .primary)
                    .opacity(device.isStale ? 0.65 : 1)
            } else {
                Text(device.phoneSupportsStatus ? "Waiting for phone status…" : "Phone status appears here once a phone that reports it connects.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func details(_ status: DSDeviceStatus) -> some View {
        Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 7) {
            row("Battery", value: batteryValue(status), symbol: PhoneStatusDisplay.batterySymbol(status))
            row("Power saving", value: status.powerSave ? "On" : "Off", symbol: "leaf")
            GridRow {
                networkImage(status)
                    .foregroundStyle(.secondary)
                    .frame(width: 18)
                Text("Network").foregroundStyle(.secondary)
                Text(networkText(status))
            }
            if status.networkType == .cellular || !status.carrier.isEmpty {
                row("Carrier", value: status.carrier.isEmpty ? "—" : status.carrier, symbol: "antenna.radiowaves.left.and.right")
            }
            row("Airplane mode", value: status.airplaneMode ? "On" : "Off", symbol: "airplane")
            row("Sound", value: soundText(status), symbol: PhoneStatusDisplay.ringerSymbol(status))
        }
        .font(.callout)
    }

    private func row(_ label: String, value: String, symbol: String) -> some View {
        GridRow {
            Image(systemName: symbol)
                .foregroundStyle(.secondary)
                .frame(width: 18)
            Text(label).foregroundStyle(.secondary)
            Text(value)
                .textSelection(.enabled)
        }
        .accessibilityElement(children: .combine)
    }

    private func batteryValue(_ status: DSDeviceStatus) -> String {
        let percent = PhoneStatusDisplay.batteryText(status)
        guard let charging = PhoneStatusDisplay.chargingText(status) else { return percent }
        return "\(percent) · \(charging)"
    }

    private func networkText(_ status: DSDeviceStatus) -> String {
        let label = PhoneStatusDisplay.networkLabel(status)
        guard let bars = PhoneStatusDisplay.bars(status) else { return label }
        return "\(label) · \(bars) of 4 bars"
    }

    private func soundText(_ status: DSDeviceStatus) -> String {
        guard status.doNotDisturb else { return PhoneStatusDisplay.ringerLabel(status) }
        var ringer = status
        ringer.doNotDisturb = false
        let mode = PhoneStatusDisplay.ringerLabel(ringer)
        return mode == "—" ? "Do Not Disturb" : "Do Not Disturb · \(mode)"
    }
}
