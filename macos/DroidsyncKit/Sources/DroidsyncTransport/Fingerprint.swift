import Foundation
import CryptoKit

public enum Fingerprint {
    public static func hexSHA256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

public enum RelayFingerprint {
    /// A relay fingerprint is the lowercase hex SHA-256 of its certificate's DER bytes.
    public static func isValid(_ value: String) -> Bool {
        value.range(of: #"^[0-9a-f]{64}$"#, options: .regularExpression) != nil
    }

    /// Accepts what a user pastes from the relay log: case and `:`, `-` or whitespace separators are ignored.
    public static func normalized(_ value: String) -> String? {
        let hex = value.lowercased().filter { !":- \n\t".contains($0) }
        return isValid(hex) ? hex : nil
    }
}
