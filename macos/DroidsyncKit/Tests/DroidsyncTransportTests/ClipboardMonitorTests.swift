import Foundation
import XCTest
@testable import DroidsyncTransport

final class FakePasteboard: ClipboardPasteboard {
    var changeCount = 0
    var holdsPrivateContent = false
    var text: String?
    var image: Data?
    var lastWrittenText: String?
    var lastWrittenImage: Data?

    func readText() -> String? { text }
    func readImagePNG() -> Data? { image }
    func writeText(_ text: String) { lastWrittenText = text; changeCount += 1 }
    func writeImage(png: Data) { lastWrittenImage = png; changeCount += 1 }
}

final class ClipboardMonitorTests: XCTestCase {
    private func makeMonitor() -> (ClipboardMonitor, FakePasteboard, () -> [ClipboardMonitor.Event]) {
        let pasteboard = FakePasteboard()
        let monitor = ClipboardMonitor(pasteboard: pasteboard)
        var events: [ClipboardMonitor.Event] = []
        monitor.onEvent = { events.append($0) }
        monitor.isEnabled = { true }
        monitor.isConnected = { true }
        monitor.isPhoneMetered = { false }
        return (monitor, pasteboard, { events })
    }

    func testEmitsTextOncePerCopy() {
        let (monitor, pasteboard, events) = makeMonitor()
        pasteboard.text = "hello"
        pasteboard.changeCount += 1
        monitor.poll()
        monitor.poll()
        XCTAssertEqual(events(), [.text("hello")])
    }

    func testSkipsDuplicateContent() {
        let (monitor, pasteboard, events) = makeMonitor()
        pasteboard.text = "same"
        pasteboard.changeCount += 1
        monitor.poll()
        pasteboard.changeCount += 1
        monitor.poll()
        XCTAssertEqual(events(), [.text("same")])
    }

    func testDoesNotEchoAppliedContent() {
        let (monitor, pasteboard, events) = makeMonitor()
        pasteboard.writeText("from phone")
        monitor.noteApplied(Data("from phone".utf8))
        monitor.poll()
        XCTAssertTrue(events().isEmpty)
    }

    func testRecopyAfterRemoteChangeIsSentAgain() {
        let (monitor, pasteboard, events) = makeMonitor()
        pasteboard.text = "foo"
        pasteboard.changeCount += 1
        monitor.poll()

        pasteboard.writeText("bar")
        monitor.noteApplied(Data("bar".utf8))
        monitor.poll()

        pasteboard.text = "foo"
        pasteboard.changeCount += 1
        monitor.poll()
        XCTAssertEqual(events(), [.text("foo"), .text("foo")])
    }

    func testRecopyOfAppliedContentAfterLocalChangeIsSent() {
        let (monitor, pasteboard, events) = makeMonitor()
        pasteboard.writeText("from phone")
        monitor.noteApplied(Data("from phone".utf8))

        pasteboard.text = "local"
        pasteboard.changeCount += 1
        monitor.poll()

        pasteboard.text = "from phone"
        pasteboard.changeCount += 1
        monitor.poll()
        XCTAssertEqual(events(), [.text("local"), .text("from phone")])
    }

    func testPrivateContentIsNotSent() {
        let (monitor, pasteboard, events) = makeMonitor()
        pasteboard.holdsPrivateContent = true
        pasteboard.text = "hunter2"
        pasteboard.changeCount += 1
        monitor.poll()
        XCTAssertTrue(events().isEmpty)

        pasteboard.holdsPrivateContent = false
        pasteboard.text = "public"
        pasteboard.changeCount += 1
        monitor.poll()
        XCTAssertEqual(events(), [.text("public")])
    }

    func testDisabledOrDisconnectedDoesNotEmit() {
        let (monitor, pasteboard, events) = makeMonitor()
        monitor.isEnabled = { false }
        pasteboard.text = "x"
        pasteboard.changeCount += 1
        monitor.poll()
        XCTAssertTrue(events().isEmpty)

        monitor.isEnabled = { true }
        monitor.isConnected = { false }
        pasteboard.changeCount += 1
        monitor.poll()
        XCTAssertTrue(events().isEmpty)
    }

    func testImageDeferredWhenMetered() {
        let (monitor, pasteboard, events) = makeMonitor()
        monitor.isPhoneMetered = { true }
        pasteboard.image = Data(repeating: 9, count: 100)
        pasteboard.changeCount += 1
        monitor.poll()
        XCTAssertEqual(events(), [.deferredImage(Data(repeating: 9, count: 100))])
    }

    func testImageAutoSentWhenUnmetered() {
        let (monitor, pasteboard, events) = makeMonitor()
        pasteboard.image = Data(repeating: 5, count: 100)
        pasteboard.changeCount += 1
        monitor.poll()
        XCTAssertEqual(events(), [.image(Data(repeating: 5, count: 100))])
    }
}
