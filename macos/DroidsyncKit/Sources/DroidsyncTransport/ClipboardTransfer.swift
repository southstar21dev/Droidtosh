import CryptoKit
import Foundation

public enum ClipboardTransfer {
    public static let chunkSize = 256 * 1024
    public static let maxBlobSize = 32 * 1024 * 1024
    public static let maxTextBytes = 512 * 1024
    public static let allowedMimeTypes: Set<String> = ["image/png", "image/jpeg"]
}

public enum ClipboardChunker {
    public static func chunks(for data: Data) -> [(offset: Int, data: Data)] {
        guard !data.isEmpty else { return [] }
        var result: [(offset: Int, data: Data)] = []
        var offset = 0
        while offset < data.count {
            let end = min(offset + ClipboardTransfer.chunkSize, data.count)
            result.append((offset, data.subdata(in: offset..<end)))
            offset = end
        }
        return result
    }
}

public struct ClipboardAssembler {
    public enum ChunkResult: Equatable {
        case progress
        case completed
        case rejected
    }

    private let maxBlobSize: Int
    private let timeout: TimeInterval
    private var transferID: String?
    private var mimeType: String?
    private var expectedSize = 0
    private var buffer = Data()
    private var lastActivity = Date.distantPast

    public init(
        maxBlobSize: Int = ClipboardTransfer.maxBlobSize,
        timeout: TimeInterval = 30
    ) {
        self.maxBlobSize = maxBlobSize
        self.timeout = timeout
    }

    public var activeTransferID: String? { transferID }
    /// MIME type declared by the active transfer's start; read it before `end` clears it.
    public var activeMimeType: String? { mimeType }

    @discardableResult
    public mutating func start(
        transferID: String,
        mimeType: String,
        totalSize: UInt64,
        now: Date = Date()
    ) -> Bool {
        cancel()
        guard ClipboardTransfer.allowedMimeTypes.contains(mimeType),
              totalSize > 0,
              totalSize <= UInt64(maxBlobSize) else {
            return false
        }
        self.transferID = transferID
        self.mimeType = mimeType
        self.expectedSize = Int(totalSize)
        self.buffer = Data()
        self.lastActivity = now
        return true
    }

    public mutating func append(
        transferID: String,
        offset: Int,
        data: Data,
        now: Date = Date()
    ) -> ChunkResult {
        guard self.transferID == transferID, offset == buffer.count else {
            cancel()
            return .rejected
        }
        guard buffer.count + data.count <= expectedSize else {
            cancel()
            return .rejected
        }
        buffer.append(data)
        lastActivity = now
        return buffer.count == expectedSize ? .completed : .progress
    }

    public mutating func end(transferID: String) -> Data? {
        guard self.transferID == transferID, buffer.count == expectedSize else {
            cancel()
            return nil
        }
        let data = buffer
        cancel()
        return data
    }

    @discardableResult
    public mutating func expireIfNeeded(now: Date = Date()) -> Bool {
        guard transferID != nil, now.timeIntervalSince(lastActivity) > timeout else {
            return false
        }
        cancel()
        return true
    }

    public mutating func cancel() {
        transferID = nil
        mimeType = nil
        expectedSize = 0
        buffer = Data()
    }
}

public enum ClipboardHash {
    public static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
