import Foundation

public enum PairingError: Error, Equatable {
    case malformed
    case unsupportedVersion(Int)
    case invalidPSKLength
}

public struct PairingPayload: Equatable {
    public static let version = 2

    public let deviceID: String
    public let deviceName: String
    /// The relay's host and port; nil only when `mode` is `.local`.
    public let host: String?
    public let port: UInt16?
    public let room: String
    public let fingerprint: String
    /// SHA-256 of the relay's TLS certificate (DER, lowercase hex); the phone pins it. Nil only
    /// when `mode` is `.local`.
    public let relayFingerprint: String?
    public let psk: Data
    /// The Mac's connection mode, which becomes the phone's initial one (PROTOCOL-v2.md §1.5).
    public let mode: ConnectionMode
    /// The Mac's private IPv4 address when the code was made, for when Bonjour can't find it.
    public let localHost: String?
    /// The local listener's port; nil only when `mode` is `.relay`.
    public let localPort: UInt16?

    public init(
        deviceID: String,
        deviceName: String,
        host: String?,
        port: UInt16?,
        room: String,
        fingerprint: String,
        relayFingerprint: String?,
        psk: Data,
        mode: ConnectionMode = .relay,
        localHost: String? = nil,
        localPort: UInt16? = nil
    ) {
        self.deviceID = deviceID
        self.deviceName = deviceName
        self.host = host
        self.port = port
        self.room = room
        self.fingerprint = fingerprint
        self.relayFingerprint = relayFingerprint
        self.psk = psk
        self.mode = mode
        self.localHost = localHost
        self.localPort = localPort
    }

    public func uri() -> String {
        var components = URLComponents()
        components.scheme = "droidsync"
        components.host = "pair"
        var items = [
            URLQueryItem(name: "v", value: String(Self.version)),
            URLQueryItem(name: "did", value: deviceID),
            URLQueryItem(name: "name", value: deviceName),
        ]
        if mode.usesRelay, let host, let port {
            items.append(URLQueryItem(name: "host", value: host))
            items.append(URLQueryItem(name: "port", value: String(port)))
        }
        items.append(URLQueryItem(name: "fp", value: fingerprint))
        if mode.usesRelay, let relayFingerprint {
            items.append(URLQueryItem(name: "rfp", value: relayFingerprint))
        }
        items += [
            URLQueryItem(name: "psk", value: Base64URL.encode(psk)),
            URLQueryItem(name: "room", value: room),
            URLQueryItem(name: "mode", value: mode.rawValue),
        ]
        if mode.usesLocal {
            if let localHost { items.append(URLQueryItem(name: "lhost", value: localHost)) }
            if let localPort { items.append(URLQueryItem(name: "lport", value: String(localPort))) }
        }
        components.queryItems = items
        return components.string ?? ""
    }

    public static func parse(_ uri: String) throws -> PairingPayload {
        guard let components = URLComponents(string: uri),
              components.scheme == "droidsync",
              components.host == "pair",
              let items = components.queryItems else {
            throw PairingError.malformed
        }
        func value(_ name: String) -> String? {
            items.first { $0.name == name }?.value
        }
        guard let versionString = value("v"), let version = Int(versionString) else {
            throw PairingError.malformed
        }
        guard version == Self.version else { throw PairingError.unsupportedVersion(version) }
        let mode: ConnectionMode
        if let modeString = value("mode") {
            guard let parsed = ConnectionMode(rawValue: modeString) else { throw PairingError.malformed }
            mode = parsed
        } else {
            mode = .relay
        }
        guard let deviceID = value("did"),
              let deviceName = value("name"),
              let room = value("room"), room.range(of: #"^[A-Za-z0-9_-]{43}$"#, options: .regularExpression) != nil,
              let fingerprint = value("fp"),
              let pskString = value("psk"),
              let psk = Base64URL.decode(pskString) else {
            throw PairingError.malformed
        }
        var host: String?
        var port: UInt16?
        var relayFingerprint: String?
        if mode.usesRelay {
            guard let relayHost = value("host"),
                  let portString = value("port"), let relayPort = UInt16(portString),
                  let rfp = value("rfp"), RelayFingerprint.isValid(rfp) else {
                throw PairingError.malformed
            }
            host = relayHost
            port = relayPort
            relayFingerprint = rfp
        }
        var localPort: UInt16?
        if mode.usesLocal {
            guard let portString = value("lport"), let parsed = UInt16(portString), parsed > 0 else {
                throw PairingError.malformed
            }
            localPort = parsed
        }
        guard psk.count == Auth.pskLength else { throw PairingError.invalidPSKLength }
        return PairingPayload(
            deviceID: deviceID, deviceName: deviceName, host: host,
            port: port, room: room, fingerprint: fingerprint,
            relayFingerprint: relayFingerprint, psk: psk,
            mode: mode, localHost: mode.usesLocal ? value("lhost") : nil, localPort: localPort
        )
    }
}

public enum Base64URL {
    public static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func decode(_ string: String) -> Data? {
        var base64 = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64 += "=" }
        return Data(base64Encoded: base64)
    }
}
