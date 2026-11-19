import Foundation

/// What an `OpenUrl` may carry (PROTOCOL-v2.md section 10), checked by the receiver and before
/// sending. Strict on purpose: a link is only ever opened by a user click, but the notification
/// still must not show or open anything surprising. Mirrors the phone's `LinkPolicy`.
public enum LinkPolicy {
    public static let maxBytes = 2048
    private static let maxTelLength = 32

    public enum Link: Equatable, Sendable {
        case web(url: String, host: String)
        case tel(url: String, number: String)

        public var url: String {
            switch self {
            case .web(let url, _), .tel(let url, _): return url
            }
        }

        /// What a notification shows after "Open link from phone:".
        public var display: String {
            switch self {
            case .web(_, let host): return host
            case .tel(_, let number): return number
            }
        }
    }

    /// The link if `raw` is acceptable, else nil. `allowTel` admits `tel:` numbers.
    public static func validate(_ raw: String, allowTel: Bool) -> Link? {
        guard !raw.isEmpty, raw.utf8.count <= maxBytes else { return nil }
        // By code point, like the phone: a String compares by grapheme cluster, so a combining mark
        // right after a `/`, `?`, `#` or `://` would otherwise hide the delimiter.
        let scalars = Array(raw.unicodeScalars)
        guard !scalars.contains(where: isForbidden) else { return nil }
        if hasASCIIPrefix(scalars, "tel:") { return allowTel ? tel(scalars) : nil }
        return web(raw, scalars)
    }

    /// `text` as a link when, ignoring surrounding whitespace, it is exactly one http(s) URL.
    public static func singleWebURL(in text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if case .web(let url, _)? = validate(trimmed, allowTel: false) { return url }
        return nil
    }

    private static func isForbidden(_ scalar: Unicode.Scalar) -> Bool {
        if scalar.properties.isWhitespace { return true }
        switch scalar.properties.generalCategory {
        // Format characters include bidi overrides and zero-width characters, which disguise a host.
        case .control, .format, .privateUse, .unassigned, .lineSeparator, .paragraphSeparator, .spaceSeparator:
            return true
        default:
            return false
        }
    }

    private static func web(_ raw: String, _ scalars: [Unicode.Scalar]) -> Link? {
        guard scalars.count >= 3,
              let separator = (0...(scalars.count - 3)).first(where: {
                  scalars[$0] == ":" && scalars[$0 + 1] == "/" && scalars[$0 + 2] == "/"
              })
        else { return nil }
        let scheme = scalars[..<separator].map(asciiLowercased)
        guard scheme == Array("http".unicodeScalars) || scheme == Array("https".unicodeScalars) else { return nil }
        let authority = scalars[(separator + 3)...].prefix { $0 != "/" && $0 != "?" && $0 != "#" }
        guard let host = parseHost(String(String.UnicodeScalarView(authority))) else { return nil }
        return .web(url: raw, host: host)
    }

    /// Lowercases ASCII letters only, so no other letter (such as U+017F) can pass for one.
    private static func asciiLowercased(_ scalar: Unicode.Scalar) -> Unicode.Scalar {
        scalar.value >= 0x41 && scalar.value <= 0x5A ? Unicode.Scalar(scalar.value + 32)! : scalar
    }

    private static func hasASCIIPrefix(_ scalars: [Unicode.Scalar], _ prefix: String) -> Bool {
        let wanted = Array(prefix.unicodeScalars)
        return scalars.count >= wanted.count && zip(scalars, wanted).allSatisfy { asciiLowercased($0) == $1 }
    }

    /// The host of an authority under the one rule both platforms share (PROTOCOL-v2.md section 10),
    /// or nil. No userinfo; a bracketed IPv6 literal or dot-separated non-empty labels (one trailing
    /// dot allowed) of ASCII letters, digits, `-`, `_` and non-ASCII letters, marks and digits; an
    /// optional `:port` of 1 to 5 digits in 1..65535. Lowercased for ASCII only.
    static func parseHost(_ authority: String) -> String? {
        let scalars = Array(authority.unicodeScalars)
        guard !scalars.isEmpty, !scalars.contains("@") else { return nil }
        let host: [Unicode.Scalar]
        var port: [Unicode.Scalar]?
        if scalars[0] == "[" {
            guard let close = scalars.firstIndex(of: "]") else { return nil }
            host = Array(scalars[0...close])
            let tail = Array(scalars[(close + 1)...])
            if tail.isEmpty {
                port = nil
            } else if tail[0] == ":" {
                port = Array(tail.dropFirst())
            } else {
                return nil
            }
            let literal = host.dropFirst().dropLast()
            guard !literal.isEmpty, literal.contains(":") else { return nil }
            guard literal.allSatisfy({ ($0.value >= 0x30 && $0.value <= 0x39) || ":.abcdefABCDEF".unicodeScalars.contains($0) }) else { return nil }
        } else {
            let colon = scalars.firstIndex(of: ":")
            host = colon.map { Array(scalars[..<$0]) } ?? scalars
            port = colon.map { Array(scalars[($0 + 1)...]) }
            var labels = host.split(separator: ".", omittingEmptySubsequences: false)
            if host.last == "." { labels.removeLast() }
            guard !labels.isEmpty, labels.allSatisfy({ !$0.isEmpty && $0.allSatisfy(isHostScalar) }) else { return nil }
        }
        if let port {
            guard !port.isEmpty, port.count <= 5, port.allSatisfy({ $0.value >= 0x30 && $0.value <= 0x39 }) else { return nil }
            guard let number = Int(String(String.UnicodeScalarView(port))), (1...65535).contains(number) else { return nil }
        }
        return String(String.UnicodeScalarView(host.map(asciiLowercased)))
    }

    private static func isHostScalar(_ scalar: Unicode.Scalar) -> Bool {
        if scalar.isASCII {
            let v = scalar.value
            return (v >= 0x61 && v <= 0x7A) || (v >= 0x41 && v <= 0x5A) || (v >= 0x30 && v <= 0x39) || v == 0x2D || v == 0x5F
        }
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
             .nonspacingMark, .spacingMark, .enclosingMark, .decimalNumber:
            return true
        default:
            return false
        }
    }

    private static func tel(_ scalars: [Unicode.Scalar]) -> Link? {
        let number = scalars.dropFirst("tel:".count)
        let isDigit = { (scalar: Unicode.Scalar) in scalar.value >= 0x30 && scalar.value <= 0x39 }
        guard !number.isEmpty, number.count <= maxTelLength else { return nil }
        guard number.allSatisfy({ isDigit($0) || "+*#().-".unicodeScalars.contains($0) }) else { return nil }
        guard number.contains(where: isDigit) else { return nil }
        let text = String(String.UnicodeScalarView(number))
        return .tel(url: "tel:\(text)", number: text)
    }
}
