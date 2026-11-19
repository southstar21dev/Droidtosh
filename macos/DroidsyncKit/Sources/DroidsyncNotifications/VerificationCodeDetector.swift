import Foundation

/// Finds a one-time verification code (4 to 8 digits, optionally split by a space or dash)
/// in a notification's text. A code is only reported when a keyword such as "code", "OTP"
/// or "PIN" sits near it, so phone numbers, amounts, dates, times and order numbers on their
/// own are left alone. The result has the separators removed, ready to paste.
public enum VerificationCodeDetector {
    public static let maxKeywordDistance = 60

    private static let keywordRegex = try! NSRegularExpression(
        pattern: #"(code|c[oó]digo|codice|kode|kodu|\bk[oó]d|\bcod\b|код|\botp\b|\bpin\b|passcode|password|passwort|mot de passe|contraseña|verification|verify|verific\w*|vérif\w*|bestätigungs\w*|einmal\w*|one[- ]time|رمز|كود|验证码|驗證碼|認証|コード)"#,
        options: [.caseInsensitive]
    )
    private static let candidateRegex = try! NSRegularExpression(
        pattern: #"\d{3,4}[ \-]\d{3,4}|\d{4,8}"#
    )
    private static let unitAfterRegex = try! NSRegularExpression(
        pattern: #"^\s?(%|\$|€|£|₽|₴|₹|usd\b|eur\b|gbp\b|am\b|pm\b|a\.m|p\.m|min\b|mins\b|minutes?\b|hours?\b|hrs?\b|seconds?\b|km\b|kg\b|mb\b|gb\b)"#,
        options: [.caseInsensitive]
    )

    public static func code(title: String, body: String) -> String? {
        code(in: title + "\n" + body)
    }

    public static func code(in text: String) -> String? {
        let ns = text as NSString
        let full = NSRange(location: 0, length: ns.length)
        let keywords = keywordRegex.matches(in: text, range: full).map(\.range)
        guard !keywords.isEmpty else { return nil }

        var best: (distance: Int, digits: String)?
        for match in candidateRegex.matches(in: text, range: full) {
            let range = match.range
            let raw = ns.substring(with: range)
            let digits = raw.filter(\.isNumber)
            guard (4...8).contains(digits.count) else { continue }
            guard !looksLikeOtherNumber(ns: ns, range: range) else { continue }
            let distance = keywords.map { gap(range, $0) }.min() ?? Int.max
            guard distance <= maxKeywordDistance else { continue }
            // A bare year is a code only when the keyword is right beside it.
            if raw.count == digits.count, digits.count == 4, isYear(digits), distance > 4 { continue }
            if best == nil || distance < best!.distance { best = (distance, digits) }
        }
        return best?.digits
    }

    private static func gap(_ a: NSRange, _ b: NSRange) -> Int {
        // A code usually follows its keyword, so one in front of it counts as slightly farther.
        if NSMaxRange(a) <= b.location { return b.location - NSMaxRange(a) + 2 }
        if NSMaxRange(b) <= a.location { return a.location - NSMaxRange(b) }
        return 0
    }

    private static func isYear(_ digits: String) -> Bool {
        guard let value = Int(digits) else { return false }
        return (1900...2100).contains(value)
    }

    private static func char(_ ns: NSString, _ index: Int) -> Character? {
        guard index >= 0, index < ns.length, let scalar = UnicodeScalar(ns.character(at: index)) else { return nil }
        return Character(scalar)
    }

    private static func isASCIILetter(_ c: Character) -> Bool {
        c.isASCII && c.isLetter
    }

    /// Amounts, dates, times, phone numbers and order numbers around a digit run.
    private static func looksLikeOtherNumber(ns: NSString, range: NSRange) -> Bool {
        let start = range.location
        let end = NSMaxRange(range)
        let prev = char(ns, start - 1)
        let prev2 = char(ns, start - 2)
        let next = char(ns, end)
        let next2 = char(ns, end + 1)

        if let prev {
            if "$€£¥₹+#".contains(prev) || prev.isNumber || isASCIILetter(prev) { return true }
            if "/.,:-".contains(prev), prev2?.isNumber == true { return true }
        }
        if let next {
            if next.isNumber || isASCIILetter(next) { return true }
            if "/.,:-".contains(next), next2?.isNumber == true { return true }
        }
        let rest = ns.substring(from: end) as NSString
        return unitAfterRegex.firstMatch(in: rest as String, range: NSRange(location: 0, length: rest.length)) != nil
    }
}
