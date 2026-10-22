import XCTest
@testable import DroidsyncCalls

final class AppNamesTests: XCTestCase {
    func testWhatsAppBusinessHasAHumanName() {
        XCTAssertEqual(AppNames.displayName(for: "com.whatsapp.w4b"), "WhatsApp Business")
        XCTAssertTrue(VoipApps.knownPackages.contains("com.whatsapp.w4b"))
    }

    func testPhoneLabelWins() {
        XCTAssertEqual(
            AppNames.displayName(for: "com.whatsapp.w4b", labels: ["com.whatsapp.w4b": "WA Business"]),
            "WA Business"
        )
    }

    func testLabelEqualToPackageIsIgnored() {
        // The phone falls back to the package when it can't read an app's label.
        XCTAssertEqual(
            AppNames.displayName(for: "com.whatsapp.w4b", labels: ["com.whatsapp.w4b": "com.whatsapp.w4b"]),
            "WhatsApp Business"
        )
    }

    func testUnknownPackageIsPrettifiedNotRaw() {
        XCTAssertEqual(AppNames.displayName(for: "com.example.chatapp"), "Example")
        XCTAssertEqual(AppNames.displayName(for: "im.vector.app"), "Vector")
        XCTAssertNotEqual(AppNames.displayName(for: "org.example.voip"), "org.example.voip")
    }
}
