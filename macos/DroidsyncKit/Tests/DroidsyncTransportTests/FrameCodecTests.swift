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
