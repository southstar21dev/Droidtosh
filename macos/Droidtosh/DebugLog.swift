import Foundation

/// Appends to ~/Library/Logs/Droidtosh/debug.log, which only this user can read.
/// The previous log is kept as debug.log.1 once the file passes `maxBytes`.
enum DebugLog {
    private static let maxBytes: UInt64 = 5 * 1024 * 1024
    private static let directory = FileManager.default
        .homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Logs/Droidtosh", isDirectory: true)
    private static let url = directory.appendingPathComponent("debug.log")
    private static let rotatedURL = directory.appendingPathComponent("debug.log.1")

    static var fileURL: URL { url }
    static var rotatedFileURL: URL { rotatedURL }
    private static let queue = DispatchQueue(label: "dev.southstar21.droidtosh.debuglog")

    static func log(_ message: String) {
        let line = "[\(Date())] \(message)\n"
        queue.async {
            let fileManager = FileManager.default
            try? fileManager.createDirectory(
                at: directory,
                withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700]
            )
            if let size = (try? fileManager.attributesOfItem(atPath: url.path))?[.size] as? UInt64,
               size > maxBytes {
                try? fileManager.removeItem(at: rotatedURL)
                try? fileManager.moveItem(at: url, to: rotatedURL)
            }
            if !fileManager.fileExists(atPath: url.path) {
                fileManager.createFile(atPath: url.path, contents: nil, attributes: [.posixPermissions: 0o600])
            }
            guard let handle = try? FileHandle(forWritingTo: url) else { return }
            handle.seekToEndOfFile()
            handle.write(Data(line.utf8))
            try? handle.close()
        }
    }
}
