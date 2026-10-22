import XCTest
@testable import DroidsyncTransport

final class FingerprintTests: XCTestCase {
    func testSHA256OfEmptyData() {
        XCTAssertEqual(
            Fingerprint.hexSHA256(Data()),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
    }
}
