import Foundation
import DroidsyncProtocol

public struct CallSnapshot: Equatable {
    public let id: String
    public let kind: DSCallKind
    public let packageName: String
    public let direction: DSCallDirection
    public let phase: DSCallPhase
    public let number: String
    public let contactName: String
    public let simLabel: String
    public let startedAtMs: UInt64

    public var isVoIP: Bool { kind == .voip }
}

public enum VoipApps {
    public static let knownPackages: Set<String> = [
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",
        "com.facebook.orca",
        "com.google.android.apps.meetings",
        "us.zoom.xos",
    ]
}

public final class CallCenter {
    public private(set) var activeCall: CallSnapshot?
    public var onCallChanged: ((CallSnapshot?) -> Void)?

    /// A number-less outgoing cellular call that `shadowsVoIPCall` kept from replacing a VoIP
    /// call. The phone only sends one after checking it's a real SIM call (one dialled on the
    /// phone while a VoIP call is up), so it is shown once the VoIP call ends.
    public private(set) var heldBackCall: DSCallState?

    public init() {}

    public func apply(_ state: DSCallState) {
        if shadowsVoIPCall(state) {
            if state.direction == .outgoing { heldBackCall = state }
            return
        }
        // A later state for the held-back call (its number, or its end) supersedes it.
        if heldBackCall?.callID == state.callID { heldBackCall = nil }
        switch state.phase {
        case .ringing, .dialing, .active:
            show(state)
        case .ended, .missed:
            guard activeCall?.id == state.callID else { break }
            if let held = heldBackCall {
                heldBackCall = nil
                show(held)
            } else {
                activeCall = nil
                onCallChanged?(nil)
            }
        default:
            break
        }
    }

    private func show(_ state: DSCallState) {
        let snapshot = CallSnapshot(
            id: state.callID,
            kind: state.kind,
            packageName: state.packageName,
            direction: state.direction,
            phase: state.phase,
            number: state.number,
            contactName: state.contactName,
            simLabel: state.simLabel,
            startedAtMs: state.startedAtMs
        )
        // A repeated state (the phone resends it, e.g. with READ_CALL_LOG) changes nothing.
        guard snapshot != activeCall else { return }
        activeCall = snapshot
        onCallChanged?(snapshot)
    }

    /// Whether the VoIP suppression rules (cellular call in progress, call dedup) may
    /// drop `state`. They only gate new calls: updates and endings for a call already
    /// shown must always arrive, or its panel stays up.
    public func mayDropAsVoIPDuplicate(_ state: DSCallState) -> Bool {
        guard state.kind == .voip else { return false }
        switch state.phase {
        case .ended, .missed:
            return false
        default:
            return activeCall?.id != state.callID
        }
    }

    /// Whether `state` is a cellular call with no number or name arriving while a VoIP call
    /// rings or is in progress. Answering a self-managed VoIP call (WhatsApp) puts the phone's
    /// telephony state OFFHOOK, which older phone builds reported as a number-less cellular call;
    /// it must not replace the VoIP call with "Unknown caller · Cellular". Without a VoIP call
    /// (or once one has a number) a number-less cellular call, such as one dialled on the phone
    /// without the number permission, shows as usual.
    public func shadowsVoIPCall(_ state: DSCallState) -> Bool {
        guard state.kind == .cellular,
              state.number.isEmpty, state.contactName.isEmpty,
              let active = activeCall, active.isVoIP, active.id != state.callID else { return false }
        switch state.phase {
        case .ringing, .dialing, .active:
            return active.phase == .ringing || active.phase == .active
        default:
            return false
        }
    }

    public func reset() {
        heldBackCall = nil
        guard activeCall != nil else { return }
        activeCall = nil
        onCallChanged?(nil)
    }
}
