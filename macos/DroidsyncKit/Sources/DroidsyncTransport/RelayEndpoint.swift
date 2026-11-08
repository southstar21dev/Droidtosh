import Foundation

/// A fully specified relay join target, built from the user's settings and the room token.
public struct RelayEndpoint: Equatable, Sendable {
    public let host: String
    public let port: UInt16
    public let room: String

    public init(host: String, port: UInt16, room: String) {
        self.host = host
        self.port = port
        self.room = room
    }

    /// Returns nil unless the host is non-blank, the port is 1–65535 and the room is set.
    public init?(host: String, portText: String, room: String) {
        let host = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty, let port = Self.port(from: portText), !room.isEmpty else { return nil }
        self.init(host: host, port: port, room: room)
    }

    public static func port(from text: String) -> UInt16? {
        guard let value = Int(text.trimmingCharacters(in: .whitespacesAndNewlines)),
              (1...65535).contains(value) else { return nil }
        return UInt16(value)
    }
}

public enum RoomToken {
    /// 32 random bytes, base64url without padding.
    public static func generate() -> String {
        Base64URL.encode(randomNonce())
    }

    public static func isValid(_ token: String) -> Bool {
        token.range(of: #"^[A-Za-z0-9_-]{43}$"#, options: .regularExpression) != nil
    }
}
