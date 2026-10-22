import AppKit
import Foundation
import DroidsyncKit

@MainActor
final class CallCoordinator {
    static let acceptID = "call.accept"
    static let declineID = "call.decline"
    static let muteID = "call.mute"
    static let endID = "call.end"

    private let center: CallCenter
    private let presenter: NotificationPresenter
    private let dedupCalls: DedupEngine
    private var send: ((DSEnvelope) -> Void)?
    private var ringtone: NSSound?
    private var ringTimer: Timer?

    private(set) var activeCall: CallSnapshot?
    var onActiveCallChanged: ((CallSnapshot?) -> Void)?
    var onActionSent: ((_ requestID: String, _ package: String) -> Void)?

    init(center: CallCenter, presenter: NotificationPresenter, dedupCalls: DedupEngine) {
        self.center = center
        self.presenter = presenter
        self.dedupCalls = dedupCalls
        center.onCallChanged = { [weak self] snapshot in
            self?.callChanged(snapshot)
        }
    }

    func attach(send: @escaping (DSEnvelope) -> Void) {
        self.send = send
    }

    func updateDedup(config: DedupConfig) {
        dedupCalls.update(config: config)
    }

    func apply(_ state: DSCallState) {
        DebugLog.log("call: apply id=\(state.callID) kind=\(state.kind) phase=\(state.phase) activeKind=\(String(describing: center.activeCall?.kind))")
        if center.shadowsVoIPCall(state) {
            // The center keeps a number-less outgoing call to show once the VoIP call ends.
            DebugLog.log("call: held back (number-less cellular during VoIP call)")
            center.apply(state)
            return
        }
        if center.mayDropAsVoIPDuplicate(state) {
            if let active = center.activeCall,
               active.kind == .cellular,
               active.phase == .active {
                DebugLog.log("call: dropped (cellular active guard)")
                return
            }
            if dedupCalls.shouldSuppress(androidPackage: state.packageName) {
                DebugLog.log("call: dropped (dedup calls) pkg=\(state.packageName)")
                return
            }
        }
        center.apply(state)
    }

    func reset() {
        center.reset()
    }

    func handleResponse(identifier: String) {
        guard let call = activeCall else { return }
        let action: DSCallActionType
        switch identifier {
        case Self.acceptID: action = .callActionAccept
        case Self.declineID: action = .callActionDecline
        case Self.muteID:
            guard call.kind != .voip else { return }
            suppressedRingCallID = call.id
            stopRingtone()
            action = .callActionMuteRing
        case Self.endID: action = .callActionEnd
        default: return
        }
        var request = DSCallAction()
        request.callID = call.id
        request.action = action
        request.requestID = UUID().uuidString
        DebugLog.log("call: action \(identifier) call=\(call.id) kind=\(call.kind)")
        onActionSent?(request.requestID, call.packageName)
        send?(protocolEnvelope(.callAction(request)))
    }

    private func callChanged(_ snapshot: CallSnapshot?) {
        DebugLog.log("call: changed id=\(snapshot?.id ?? "nil") phase=\(String(describing: snapshot?.phase))")
        activeCall = snapshot
        onActiveCallChanged?(snapshot)
        guard let snapshot else {
            stopRingtone()
            suppressedRingCallID = nil
            return
        }
        if let suppressed = suppressedRingCallID, suppressed != snapshot.id {
            suppressedRingCallID = nil
        }
        if snapshot.phase == .ringing {
            startRingtone(for: snapshot.id)
        } else {
            stopRingtone()
        }
    }

    private var suppressedRingCallID: String?
    private var ringtoneCallID: String?

    private func startRingtone(for callID: String) {
        guard suppressedRingCallID != callID else { return }
        if ringtone == nil {
            let sound = NSSound(named: NSSound.Name("Submarine"))
            if sound == nil { NSSound.beep() }
            sound?.loops = true
            sound?.play()
            ringtone = sound
        }
        guard ringtoneCallID != callID else { return }
        ringtoneCallID = callID
        ringTimer?.invalidate()
        ringTimer = Timer.scheduledTimer(withTimeInterval: 45, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self,
                      self.activeCall?.id == callID,
                      self.activeCall?.phase == .ringing else { return }
                self.suppressedRingCallID = callID
                self.stopRingtone()
            }
        }
    }

    private func stopRingtone() {
        ringtone?.stop()
        ringtone = nil
        ringTimer?.invalidate()
        ringTimer = nil
        ringtoneCallID = nil
    }
}
