import XCTest
@testable import DroidsyncProtocol
@testable import DroidsyncCalls

final class CallCenterTests: XCTestCase {
    private func state(
        id: String = "cellular:1",
        kind: DSCallKind = .cellular,
        phase: DSCallPhase,
        name: String = "Bob",
        number: String = ""
    ) -> DSCallState {
        var state = DSCallState()
        state.callID = id
        state.kind = kind
        state.direction = .incoming
        state.phase = phase
        state.contactName = name
        state.number = number
        return state
    }

    func testRingingThenActiveThenEnded() {
        let center = CallCenter()
        var changes: [CallSnapshot?] = []
        center.onCallChanged = { changes.append($0) }

        center.apply(state(phase: .ringing))
        XCTAssertEqual(center.activeCall?.phase, .ringing)
        center.apply(state(phase: .active))
        XCTAssertEqual(center.activeCall?.phase, .active)
        center.apply(state(phase: .ended))
        XCTAssertNil(center.activeCall)
        XCTAssertEqual(changes.count, 3)
        XCTAssertEqual(changes[1]?.phase, .active)
        XCTAssertNil(changes[2])
    }

    func testEndedForDifferentCallIsIgnored() {
        let center = CallCenter()
        center.apply(state(id: "cellular:1", phase: .ringing))
        center.apply(state(id: "cellular:2", phase: .ended))
        XCTAssertEqual(center.activeCall?.id, "cellular:1")
    }

    func testMissedClears() {
        let center = CallCenter()
        center.apply(state(phase: .ringing))
        center.apply(state(phase: .missed))
        XCTAssertNil(center.activeCall)
    }

    func testResetClears() {
        let center = CallCenter()
        center.apply(state(phase: .ringing))
        center.reset()
        XCTAssertNil(center.activeCall)
    }

    func testKnownVoipPackagesIncludeWhatsAppAndTelegram() {
        XCTAssertTrue(VoipApps.knownPackages.contains("com.whatsapp"))
        XCTAssertTrue(VoipApps.knownPackages.contains("org.telegram.messenger"))
    }

    func testVoIPSuppressionOnlyGatesNewCalls() {
        let center = CallCenter()
        let ringing = state(id: "voip:1", kind: .voip, phase: .ringing)
        XCTAssertTrue(center.mayDropAsVoIPDuplicate(ringing))
        XCTAssertFalse(center.mayDropAsVoIPDuplicate(state(phase: .ringing)))

        center.apply(ringing)
        XCTAssertFalse(center.mayDropAsVoIPDuplicate(state(id: "voip:1", kind: .voip, phase: .active)))
        XCTAssertFalse(center.mayDropAsVoIPDuplicate(state(id: "voip:1", kind: .voip, phase: .ended)))
        XCTAssertFalse(center.mayDropAsVoIPDuplicate(state(id: "voip:1", kind: .voip, phase: .missed)))
        XCTAssertTrue(center.mayDropAsVoIPDuplicate(state(id: "voip:2", kind: .voip, phase: .ringing)))
    }
    func testNumberlessCellularCallDoesNotReplaceAnsweredVoIPCall() {
        let center = CallCenter()
        var voip = state(id: "voip:0|com.whatsapp.w4b|1", kind: .voip, phase: .ringing, name: "+36 70 563 9709")
        voip.packageName = "com.whatsapp.w4b"
        center.apply(voip)
        voip.phase = .active
        center.apply(voip)

        // The phone's telephony state goes OFFHOOK when the WhatsApp call is answered.
        let phantom = state(id: "cellular:x", kind: .cellular, phase: .active, name: "")
        XCTAssertTrue(center.shadowsVoIPCall(phantom))
        center.apply(phantom)
        XCTAssertEqual(center.activeCall?.id, voip.callID)
        XCTAssertEqual(center.activeCall?.phase, .active)
        XCTAssertEqual(center.activeCall?.packageName, "com.whatsapp.w4b")
        XCTAssertEqual(center.activeCall?.contactName, "+36 70 563 9709")
    }

    func testNumberlessCellularCallDoesNotReplaceRingingVoIPCall() {
        let center = CallCenter()
        center.apply(state(id: "voip:1", kind: .voip, phase: .ringing))
        center.apply(state(id: "cellular:x", kind: .cellular, phase: .ringing, name: ""))
        XCTAssertEqual(center.activeCall?.id, "voip:1")
    }

    func testCellularCallWithNumberStillReplacesVoIPCall() {
        let center = CallCenter()
        center.apply(state(id: "voip:1", kind: .voip, phase: .active))
        let real = state(id: "cellular:1", kind: .cellular, phase: .ringing, name: "", number: "+44 20 7946 0958")
        XCTAssertFalse(center.shadowsVoIPCall(real))
        center.apply(real)
        XCTAssertEqual(center.activeCall?.id, "cellular:1")
    }

    private func outgoing(
        id: String = "cellular:out",
        phase: DSCallPhase,
        name: String = "",
        number: String = ""
    ) -> DSCallState {
        var dialled = state(id: id, kind: .cellular, phase: phase, name: name, number: number)
        dialled.direction = .outgoing
        dialled.simLabel = "SIM 1"
        return dialled
    }

    func testNumberlessOutgoingCallThenItsNumberUpdatesTheSameCall() {
        let center = CallCenter()
        var changes: [CallSnapshot?] = []
        center.onCallChanged = { changes.append($0) }

        center.apply(outgoing(phase: .dialing))
        XCTAssertEqual(center.activeCall?.id, "cellular:out")
        XCTAssertEqual(center.activeCall?.direction, .outgoing)
        XCTAssertEqual(center.activeCall?.number, "")

        center.apply(outgoing(phase: .dialing, name: "Alice", number: "+441234567890"))
        center.apply(outgoing(phase: .dialing, name: "Alice", number: "+441234567890"))
        center.apply(outgoing(phase: .active, name: "Alice", number: "+441234567890"))
        XCTAssertEqual(center.activeCall?.contactName, "Alice")
        XCTAssertEqual(center.activeCall?.phase, .active)
        center.apply(outgoing(phase: .ended, name: "Alice", number: "+441234567890"))
        XCTAssertNil(center.activeCall)

        // Never cleared in between (no flicker), and the repeated state changed nothing.
        XCTAssertEqual(changes.map { $0?.phase }, [.dialing, .dialing, .active, nil])
        XCTAssertTrue(changes.dropLast().allSatisfy { $0?.id == "cellular:out" })
    }

    func testNumberlessOutgoingCallDuringVoIPCallShowsOnceTheVoIPCallEnds() {
        let center = CallCenter()
        center.apply(state(id: "voip:1", kind: .voip, phase: .active))
        let dialled = outgoing(phase: .dialing)
        XCTAssertTrue(center.shadowsVoIPCall(dialled))
        center.apply(dialled)
        XCTAssertEqual(center.activeCall?.id, "voip:1")

        center.apply(state(id: "voip:1", kind: .voip, phase: .ended))
        XCTAssertEqual(center.activeCall?.id, "cellular:out")
        XCTAssertEqual(center.activeCall?.phase, .dialing)
        center.apply(outgoing(phase: .ended))
        XCTAssertNil(center.activeCall)
    }

    func testOutgoingCallNumberArrivingDuringVoIPCallReplacesIt() {
        let center = CallCenter()
        center.apply(state(id: "voip:1", kind: .voip, phase: .active))
        center.apply(outgoing(phase: .dialing))
        center.apply(outgoing(phase: .dialing, number: "+441234567890"))
        XCTAssertEqual(center.activeCall?.id, "cellular:out")
        XCTAssertEqual(center.activeCall?.number, "+441234567890")
        XCTAssertNil(center.heldBackCall)
    }

    func testHeldBackOutgoingCallThatEndsFirstIsNotShownLater() {
        let center = CallCenter()
        center.apply(state(id: "voip:1", kind: .voip, phase: .active))
        center.apply(outgoing(phase: .dialing))
        center.apply(outgoing(phase: .ended))
        XCTAssertEqual(center.activeCall?.id, "voip:1")
        center.apply(state(id: "voip:1", kind: .voip, phase: .ended))
        XCTAssertNil(center.activeCall)
    }

    func testIncomingNumberlessPhantomIsNotShownAfterTheVoIPCallEnds() {
        // Older phone builds report an answered WhatsApp call as a number-less incoming call.
        let center = CallCenter()
        center.apply(state(id: "voip:1", kind: .voip, phase: .active))
        center.apply(state(id: "cellular:x", kind: .cellular, phase: .active, name: ""))
        center.apply(state(id: "voip:1", kind: .voip, phase: .ended))
        XCTAssertNil(center.activeCall)
        XCTAssertNil(center.heldBackCall)
    }

    func testNumberlessOutgoingCallShowsWithoutAVoIPCall() {
        let center = CallCenter()
        let dialled = outgoing(phase: .dialing)
        XCTAssertFalse(center.shadowsVoIPCall(dialled))
        center.apply(dialled)
        XCTAssertEqual(center.activeCall?.id, "cellular:out")
        XCTAssertEqual(center.activeCall?.simLabel, "SIM 1")
    }

    func testNumberlessCellularCallShowsWithoutAVoIPCall() {
        let center = CallCenter()
        center.apply(state(id: "cellular:1", kind: .cellular, phase: .ringing, name: ""))
        XCTAssertEqual(center.activeCall?.id, "cellular:1")
    }
}
