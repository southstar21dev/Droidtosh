import Foundation
import DroidsyncKit

/// Owns live calls (the call coordinator and the active call), the phone's SIM accounts,
/// dialing and the call dedup settings.
@MainActor
final class CallsController: ObservableObject {
    @Published private(set) var activeCall: CallSnapshot?
    @Published private(set) var phoneAccounts: [DSPhoneAccount] = []
    @Published private(set) var dedupCallsEnabled = true
    @Published private(set) var dedupCallsDisabledPackages: Set<String> = []

    /// Fires whenever the coordinator's active call changes, including to nil.
    var onActiveCallChanged: ((CallSnapshot?) -> Void)?
    /// Fires when a call action request is sent, so its result can be attributed to the app.
    var onActionSent: ((_ requestID: String, _ package: String) -> Void)?
    /// Fires when a call ends or is missed, i.e. when the phone's call log has changed.
    var onCallFinished: (() -> Void)?

    private let presenter: NotificationPresenter
    private let send: (DSEnvelope) -> Void
    private var coordinator: CallCoordinator?

    init(presenter: NotificationPresenter, send: @escaping (DSEnvelope) -> Void) {
        self.presenter = presenter
        self.send = send
    }

    func launch() {
        let defaults = UserDefaults.standard
        dedupCallsEnabled = defaults.object(forKey: "dedup-calls-enabled") as? Bool ?? true
        dedupCallsDisabledPackages = Set(defaults.stringArray(forKey: "dedup-calls-disabled") ?? [])
        let dedupCalls = DedupEngine(
            config: DedupConfig(enabled: dedupCallsEnabled, disabledPackages: dedupCallsDisabledPackages),
            runningApps: { RunningApps.bundleIDs() }
        )
        let coordinator = CallCoordinator(center: CallCenter(), presenter: presenter, dedupCalls: dedupCalls)
        coordinator.attach(send: send)
        coordinator.onActiveCallChanged = { [weak self] snapshot in
            guard let self else { return }
            self.activeCall = snapshot
            self.onActiveCallChanged?(snapshot)
        }
        coordinator.onActionSent = { [weak self] requestID, package in
            self?.onActionSent?(requestID, package)
        }
        self.coordinator = coordinator
    }

    func handle(_ envelope: DSEnvelope) {
        switch envelope.payload {
        case .callState(let state)?:
            DebugLog.log("call: envelope state id=\(state.callID) kind=\(state.kind) pkg=\(state.packageName) phase=\(state.phase)")
            coordinator?.apply(state)
            activeCall = coordinator?.activeCall
            if state.phase == .missed || state.phase == .ended {
                onCallFinished?()
            }
        case .snapshotReply(let snapshot)?:
            phoneAccounts = snapshot.phoneAccounts
            if snapshot.hasCallState { coordinator?.apply(snapshot.callState) }
        default:
            break
        }
    }

    /// Ends any call state left from a previous session (phone authenticated or disconnected).
    func reset() {
        coordinator?.reset()
    }

    func resetForUnpair() {
        coordinator?.reset()
        activeCall = nil
        phoneAccounts = []
    }

    func performCallAction(_ identifier: String) {
        coordinator?.handleResponse(identifier: identifier)
    }

    func dial(number: String, phoneAccountId: String?) {
        var request = DSDialRequest()
        request.number = number
        request.phoneAccountID = phoneAccountId ?? ""
        request.requestID = UUID().uuidString
        send(protocolEnvelope(.dialRequest(request)))
    }

    func redial(_ entry: DSCallLogEntry) {
        dial(number: entry.number, phoneAccountId: entry.phoneAccountID)
    }

    func setDedupCallsEnabled(_ enabled: Bool) {
        dedupCallsEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "dedup-calls-enabled")
        coordinator?.updateDedup(config: DedupConfig(enabled: enabled, disabledPackages: dedupCallsDisabledPackages))
    }

    func setDedupCallsDisabled(package: String, disabled: Bool) {
        if disabled { dedupCallsDisabledPackages.insert(package) } else { dedupCallsDisabledPackages.remove(package) }
        UserDefaults.standard.set(Array(dedupCallsDisabledPackages).sorted(), forKey: "dedup-calls-disabled")
        coordinator?.updateDedup(config: DedupConfig(enabled: dedupCallsEnabled, disabledPackages: dedupCallsDisabledPackages))
    }
}

#if DEBUG
extension CallsController {
    /// Fake call state for `SnapshotRenderer`.
    func applySnapshotFixture(activeCall: CallSnapshot?, accounts: [DSPhoneAccount]) {
        self.activeCall = activeCall
        phoneAccounts = accounts
    }
}
#endif
