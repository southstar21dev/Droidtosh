import Foundation

/// Which SIM a number dialled from a `tel:` link starts on: the one used last, if the phone still
/// reports it, else the first.
public enum DialAccountPicker {
    public static func defaultAccount(accounts: [String], lastUsed: String?) -> String? {
        if let lastUsed, accounts.contains(lastUsed) { return lastUsed }
        return accounts.first
    }

    /// The number in a `tel:` URL's text (`tel:+49 170`, `tel://+49170`, percent-encoded), as `tel:<number>`
    /// for `LinkPolicy`; spaces are dropped because dialers and web pages add them for readability.
    public static func telLink(from text: String) -> String? {
        guard text.lowercased().hasPrefix("tel:") else { return nil }
        var rest = String(text.dropFirst("tel:".count))
        if rest.hasPrefix("//") { rest.removeFirst(2) }
        let decoded = rest.removingPercentEncoding ?? rest
        let number = decoded.filter { !$0.isWhitespace }
        return number.isEmpty ? nil : "tel:\(number)"
    }
}
