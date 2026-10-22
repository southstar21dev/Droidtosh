import XCTest
@testable import DroidsyncProtocol

final class EnvelopeRouteTests: XCTestCase {
    func testPhoneToMacPayloadsReachTheirFeature() {
        XCTAssertEqual(EnvelopeRoute.features(for: .hello(DSHello())), [.connection])
        XCTAssertEqual(EnvelopeRoute.features(for: .notificationPosted(DSNotificationPosted())), [.notifications])
        XCTAssertEqual(EnvelopeRoute.features(for: .relayStatus(DSRelayStatus())), [.notifications])
        XCTAssertEqual(EnvelopeRoute.features(for: .actionResult(DSActionResult())), [.notifications])
        XCTAssertEqual(EnvelopeRoute.features(for: .callState(DSCallState())), [.calls])
        XCTAssertEqual(EnvelopeRoute.features(for: .calllogPage(DSCallLogPage())), [.callLog])
        XCTAssertEqual(EnvelopeRoute.features(for: .clipboardBlobChunk(DSClipboardBlobChunk())), [.clipboard])
        XCTAssertEqual(EnvelopeRoute.features(for: .phoneStatus(DSPhoneStatus())), [.clipboard])
        XCTAssertEqual(EnvelopeRoute.features(for: .fileOffer(DSFileOffer())), [.files])
        XCTAssertEqual(EnvelopeRoute.features(for: .fileChunk(DSFileChunk())), [.files])
        XCTAssertEqual(EnvelopeRoute.features(for: .fileComplete(DSFileComplete())), [.files])
        XCTAssertEqual(EnvelopeRoute.features(for: .deviceStatus(DSDeviceStatus())), [.device])
        XCTAssertEqual(EnvelopeRoute.features(for: .findPhoneState(DSFindPhoneState())), [.device])
    }

    func testFindRequestIsReliableAndNeedsFind() {
        XCTAssertTrue(ProtocolV2.macCapabilities.contains("phone.status"))
        XCTAssertTrue(ProtocolV2.macCapabilities.contains("find"))
        XCTAssertTrue(ProtocolV2.isReliableFromMac(.findPhoneRequest(DSFindPhoneRequest())))
        XCTAssertEqual(ProtocolV2.requiredCapability(for: .findPhoneRequest(DSFindPhoneRequest())), "find")
        // Phone → Mac only; never sent by the Mac.
        XCTAssertFalse(ProtocolV2.isReliableFromMac(.deviceStatus(DSDeviceStatus())))
    }

    func testFileMessagesNeedFilesAndOnlyControlIsReliable() {
        XCTAssertTrue(ProtocolV2.macCapabilities.contains("files"))
        for payload: DSEnvelope.OneOf_Payload in [
            .fileOffer(DSFileOffer()), .fileAccept(DSFileAccept()), .fileReject(DSFileReject()),
            .fileEnd(DSFileEnd()), .fileComplete(DSFileComplete()), .fileCancel(DSFileCancel()),
        ] {
            XCTAssertTrue(ProtocolV2.isReliableFromMac(payload))
            XCTAssertEqual(ProtocolV2.requiredCapability(for: payload), "files")
        }
        XCTAssertFalse(ProtocolV2.isReliableFromMac(.fileChunk(DSFileChunk())))
        XCTAssertFalse(ProtocolV2.isReliableFromMac(.fileProgress(DSFileProgress())))
        XCTAssertEqual(ProtocolV2.requiredCapability(for: .fileChunk(DSFileChunk())), "files")
    }

    func testSnapshotGoesToCallsBeforeNotifications() {
        XCTAssertEqual(EnvelopeRoute.features(for: .snapshotReply(DSSnapshotReply())), [.calls, .notifications])
    }

    func testTransportAndOutboundPayloadsAreIgnored() {
        XCTAssertEqual(EnvelopeRoute.features(for: .ping(DSPing())), [])
        XCTAssertEqual(EnvelopeRoute.features(for: .authOk(DSAuthOk())), [])
        XCTAssertEqual(EnvelopeRoute.features(for: .dialRequest(DSDialRequest())), [])
        XCTAssertEqual(EnvelopeRoute.features(for: .serverInfo(DSServerInfo())), [])
        XCTAssertEqual(EnvelopeRoute.features(for: .resume(DSResume())), [])
        XCTAssertEqual(EnvelopeRoute.features(for: .ackOnly(DSAck())), [])
        XCTAssertEqual(EnvelopeRoute.features(for: .findPhoneRequest(DSFindPhoneRequest())), [])
    }

    func testReliablePayloadsFromMac() {
        XCTAssertTrue(ProtocolV2.isReliableFromMac(.notificationAction(DSNotificationAction())))
        XCTAssertTrue(ProtocolV2.isReliableFromMac(.callAction(DSCallAction())))
        XCTAssertTrue(ProtocolV2.isReliableFromMac(.clipboardText(DSClipboardText())))
        XCTAssertFalse(ProtocolV2.isReliableFromMac(.snapshotRequest(DSSnapshotRequest())))
        XCTAssertFalse(ProtocolV2.isReliableFromMac(.clipboardBlobChunk(DSClipboardBlobChunk())))
        XCTAssertFalse(ProtocolV2.isReliableFromMac(.ping(DSPing())))
    }

    func testRequiredCapabilities() {
        XCTAssertEqual(ProtocolV2.requiredCapability(for: .dialRequest(DSDialRequest())), "calls")
        XCTAssertEqual(ProtocolV2.requiredCapability(for: .clipboardBlobStart(DSClipboardBlobStart())), "clipboard.image")
        XCTAssertEqual(ProtocolV2.requiredCapability(for: .calllogRequest(DSCallLogRequest())), "calllog")
        XCTAssertNil(ProtocolV2.requiredCapability(for: .serverInfo(DSServerInfo())))
    }
}
