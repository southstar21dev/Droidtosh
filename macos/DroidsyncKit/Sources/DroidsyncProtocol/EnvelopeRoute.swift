import Foundation

/// The Mac-side features that consume envelopes from the phone.
public enum PhoneFeature: CaseIterable, Sendable {
    case connection
    case notifications
    case calls
    case callLog
    case clipboard
    case files
    /// Phone status and Find my phone (§8, §9).
    case device
}

public enum EnvelopeRoute {
    /// Features that handle an inbound payload, in delivery order. The switch is exhaustive on
    /// purpose: a new payload case doesn't compile until it is routed or explicitly ignored.
    public static func features(for payload: DSEnvelope.OneOf_Payload) -> [PhoneFeature] {
        switch payload {
        case .hello:
            return [.connection]
        case .relayStatus, .notificationPosted, .notificationUpdated, .notificationRemoved,
             .appsList, .appIcon, .actionResult:
            return [.notifications]
        case .snapshotReply:
            // Calls first: the snapshot's call state and phone accounts land before notifications reconcile.
            return [.calls, .notifications]
        case .callState:
            return [.calls]
        case .calllogPage:
            return [.callLog]
        case .clipboardText, .clipboardBlobStart, .clipboardBlobChunk, .clipboardBlobEnd, .phoneStatus:
            return [.clipboard]
        // File transfer is symmetric: every file message can arrive from the phone.
        case .fileOffer, .fileAccept, .fileReject, .fileChunk, .fileProgress, .fileEnd, .fileComplete, .fileCancel:
            return [.files]
        case .deviceStatus, .findPhoneState:
            return [.device]
        // Handshake and keepalive are consumed by the transport; the rest only flow Mac → phone.
        case .authChallenge, .authResponse, .authOk, .ping, .pong, .error, .resume, .ackOnly,
             .notificationAction, .notificationDismiss, .snapshotRequest, .appsListRequest,
             .muteListSet, .openAppRequest, .serverInfo, .hostStatus,
             .callAction, .dialRequest, .calllogRequest, .findPhoneRequest:
            return []
        }
    }
}
