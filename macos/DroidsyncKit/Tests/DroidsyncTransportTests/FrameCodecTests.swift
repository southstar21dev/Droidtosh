import XCTest
@testable import DroidsyncTransport

final class FrameCodecTests: XCTestCase {
    func testEncodeKnownVector() {
        XCTAssertEqual(
            FrameCodec.encode(Data([0x01, 0x02, 0x03])),
            Data([0x00, 0x00, 0x00, 0x03, 0x01, 0x02, 0x03])
        )
    }

    func testDecodeSingleFrame() throws {
        var decoder = FrameDecoder()
        let frames = try decoder.append(FrameCodec.encode(Data([0xAA, 0xBB])))
        XCTAssertEqual(frames, [Data([0xAA, 0xBB])])
    }

    func testDecodeSplitAcrossChunks() throws {
        let frame = FrameCodec.encode(Data(repeating: 0x7F, count: 300))
        var decoder = FrameDecoder()
        var received: [Data] = []
        for byte in frame {
            received += try decoder.append(Data([byte]))
        }
        XCTAssertEqual(received, [Data(repeating: 0x7F, count: 300)])
    }

    func testDecodeMultipleFramesInOneChunk() throws {
        var decoder = FrameDecoder()
        let frames = try decoder.append(FrameCodec.encode(Data([1])) + FrameCodec.encode(Data([2, 3])))
        XCTAssertEqual(frames, [Data([1]), Data([2, 3])])
    }

    func testManyFramesWithPartialTailAcrossAppends() throws {
        var stream = Data()
        for index in 0..<2_000 { stream += FrameCodec.encode(Data(repeating: UInt8(index % 251), count: index % 17)) }
        var decoder = FrameDecoder()
        var received: [Data] = []
        // Odd chunk size so frames straddle appends.
        var cursor = 0
        while cursor < stream.count {
            let next = min(cursor + 1_001, stream.count)
            received += try decoder.append(stream.subdata(in: cursor..<next))
            cursor = next
        }
        XCTAssertEqual(received.count, 2_000)
        XCTAssertEqual(received[1_999], Data(repeating: UInt8(1_999 % 251), count: 1_999 % 17))
    }

    func testZeroLengthFrame() throws {
        var decoder = FrameDecoder()
        let frames = try decoder.append(Data([0x00, 0x00, 0x00, 0x00]))
        XCTAssertEqual(frames, [Data()])
    }

    func testOversizeFrameThrows() {
        var decoder = FrameDecoder()
        XCTAssertThrowsError(try decoder.append(Data([0xFF, 0xFF, 0xFF, 0xFF]))) { error in
            XCTAssertEqual(error as? FrameError, .frameTooLarge(Int(UInt32.max)))
        }
    }
}
