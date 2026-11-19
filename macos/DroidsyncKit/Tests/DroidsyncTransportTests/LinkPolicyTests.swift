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

    // The same corpus is in the phone's LinkPolicyTest.kt; keep the two lists identical.
    private let acceptedHosts: [(String, String)] = [
        ("https://example.com/", "example.com"),
        ("HTTPS://EXAMPLE.com", "example.com"),
        ("HtTp://x.y", "x.y"),
        ("http://example.com:8080/x", "example.com"),
        ("https://example.com?x=1", "example.com"),
        ("https://example.com#frag", "example.com"),
        ("https://example.com/@x", "example.com"),
        ("https://example.com?x=a@b", "example.com"),
        ("https://example.com/a:b", "example.com"),
        ("https://BÜCHER.example/", "bÜcher.example"),
        ("https://bücher.example/", "bücher.example"),
        ("https://xn--bcher-kva.example/", "xn--bcher-kva.example"),
        ("https://日本語.jp/", "日本語.jp"),
        ("https://my_host.example/", "my_host.example"),
        ("https://under_score/", "under_score"),
        ("https://[::1]/", "[::1]"),
        ("https://[2001:DB8::1]:8443/x", "[2001:db8::1]"),
        ("https://[::ffff:1.2.3.4]", "[::ffff:1.2.3.4]"),
        ("https://example.com:1/", "example.com"),
        ("https://example.com:65535/", "example.com"),
        ("https://example.com:00080/", "example.com"),
        ("https://example.com./", "example.com."),
        ("https://192.168.0.1:3000/", "192.168.0.1"),
        ("https://a-b.c-d/", "a-b.c-d"),
        ("https://example.com/\u{0301}x", "example.com"),
        ("https://example.com?\u{0301}", "example.com"),
        ("https://example.com#\u{20DD}", "example.com"),
    ]

    private let rejectedHosts: [String] = [
        "https://",
        "https:///path",
        "https://?q=1",
        "https://#f",
        "https://:80/",
        "https://user:pass@example.com/",
        "https://google.com@evil.example/",
        "https://@example.com/",
        "https://[::1",
        "https://[]/",
        "https://[zz::1]/",
        "https://[::1]x/",
        "https://[1.2.3.4]/",
        "https://[::1]:/",
        "https://[::1]:0/",
        "https://example.com:0/",
        "https://example.com:65536/",
        "https://example.com:123456/",
        "https://example.com:/",
        "https://example.com:80a/",
        "https://example.com::80/",
        "https://example.com../",
        "https://.example.com/",
        "https://a..b/",
        "https://./",
        "https://ex!ample.com/",
        "https://example.com\\@evil.example/",
        "https://exa%6dple.com/",
        "https://ex*ample.com/",
        "https://😀.example/",
        "https://example。com/",
        "https://exa(mple.com/",
        "https://example.com\\path",
        "http\u{017F}://example.com/",
        "https:/\u{0301}/example.com/",
    ]

    func testHostRuleCorpusMatchesThePhone() {
        for (url, host) in acceptedHosts {
            XCTAssertEqual(link(url), .web(url: url, host: host), url)
        }
        for url in rejectedHosts {
            XCTAssertNil(link(url), url)
        }
    }
}
