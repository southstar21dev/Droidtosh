import AppKit
import SwiftUI
import DroidsyncKit

/// The floating call panel: who's calling, and round Accept, Mute and Decline buttons (or End
/// during a call), like the system's own call notifications.
struct CallPanelView: View {
    @ObservedObject var model: AppModel

    private var call: CallSnapshot? { model.calls.activeCall }
    private var ringing: Bool { call?.phase == .ringing }

    var body: some View {
        HStack(spacing: 14) {
            avatar
            VStack(alignment: .leading, spacing: 3) {
                Text(caption)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
                Text(title)
                    .font(.title3.weight(.semibold))
                    .lineLimit(1)
                    .truncationMode(.tail)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            buttons
        }
        .padding(.leading, 16)
        .padding(.trailing, 14)
        .padding(.vertical, 14)
        .frame(width: 420, height: 92)
        .background(CallPanelBackground())
        .accessibilityElement(children: .contain)
        .accessibilityLabel(accessibilityTitle)
    }

    @ViewBuilder
    private var buttons: some View {
        HStack(spacing: 10) {
            if ringing {
                if call?.kind == .cellular {
                    CallButton(title: "Mute", symbol: "bell.slash.fill", color: Color.gray) {
                        model.calls.performCallAction(CallCoordinator.muteID)
                    }
                    .help("Silence the ringing on your phone")
                }
                CallButton(title: "Decline", symbol: "phone.down.fill", color: .red) {
                    model.calls.performCallAction(CallCoordinator.declineID)
                }
                CallButton(title: "Accept", symbol: "phone.fill", color: .green) {
                    model.calls.performCallAction(CallCoordinator.acceptID)
                }
            } else {
                CallButton(title: "End", symbol: "phone.down.fill", color: .red) {
                    model.calls.performCallAction(CallCoordinator.endID)
                }
            }
        }
    }

    private var accessibilityTitle: String {
        if ringing { return "Incoming call from \(title)" }
        if let call, call.direction == .outgoing, numberless(call) { return "Outgoing call" }
        if call?.phase == .dialing { return "Calling \(title)" }
        return "Call with \(title)"
    }

    private var avatar: some View {
        ContactAvatar(name: call?.contactName ?? "", number: call?.number ?? "", size: 48, onGradient: true)
            .background(Circle().fill(Brand.gradient))
            .overlay(alignment: .bottomTrailing) {
                // The phone doesn't say whether a VoIP call is voice or video, so no video badge.
                Image(systemName: "phone.circle.fill")
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, ringing ? Color.green : Color.accentColor)
                    .font(.system(size: 18))
                    .background(Circle().fill(.background).padding(1))
                    .offset(x: 3, y: 3)
            }
    }

    private var caption: String {
        guard let call else { return "Call" }
        if call.phase == .ringing { return call.kind == .voip ? "Incoming \(appName(call)) call" : "Incoming call" }
        if call.phase == .dialing { return "Calling…" }
        // A number-less outgoing call is already titled "Outgoing call".
        if call.direction == .outgoing, !numberless(call) { return "Outgoing call" }
        return "In call"
    }

    private var title: String {
        guard let call else { return "Call" }
        if !call.contactName.isEmpty { return call.contactName }
        if !call.number.isEmpty { return call.number }
        // A call dialled on the phone without the permission for its number.
        return call.direction == .outgoing ? "Outgoing call" : "Unknown caller"
    }

    private func numberless(_ call: CallSnapshot) -> Bool {
        call.contactName.isEmpty && call.number.isEmpty
    }

    private var subtitle: String {
        guard let call else { return "" }
        var parts: [String] = []
        if !call.contactName.isEmpty, !call.number.isEmpty { parts.append(call.number) }
        if call.kind == .cellular {
            if parts.isEmpty { parts.append("Cellular") }
        } else if !call.packageName.isEmpty, call.phase != .ringing {
            parts.append(appName(call))
        }
        if !call.simLabel.isEmpty { parts.append(call.simLabel) }
        return parts.joined(separator: " · ")
    }

    private func appName(_ call: CallSnapshot) -> String {
        model.notifications.appName(for: call.packageName)
    }
}

/// A round call button with its label underneath.
private struct CallButton: View {
    let title: String
    let symbol: String
    let color: Color
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: symbol)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 42, height: 42)
                    .background(Circle().fill(color.gradient))
                    .brightness(hovering ? 0.06 : 0)
                    .shadow(color: color.opacity(0.35), radius: 4, y: 2)
                Text(title)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .accessibilityLabel(title)
    }
}

/// Liquid Glass on macOS 26; the HUD-like popover material before.
private struct CallPanelBackground: View {
    var body: some View {
        if #available(macOS 26.0, *) {
            Color.clear
                .glassEffect(.regular, in: .rect(cornerRadius: 24))
        } else {
            VisualEffectBackground(material: .popover)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(.separator, lineWidth: 0.5))
        }
    }
}

/// An `NSVisualEffectView` behind SwiftUI content, blending with what's behind the window.
struct VisualEffectBackground: NSViewRepresentable {
    var material: NSVisualEffectView.Material

    func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = material
        view.blendingMode = .behindWindow
        view.state = .active
        return view
    }

    func updateNSView(_ view: NSVisualEffectView, context: Context) {
        view.material = material
    }
}
