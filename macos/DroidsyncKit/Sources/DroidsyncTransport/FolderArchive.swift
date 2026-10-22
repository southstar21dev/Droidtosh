import Foundation

/// Zips a folder so it can be sent as one file (§7 carries single files only).
///
/// The archive is `<folder>.zip` with the folder itself at its root, so unzipping it on the
/// phone recreates the folder with its relative structure. It's made by
/// `ditto -c -k --sequesterRsrc --keepParent`, which Finder's "Compress" also uses: resource
/// forks and extended attributes go to `__MACOSX/` instead of being lost or breaking other
/// unzip tools.
///
/// Blocking: call it off the main thread and off the file transfer queue.
public enum FolderArchive {
    /// Whether `url` is a directory (a package such as an `.app` counts: it's sent zipped).
    public static func isFolder(_ url: URL) -> Bool {
        (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true
    }

    /// The name the archive of `folder` is offered under.
    public static func archiveName(for folder: URL) -> String {
        folder.standardizedFileURL.lastPathComponent + ".zip"
    }

    /// Zips `folder` into a new directory under `temporaryDirectory` and returns the archive's
    /// URL; delete its parent directory when done with it. Fails with `.tooLarge` as soon as the
    /// archive grows past `maxSize`, with `.cancelled` once `isCancelled` returns true, and with
    /// `.compressionFailed` when ditto fails. Nothing is left behind on failure.
    public static func zip(
        folder: URL,
        temporaryDirectory: URL,
        maxSize: UInt64 = FileTransferLimits.maxFileSize,
        pollInterval: TimeInterval = 0.1,
        isCancelled: () -> Bool = { false }
    ) throws -> URL {
        guard isFolder(folder) else { throw FileTransferError.unreadable }
        let fileManager = FileManager.default
        let directory = temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let archive = directory.appendingPathComponent(archiveName(for: folder))
        do {
            try runDitto(folder: folder, archive: archive, maxSize: maxSize, pollInterval: pollInterval, isCancelled: isCancelled)
            return archive
        } catch {
            try? fileManager.removeItem(at: directory)
            throw error
        }
    }

    private static func runDitto(
        folder: URL,
        archive: URL,
        maxSize: UInt64,
        pollInterval: TimeInterval,
        isCancelled: () -> Bool
    ) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        process.arguments = ["-c", "-k", "--sequesterRsrc", "--keepParent", folder.path, archive.path]
        // ditto's messages go to a file next to the archive: a pipe nobody drains could fill up
        // and block it.
        let log = archive.deletingLastPathComponent().appendingPathComponent("ditto.log")
        guard FileManager.default.createFile(atPath: log.path, contents: nil) else {
            throw FileTransferError.compressionFailed("can't create \(log.path)")
        }
        let logHandle = try FileHandle(forWritingTo: log)
        defer {
            try? logHandle.close()
            try? FileManager.default.removeItem(at: log)
        }
        process.standardError = logHandle
        process.standardOutput = FileHandle.nullDevice
        try process.run()
        var failure: FileTransferError?
        while process.isRunning {
            if isCancelled() {
                failure = .cancelled
            } else if size(of: archive) > maxSize {
                failure = .tooLarge
            }
            if failure != nil {
                process.terminate()
                break
            }
            Thread.sleep(forTimeInterval: pollInterval)
        }
        process.waitUntilExit()
        if let failure { throw failure }
        guard process.terminationReason == .exit, process.terminationStatus == 0 else {
            let output = (try? Data(contentsOf: log)) ?? Data()
            let message = String(decoding: output, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            throw FileTransferError.compressionFailed(message.isEmpty ? "ditto exited with \(process.terminationStatus)" : message)
        }
        if isCancelled() { throw FileTransferError.cancelled }
        guard size(of: archive) <= maxSize else { throw FileTransferError.tooLarge }
    }

    private static func size(of url: URL) -> UInt64 {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attributes?[.size] as? NSNumber)?.uint64Value ?? 0
    }
}
