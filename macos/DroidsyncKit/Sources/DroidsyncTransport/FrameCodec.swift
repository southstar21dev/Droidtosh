import Foundation

public enum FrameError: Error, Equatable {
    case frameTooLarge(Int)
}

public enum FrameCodec {
    public static let maxFrameSize = 1_048_576

    public static func encode(_ payload: Data) -> Data {
        var out = Data(capacity: payload.count + 4)
        let length = UInt32(payload.count).bigEndian
        withUnsafeBytes(of: length) { out.append(contentsOf: $0) }
        out.append(payload)
        return out
    }
}

public struct FrameDecoder {
    private var buffer = Data()
    /// Start of the unconsumed bytes; the consumed prefix is dropped once per `append`.
    private var readOffset = 0

    public init() {}

    public mutating func append(_ data: Data) throws -> [Data] {
        buffer.append(data)
        defer {
            if readOffset > 0 {
                buffer.removeSubrange(0..<readOffset)
                readOffset = 0
            }
        }
        var frames: [Data] = []
        while buffer.count - readOffset >= 4 {
            let length = buffer.withUnsafeBytes {
                $0.loadUnaligned(fromByteOffset: readOffset, as: UInt32.self)
            }.bigEndian
            guard Int(length) <= FrameCodec.maxFrameSize else {
                throw FrameError.frameTooLarge(Int(length))
            }
            let end = readOffset + 4 + Int(length)
            guard buffer.count >= end else { break }
            frames.append(buffer.subdata(in: (readOffset + 4)..<end))
            readOffset = end
        }
        return frames
    }
}
