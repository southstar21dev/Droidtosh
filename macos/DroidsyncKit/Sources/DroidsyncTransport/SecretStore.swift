import Foundation

/// The Mac's secrets (TLS identity, pairing key, room token) as files only the user can read, the
/// way Syncthing and KDE Connect keep theirs. The login keychain ties each item to the exact build
/// of the self-signed app that wrote it, so it prompted for the password after every update;
/// files never prompt. The folder is `0700` and each file `0600`, written atomically.
public struct SecretStore: Sendable {
    /// `~/Library/Application Support/Droidtosh/Secrets`.
    public static let `default` = SecretStore(
        directory: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Droidtosh", isDirectory: true)
            .appendingPathComponent("Secrets", isDirectory: true)
    )

    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
    }

    public func load(_ name: String) throws -> Data? {
        do {
            return try Data(contentsOf: url(name))
        } catch CocoaError.fileReadNoSuchFile {
            return nil
        }
    }

    public func save(_ data: Data, as name: String) throws {
        let manager = FileManager.default
        try manager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        // An existing folder keeps its mode through createDirectory; tighten it either way.
        try manager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        let temporary = directory.appendingPathComponent(".\(name).\(UUID().uuidString).tmp")
        guard manager.createFile(atPath: temporary.path, contents: data, attributes: [.posixPermissions: 0o600]) else {
            throw CocoaError(.fileWriteUnknown)
        }
        guard rename(temporary.path, url(name).path) == 0 else {
            let code = errno
            try? manager.removeItem(at: temporary)
            throw POSIXError(POSIXErrorCode(rawValue: code) ?? .EIO)
        }
    }

    public func delete(_ name: String) {
        try? FileManager.default.removeItem(at: url(name))
    }

    private func url(_ name: String) -> URL {
        directory.appendingPathComponent(name, isDirectory: false)
    }
}
