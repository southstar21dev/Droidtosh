import XCTest
@testable import DroidsyncTransport

final class LinkPolicyTests: XCTestCase {
    private func link(_ raw: String, allowTel: Bool = false) -> LinkPolicy.Link? {
        LinkPolicy.validate(raw, allowTel: allowTel)
    }

    func testAcceptsHttpAndHttpsWithAHost() {
        XCTAssertEqual(link("https://example.com/path?q=1"), .web(url: "https://example.com/path?q=1", host: "example.com"))
        XCTAssertEqual(link("http://Example.COM:8080/"), .web(url: "http://Example.COM:8080/", host: "example.com"))
        XCTAssertEqual(link("HTTPS://example.com")?.display, "example.com")
    }

    func testRejectsOtherSchemes() {
        for raw in [
            "javascript:alert(1)", "file:///etc/passwd", "content://contacts/people",
            "intent://scan/#Intent;scheme=zxing;end", "ftp://example.com/", "market://details?id=x",
            "example.com", "//example.com", "x-apple.systempreferences:com.apple.preference",
        ] {
            XCTAssertNil(link(raw, allowTel: true), raw)
        }
    }

    func testRejectsMissingHostAndUserInfo() {
        XCTAssertNil(link("https://"))
        XCTAssertNil(link("https:///path"))
        XCTAssertNil(link("https://user:pass@example.com/"))
        XCTAssertNil(link("https://google.com@evil.example/"))
    }

    func testRejectsControlWhitespaceAndFormatCharacters() {
        XCTAssertNil(link("https://example.com/a b"))
        XCTAssertNil(link("https://example.com/\n"))
        XCTAssertNil(link("https://example.com/\u{0}"))
        XCTAssertNil(link("https://example.com/\u{7F}"))
        XCTAssertNil(link("https://exam\u{200B}ple.com/"))
        XCTAssertNil(link("https://example.com/\u{202E}fdp"))
        XCTAssertNil(link(" https://example.com/"))
        // Supplementary-plane format (a tag character) and private-use code points, as on the phone.
        XCTAssertNil(link("https://example.com/\u{E0001}"))
        XCTAssertNil(link("https://example.com/\u{F0000}"))
    }

    func testCapsTheLengthInBytes() {
        let prefix = "https://example.com/"
        XCTAssertNotNil(link(prefix + String(repeating: "a", count: LinkPolicy.maxBytes - prefix.utf8.count)))
        XCTAssertNil(link(prefix + String(repeating: "a", count: LinkPolicy.maxBytes - prefix.utf8.count + 1)))
        XCTAssertNil(link(prefix + String(repeating: "\u{E9}", count: LinkPolicy.maxBytes / 2)))
    }

    func testTelIsOnlyAllowedWhenAskedForAndMustLookLikeANumber() {
        XCTAssertEqual(link("tel:+49(170)123-4567", allowTel: true), .tel(url: "tel:+49(170)123-4567", number: "+49(170)123-4567"))
        XCTAssertNil(link("tel:+491701234567", allowTel: false))
        XCTAssertNil(link("tel:", allowTel: true))
        XCTAssertNil(link("tel:abc", allowTel: true))
        XCTAssertNil(link("tel:+++", allowTel: true))
        XCTAssertNil(link("tel:123;ext=4", allowTel: true))
        XCTAssertNil(link("tel:" + String(repeating: "1", count: 40), allowTel: true))
    }

    func testSingleWebURLOnlyAcceptsOneLinkAndTrimsTheEnds() {
        XCTAssertEqual(LinkPolicy.singleWebURL(in: "  https://example.com/x\n"), "https://example.com/x")
        XCTAssertNil(LinkPolicy.singleWebURL(in: "look at https://example.com/x"))
        XCTAssertNil(LinkPolicy.singleWebURL(in: "https://example.com/x https://example.org/"))
        XCTAssertNil(LinkPolicy.singleWebURL(in: "just some text"))
        XCTAssertNil(LinkPolicy.singleWebURL(in: "tel:+491701234567"))
        XCTAssertNil(LinkPolicy.singleWebURL(in: ""))
    }
}
