import XCTest
@testable import DroidsyncProtocol
@testable import DroidsyncNotifications

final class NotificationBridgeTests: XCTestCase {
    private final class RecordingPresenter: NotificationPresenting {
        var posted: [PendingNotification] = []
        var removed: [[String]] = []
        var removeAllCalled = false
        func post(_ notification: PendingNotification) { posted.append(notification) }
        func remove(identifiers: [String]) { removed.append(identifiers) }
        func removeAll() {
            removeAllCalled = true
            posted.removeAll()
        }
    }

    private func record(
        key: String = "k1",
        actions: Int = 2,
        ongoing: Bool = false,
        replyIndexes: Set<Int>? = nil
    ) -> DSNotificationRecord {
        var record = DSNotificationRecord()
        record.key = key
        record.packageName = "com.whatsapp"
        record.appLabel = "WhatsApp"
        record.title = "Alice"
        record.text = "See you at 7"
        record.bigText = "See you at 7, bring snacks"
        record.subText = "Chat"
        record.remoteInputHint = "Message"
        record.isOngoing = ongoing
        record.actions = (0..<actions).map { index in
            var action = DSActionInfo()
            action.index = UInt32(index)
            action.title = index == 0 ? "Reply" : "Action \(index)"
            action.hasRemoteInput_p = replyIndexes?.contains(index) ?? (index == 0)
            return action
        }
        return record
    }

    func testMappingProducesCategoryReplyAndBody() {
        let pending = NotificationBridge.pending(from: record())
        XCTAssertEqual(pending.identifier, "k1")
        XCTAssertEqual(pending.title, "Alice")
        XCTAssertEqual(pending.body, "See you at 7, bring snacks")
        XCTAssertTrue(pending.categoryIdentifier.hasPrefix("app.com.whatsapp.r0,a1."))
        XCTAssertEqual(pending.replyPlaceholder, "Message")
        XCTAssertEqual(pending.actions.count, 2)
        XCTAssertEqual(pending.actions[0].identifier, "reply.0")
        XCTAssertTrue(pending.actions[0].isReply)
        XCTAssertEqual(pending.actions[1].identifier, "action.1")
        XCTAssertFalse(pending.actions[1].isReply)
    }

    func testCapsCustomActionsAtFour() {
        let pending = NotificationBridge.pending(from: record(actions: 6))
        XCTAssertEqual(pending.actions.count, NotificationBridge.maxCustomActions)
    }

    func testReplyActionOutsidePrefixIsKept() {
        let pending = NotificationBridge.pending(from: record(actions: 6, replyIndexes: [5]))
        XCTAssertEqual(pending.actions.count, NotificationBridge.maxCustomActions)
        XCTAssertEqual(pending.actions.map(\.identifier), ["reply.5", "action.0", "action.1", "action.2"])
        XCTAssertTrue(pending.actions[0].isReply)
    }

    func testCategoryIDVariesWithActionSet() {
        let replyFirst = NotificationBridge.pending(from: record())
        let noReply = NotificationBridge.pending(from: record(replyIndexes: []))
        let noActions = NotificationBridge.pending(from: record(actions: 0))
        XCTAssertTrue(replyFirst.categoryIdentifier.hasPrefix("app.com.whatsapp.r0,a1."))
        XCTAssertTrue(noReply.categoryIdentifier.hasPrefix("app.com.whatsapp.a0,a1."))
        XCTAssertEqual(noActions.categoryIdentifier, "app.com.whatsapp.none")
        XCTAssertNotEqual(replyFirst.categoryIdentifier, noReply.categoryIdentifier)
        XCTAssertNotEqual(replyFirst.categoryIdentifier, noActions.categoryIdentifier)
    }

    func testCategoryIDVariesWithActionTitlesAndPlaceholder() {
        var missedCall = record(replyIndexes: [])
        missedCall.actions[0].title = "Call back"
        missedCall.actions[1].title = "Message"
        var chat = record(replyIndexes: [])
        chat.actions[0].title = "Mark as read"
        chat.actions[1].title = "Mute"
        var otherHint = record()
        otherHint.remoteInputHint = "Reply to Alice"

        let base = NotificationBridge.pending(from: record())
        XCTAssertNotEqual(
            NotificationBridge.pending(from: missedCall).categoryIdentifier,
            NotificationBridge.pending(from: chat).categoryIdentifier
        )
        XCTAssertNotEqual(base.categoryIdentifier, NotificationBridge.pending(from: otherHint).categoryIdentifier)
        XCTAssertEqual(base.categoryIdentifier, NotificationBridge.pending(from: record(key: "k2")).categoryIdentifier)
    }

    func testPendingCarriesAndroidPackage() {
        XCTAssertEqual(NotificationBridge.pending(from: record()).packageName, "com.whatsapp")
    }

    func testPostedRemovedAndReconcile() {
        let presenter = RecordingPresenter()
        let bridge = NotificationBridge(presenter: presenter)

        bridge.handlePosted(record(key: "k1"))
        bridge.handlePosted(record(key: "k2"))
        XCTAssertEqual(presenter.posted.map(\.identifier), ["k1", "k2"])

        bridge.reconcile(with: [record(key: "k2"), record(key: "k3")])
        XCTAssertEqual(presenter.removed.last, ["k1"])
        XCTAssertEqual(presenter.posted.suffix(2).map(\.identifier), ["k2", "k3"])

        bridge.handleRemoved(key: "k3")
        XCTAssertEqual(presenter.removed.last, ["k3"])
    }

    func testUpdatesAndReconcilesAreSilent() {
        let presenter = RecordingPresenter()
        let bridge = NotificationBridge(presenter: presenter)

        bridge.handlePosted(record(key: "k1"))
        XCTAssertEqual(presenter.posted.last?.silent, false)

        bridge.handlePosted(record(key: "k1"))
        XCTAssertEqual(presenter.posted.last?.silent, true)

        bridge.reconcile(with: [record(key: "k1"), record(key: "k2")])
        let lastTwo = presenter.posted.suffix(2)
        XCTAssertEqual(lastTwo.first?.identifier, "k1")
        XCTAssertEqual(lastTwo.first?.silent, true)
        XCTAssertEqual(lastTwo.last?.identifier, "k2")
        XCTAssertEqual(lastTwo.last?.silent, false)
    }

    func testReconcileForgetsStaleKeysSoReusedKeyAlerts() {
        let presenter = RecordingPresenter()
        let bridge = NotificationBridge(presenter: presenter)

        bridge.handlePosted(record(key: "k1"))
        bridge.reconcile(with: [])
        XCTAssertEqual(presenter.removed.last, ["k1"])

        bridge.handlePosted(record(key: "k1"))
        XCTAssertEqual(presenter.posted.last?.silent, false)

        bridge.reconcile(with: [])
        bridge.reconcile(with: [])
        XCTAssertEqual(presenter.removed.count, 2)
    }

    func testResetClearsDeliveredAndPresentedKeys() {
        let presenter = RecordingPresenter()
        let bridge = NotificationBridge(presenter: presenter)

        bridge.handlePosted(record(key: "k1"))
        bridge.reset()
        XCTAssertTrue(presenter.removeAllCalled)

        bridge.reconcile(with: [record(key: "k2")])
        XCTAssertTrue(presenter.removed.isEmpty)
        XCTAssertEqual(presenter.posted.map(\.identifier), ["k2"])
    }

    func testActionAndDismissEnvelopes() throws {
        let action = try XCTUnwrap(
            NotificationBridge.actionEnvelope(key: "k1", identifier: "reply.0", replyText: "hi")
        )
        guard case .notificationAction(let request)? = action.payload else {
            return XCTFail("expected notification action")
        }
        XCTAssertEqual(request.key, "k1")
        XCTAssertEqual(request.actionIndex, 0)
        XCTAssertEqual(request.replyText, "hi")
        XCTAssertFalse(request.requestID.isEmpty)

        let tap = try XCTUnwrap(
            NotificationBridge.actionEnvelope(key: "k1", identifier: "action.3", replyText: nil)
        )
        guard case .notificationAction(let tapRequest)? = tap.payload else {
            return XCTFail("expected notification action")
        }
        XCTAssertEqual(tapRequest.actionIndex, 3)
        XCTAssertEqual(tapRequest.replyText, "")

        let dismiss = NotificationBridge.dismissEnvelope(key: "k1")
        guard case .notificationDismiss(let dismissed)? = dismiss.payload else {
            return XCTFail("expected dismiss")
        }
        XCTAssertEqual(dismissed.key, "k1")

        XCTAssertNil(NotificationBridge.actionEnvelope(key: "k1", identifier: "bogus", replyText: nil))
    }
}
