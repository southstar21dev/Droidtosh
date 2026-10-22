import Foundation

public struct RelayJoinLine: Equatable {
    /// The join-line protocol tag; the relay answers `ERROR upgrade` to older tags.
    public static let protocolTag = "DROIDSYNC/2"

    public enum Role: String {
        case mac
        case phone
    }

    public let room: String
    public let role: Role
    public let fcmToken: String?

    public init(room: String, role: Role, fcmToken: String? = nil) {
        self.room = room
        self.role = role
        self.fcmToken = fcmToken
    }

    public func formatted() -> String {
        if let fcmToken, !fcmToken.isEmpty {
            return "\(Self.protocolTag) \(room) \(role.rawValue) \(fcmToken)\n"
        }
        return "\(Self.protocolTag) \(room) \(role.rawValue)\n"
    }
}

public enum RelayResponse: Equatable {
    case paired
    case wait
    case error(String)

    public static func parse(_ line: String) throws -> RelayResponse {
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        switch trimmed {
        case "PAIRED": return .paired
        case "WAIT": return .wait
        default:
            if trimmed.hasPrefix("ERROR ") {
                return .error(String(trimmed.dropFirst("ERROR ".count)))
            }
            throw RelayJoinLineError.malformedResponse
        }
    }
}

public enum RelayJoinLineError: Error, Equatable {
    case malformedResponse
}
