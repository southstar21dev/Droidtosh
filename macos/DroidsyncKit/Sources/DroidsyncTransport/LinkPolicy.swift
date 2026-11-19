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
        guard !raw.unicodeScalars.contains(where: isForbidden) else { return nil }
        if raw.lowercased().hasPrefix("tel:") { return allowTel ? tel(raw) : nil }
        return web(raw)
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

    private static func web(_ raw: String) -> Link? {
        guard let separator = raw.range(of: "://") else { return nil }
        let scheme = raw[raw.startIndex..<separator.lowerBound].lowercased()
        guard scheme == "http" || scheme == "https" else { return nil }
        guard let components = URLComponents(string: raw),
              components.user == nil, components.password == nil,
              let host = components.host?.lowercased(), !host.isEmpty
        else { return nil }
        return .web(url: raw, host: host)
    }

    private static func tel(_ raw: String) -> Link? {
        let number = String(raw.dropFirst("tel:".count))
        guard !number.isEmpty, number.count <= maxTelLength else { return nil }
        guard number.allSatisfy({ $0.isASCII && ($0.isNumber || "+*#().-".contains($0)) }) else { return nil }
        guard number.contains(where: { $0.isNumber }) else { return nil }
        return .tel(url: "tel:\(number)", number: number)
    }
}
