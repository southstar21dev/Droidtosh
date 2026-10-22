import Foundation
import CryptoKit

public enum AuthError: Error, Equatable {
    case invalidMessage
    case invalidMAC
    /// Both sides authenticated, but their protocol version ranges don't overlap.
    case unsupportedVersion
}

public enum Auth {
    public static let pskLength = 32
    public static let nonceLength = 32

    public static func macInput(role: String, serverNonce: Data, clientNonce: Data) -> Data {
        var out = Data(role.utf8)
        out.append(serverNonce)
        out.append(clientNonce)
        return out
    }

    public static func mac(psk: Data, role: String, serverNonce: Data, clientNonce: Data) -> Data {
        let key = SymmetricKey(data: psk)
        let code = HMAC<SHA256>.authenticationCode(
            for: macInput(role: role, serverNonce: serverNonce, clientNonce: clientNonce),
            using: key
        )
        return Data(code)
    }

    public static func constantTimeEquals(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        var difference: UInt8 = 0
        for (a, b) in zip(lhs, rhs) { difference |= a ^ b }
        return difference == 0
    }
}
