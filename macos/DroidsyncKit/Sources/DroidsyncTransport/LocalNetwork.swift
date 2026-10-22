import Foundation
import NIOCore

/// How the phone reaches this Mac (PROTOCOL-v2.md §1.5).
public enum ConnectionMode: String, CaseIterable, Sendable {
    case relay
    /// Direct on the local network only (§1.4).
    case local
    /// The relay, with the local network as the phone's fallback.
    case both

    public var usesRelay: Bool { self != .local }
    public var usesLocal: Bool { self != .relay }
}

/// Which path carries a phone session.
public enum SessionPath: String, Equatable, Sendable {
    case relay
    case local
}

/// The Mac's direct listener (§1.4).
public struct LocalListenerOptions: Equatable, Sendable {
    public static let defaultPort = 7444
    public static let maxUnauthenticated = 4
    public static let authTimeout: TimeInterval = 10

    /// 0 binds an ephemeral port (tests).
    public let port: Int
    /// The Bonjour advertisement; nil advertises nothing.
    public let advertisement: LocalAdvertisement?

    public init(port: Int, advertisement: LocalAdvertisement?) {
        self.port = port
        self.advertisement = advertisement
    }
}

public struct LocalAdvertisement: Equatable, Sendable {
    public static let serviceType = "_droidtosh._tcp."

    public let name: String
    /// The Mac's certificate fingerprint; only its first 16 characters are published.
    public let fingerprint: String

    public init(name: String, fingerprint: String) {
        self.name = name
        self.fingerprint = fingerprint
    }

    public var txtRecord: [String: Data] {
        ["v": Data("2".utf8), "fp": Data(fingerprint.prefix(16).lowercased().utf8)]
    }
}

/// Only loopback, private and link-local peers may use the local listener (§1.4).
public enum LocalAddressPolicy {
    public static func isAllowed(_ address: SocketAddress?) -> Bool {
        switch address {
        case .v4(let v4)?:
            return isAllowed(ipv4: withUnsafeBytes(of: v4.address.sin_addr.s_addr) { Array($0) })
        case .v6(let v6)?:
            return isAllowed(ipv6: withUnsafeBytes(of: v6.address.sin6_addr) { Array($0) })
        default:
            return false
        }
    }

    /// `bytes` in network order.
    static func isAllowed(ipv4 bytes: [UInt8]) -> Bool {
        guard bytes.count == 4 else { return false }
        switch (bytes[0], bytes[1]) {
        case (127, _), (10, _): return true
        case (172, 16...31): return true
        case (192, 168): return true
        case (169, 254): return true
        case (100, 64...127): return true
        default: return false
        }
    }

    static func isAllowed(ipv6 bytes: [UInt8]) -> Bool {
        guard bytes.count == 16 else { return false }
        if bytes[0..<15].allSatisfy({ $0 == 0 }) && bytes[15] == 1 { return true }
        if bytes[0..<10].allSatisfy({ $0 == 0 }) && bytes[10] == 0xFF && bytes[11] == 0xFF {
            return isAllowed(ipv4: Array(bytes[12..<16]))
        }
        if bytes[0] & 0xFE == 0xFC { return true }
        if bytes[0] == 0xFE && bytes[1] & 0xC0 == 0x80 { return true }
        return false
    }
}

public enum LocalNetworkAddress {
    /// The Mac's first private IPv4 address on an active, non-loopback interface, preferring
    /// `en*` (Wi-Fi and Ethernet) over VPN and bridge interfaces; nil when there is none.
    public static func primaryIPv4() -> String? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return nil }
        defer { freeifaddrs(head) }
        var candidates: [(name: String, address: String)] = []
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let entry = pointer.pointee
            let flags = Int32(entry.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0,
                  let sockaddr = entry.ifa_addr, sockaddr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let bytes = sockaddr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                withUnsafeBytes(of: $0.pointee.sin_addr.s_addr) { Array($0) }
            }
            guard LocalAddressPolicy.isAllowed(ipv4: bytes), bytes[0] != 127, !(bytes[0] == 169 && bytes[1] == 254) else { continue }
            candidates.append((String(cString: entry.ifa_name), bytes.map(String.init).joined(separator: ".")))
        }
        return (candidates.first { $0.name.hasPrefix("en") } ?? candidates.first)?.address
    }
}

/// Publishes the listener over Bonjour. `NetService` needs a run loop, so it lives on the main one.
final class LocalServiceAdvertiser: NSObject, NetServiceDelegate {
    private var service: NetService?

    func start(_ advertisement: LocalAdvertisement, port: Int) {
        DispatchQueue.main.async { [self] in
            stopOnMain()
            let service = NetService(domain: "local.", type: LocalAdvertisement.serviceType, name: advertisement.name, port: Int32(port))
            service.setTXTRecord(NetService.data(fromTXTRecord: advertisement.txtRecord))
            service.delegate = self
            service.publish()
            self.service = service
        }
    }

    func stop() {
        DispatchQueue.main.async { [self] in stopOnMain() }
    }

    private func stopOnMain() {
        service?.stop()
        service = nil
    }
}
