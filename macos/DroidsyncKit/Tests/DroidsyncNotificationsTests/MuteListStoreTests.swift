import XCTest
@testable import DroidsyncNotifications

final class MuteListStoreTests: XCTestCase {
    private var defaults: UserDefaults!
    private let suiteName = "dev.southstar21.droidtosh.tests.\(UUID().uuidString)"

    override func setUp() {
        super.setUp()
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    func testSetMutedAndPersistence() {
        let store = MuteListStore(defaults: defaults, key: "muted")
        store.setMuted("com.spammy", true)
        XCTAssertEqual(store.mutedPackages, ["com.spammy"])
        XCTAssertEqual(MuteListStore(defaults: defaults, key: "muted").mutedPackages, ["com.spammy"])
        store.setMuted("com.spammy", false)
        XCTAssertTrue(store.mutedPackages.isEmpty)
    }

    func testSetAll() {
        let store = MuteListStore(defaults: defaults, key: "muted")
        store.setAll(["b", "a"])
        XCTAssertEqual(store.mutedPackages, ["a", "b"])
    }
}
