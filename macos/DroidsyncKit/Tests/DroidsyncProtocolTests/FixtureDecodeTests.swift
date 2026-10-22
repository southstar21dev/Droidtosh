import XCTest
import SwiftProtobuf
@testable import DroidsyncProtocol

final class FixtureDecodeTests: XCTestCase {
    private func load(_ name: String) throws -> Data {
        let url = try XCTUnwrap(
            Bundle.module.url(forResource: name, withExtension: "bin", subdirectory: "Fixtures"),
            "missing fixture \(name).bin"
        )
        return try Data(contentsOf: url)
    }

    func testHelloFixtureDecodesAndReEncodes() throws {
        let data = try load("hello")
        let envelope = try DSEnvelope(serializedBytes: data)
        XCTAssertEqual(envelope.protocolVersion, ProtocolV2.version)
        XCTAssertEqual(envelope.messageID, "fixture-hello")
        XCTAssertEqual(envelope.sentAtMs, 1_730_000_000_000)
        guard case .hello(let hello)? = envelope.payload else {
            return XCTFail("expected hello payload")
        }
        XCTAssertEqual(hello.deviceName, "Pixel 8")
        XCTAssertEqual(hello.androidVersion, "15")
        XCTAssertEqual(hello.capabilities, ["notifications", "calls"])
        XCTAssertEqual(try envelope.serializedData(), data)
    }

    func testPingFixture() throws {
        let data = try load("ping")
        let envelope = try DSEnvelope(serializedBytes: data)
        guard case .ping(let ping)? = envelope.payload else {
            return XCTFail("expected ping payload")
        }
        XCTAssertEqual(ping.sentAtMs, 1_730_000_000_003)
        XCTAssertEqual(try envelope.serializedData(), data)
    }

    func testNotificationFixture() throws {
        let data = try load("notification_record")
        let envelope = try DSEnvelope(serializedBytes: data)
        guard case .notificationPosted(let posted)? = envelope.payload else {
            return XCTFail("expected notification_posted payload")
        }
        let record = posted.record
        XCTAssertEqual(record.key, "0|com.whatsapp|1|null|10123")
        XCTAssertEqual(record.packageName, "com.whatsapp")
        XCTAssertEqual(record.title, "Alice")
        XCTAssertEqual(record.actions.count, 1)
        XCTAssertEqual(record.actions[0].index, 0)
        XCTAssertTrue(record.actions[0].hasRemoteInput_p)
        XCTAssertEqual(record.remoteInputHint, "Message")
        XCTAssertEqual(try envelope.serializedData(), data)
    }

    func testCallStateFixture() throws {
        let data = try load("call_state")
        let envelope = try DSEnvelope(serializedBytes: data)
        guard case .callState(let state)? = envelope.payload else {
            return XCTFail("expected call_state payload")
        }
        XCTAssertEqual(state.kind, .cellular)
        XCTAssertEqual(state.direction, .incoming)
        XCTAssertEqual(state.phase, .ringing)
        XCTAssertEqual(state.number, "+15551234567")
        XCTAssertEqual(state.simLabel, "Personal")
        XCTAssertEqual(try envelope.serializedData(), data)
    }

    func testResumeFixture() throws {
        let data = try load("resume")
        let envelope = try DSEnvelope(serializedBytes: data)
        XCTAssertEqual(envelope.protocolVersion, ProtocolV2.version)
        XCTAssertEqual(envelope.ack, 7)
        XCTAssertEqual(envelope.seq, 0)
        guard case .resume(let resume)? = envelope.payload else {
            return XCTFail("expected resume payload")
        }
        XCTAssertEqual(resume.streamID, "s-mac-1")
        XCTAssertEqual(resume.lastReceivedSeq, 7)
        XCTAssertEqual(resume.peerStreamID, "s-phone-1")
        XCTAssertEqual(try envelope.serializedData(), data)
    }

    func testDeviceStatusFixture() throws {
        let data = try load("device_status")
        let envelope = try DSEnvelope(serializedBytes: data)
        XCTAssertEqual(envelope.messageID, "fixture-device-status")
        XCTAssertEqual(envelope.sentAtMs, 1_760_000_000_000)
        guard case .deviceStatus(let status)? = envelope.payload else {
            return XCTFail("expected device_status payload")
        }
        XCTAssertEqual(status.batteryPercent, 57)
        XCTAssertTrue(status.charging)
        XCTAssertEqual(status.chargeSource, .wireless)
        XCTAssertFalse(status.powerSave)
        XCTAssertEqual(status.networkType, .cellular)
        XCTAssertEqual(status.wifiLevel, -1)
        XCTAssertEqual(status.cellularLevel, 3)
        XCTAssertEqual(status.cellularGeneration, "5G")
        XCTAssertEqual(status.carrier, "Telekom.de")
        XCTAssertFalse(status.airplaneMode)
        XCTAssertEqual(status.ringerMode, .vibrate)
        XCTAssertTrue(status.doNotDisturb)
        XCTAssertEqual(status.sampledAtMs, 1_759_999_999_500)
        XCTAssertEqual(try envelope.serializedData(), data)
    }

    func testEveryFixtureIsProtocolV2() throws {
        for name in ["hello", "ping", "notification_record", "call_state", "resume", "device_status"] {
            let envelope = try DSEnvelope(serializedBytes: try load(name))
            XCTAssertEqual(envelope.protocolVersion, ProtocolV2.version, name)
        }
    }
}
