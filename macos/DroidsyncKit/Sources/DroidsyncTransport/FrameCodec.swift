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

    public init() {}

    public mutating func append(_ data: Data) throws -> [Data] {
        buffer.append(data)
        var frames: [Data] = []
        while buffer.count >= 4 {
            let length = buffer.prefix(4).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) }.bigEndian
            guard Int(length) <= FrameCodec.maxFrameSize else {
                throw FrameError.frameTooLarge(Int(length))
            }
            guard buffer.count >= 4 + Int(length) else { break }
            frames.append(buffer.subdata(in: 4..<(4 + Int(length))))
            buffer.removeSubrange(0..<(4 + Int(length)))
        }
        return frames
    }
}
