import Foundation

/// Protocol v2 constants shared by the transport and the app (see protocol/PROTOCOL-v2.md).
public enum ProtocolV2 {
    public static let version: UInt32 = 2
    public static let minVersion: UInt32 = 2
    public static let maxVersion: UInt32 = 2

    public enum Capability {
        public static let notifications = "notifications"
        public static let calls = "calls"
        public static let callLog = "calllog"
        public static let clipboardText = "clipboard.text"
        public static let clipboardImage = "clipboard.image"
        public static let reliable = "reliable"
        public static let files = "files"
        public static let phoneStatus = "phone.status"
        public static let find = "find"
    }

    /// Everything this Mac build can do; the session uses the intersection with the phone's list.
    public static let macCapabilities: [String] = [
        Capability.notifications,
        Capability.calls,
        Capability.callLog,
        Capability.clipboardText,
        Capability.clipboardImage,
        Capability.reliable,
        Capability.files,
        Capability.phoneStatus,
        Capability.find,
    ]

    /// Mac → phone payloads sent with a sequence number and kept until acknowledged (§4.3).
    /// Phone → Mac reliability is decided by the phone; the receiver handles any `seq > 0`.
    public static func isReliableFromMac(_ payload: DSEnvelope.OneOf_Payload) -> Bool {
        switch payload {
        case .notificationAction, .notificationDismiss, .callAction, .dialRequest,
             .muteListSet, .openAppRequest, .clipboardText:
            return true
        // File control messages are reliable; chunks and progress are re-driven by a re-offer (§7).
        case .fileOffer, .fileAccept, .fileReject, .fileEnd, .fileComplete, .fileCancel:
            return true
        // A ring request must survive a reconnect; the phone drops stale starts (§9).
        case .findPhoneRequest:
            return true
        default:
            return false
        }
    }

    /// The capability a Mac → phone payload needs; nil when it is part of the core protocol.
    public static func requiredCapability(for payload: DSEnvelope.OneOf_Payload) -> String? {
        switch payload {
        case .notificationAction, .notificationDismiss, .snapshotRequest, .appsListRequest,
             .muteListSet, .openAppRequest:
            return Capability.notifications
        case .callAction, .dialRequest:
            return Capability.calls
        case .calllogRequest:
            return Capability.callLog
        case .clipboardText:
            return Capability.clipboardText
        case .clipboardBlobStart, .clipboardBlobChunk, .clipboardBlobEnd:
            return Capability.clipboardImage
        case .fileOffer, .fileAccept, .fileReject, .fileChunk, .fileProgress, .fileEnd, .fileComplete, .fileCancel:
            return Capability.files
        case .findPhoneRequest:
            return Capability.find
        default:
            return nil
        }
    }
}
