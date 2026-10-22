import Foundation
import XCTest
@testable import DroidsyncTransport

final class ClipboardTransferTests: XCTestCase {
    func testChunkerSplitsWithOffsets() {
        let data = Data(repeating: 7, count: ClipboardTransfer.chunkSize * 2 + 10)
        let chunks = ClipboardChunker.chunks(for: data)
        XCTAssertEqual(chunks.map(\.offset), [0, ClipboardTransfer.chunkSize, ClipboardTransfer.chunkSize * 2])
        XCTAssertEqual(chunks.map(\.data.count), [ClipboardTransfer.chunkSize, ClipboardTransfer.chunkSize, 10])
    }

    func testChunkerEmptyData() {
        XCTAssertTrue(ClipboardChunker.chunks(for: Data()).isEmpty)
    }

    func testAssemblerRoundTrip() {
        var assembler = ClipboardAssembler()
        let payload = Data(repeating: 3, count: 600_000)
        XCTAssertTrue(assembler.start(transferID: "t1", mimeType: "image/png", totalSize: UInt64(payload.count)))
        var completed = false
        for (offset, chunk) in ClipboardChunker.chunks(for: payload) {
            if assembler.append(transferID: "t1", offset: offset, data: chunk) == .completed {
                completed = true
            }
        }
        XCTAssertTrue(completed)
        XCTAssertEqual(assembler.end(transferID: "t1"), payload)
        XCTAssertNil(assembler.activeTransferID)
    }

    func testAssemblerRejectsBadStart() {
        var assembler = ClipboardAssembler()
        XCTAssertFalse(assembler.start(transferID: "t", mimeType: "text/plain", totalSize: 10))
        XCTAssertFalse(assembler.start(transferID: "t", mimeType: "image/png", totalSize: 0))
        XCTAssertFalse(assembler.start(
            transferID: "t",
            mimeType: "image/png",
            totalSize: UInt64(ClipboardTransfer.maxBlobSize + 1)
        ))
    }

    func testAssemblerRejectsOffsetMismatchAndCancels() {
        var assembler = ClipboardAssembler()
        XCTAssertTrue(assembler.start(transferID: "t", mimeType: "image/png", totalSize: 20))
        XCTAssertEqual(assembler.append(transferID: "t", offset: 5, data: Data(repeating: 1, count: 5)), .rejected)
        XCTAssertNil(assembler.activeTransferID)
    }

    func testNewStartReplacesActiveTransfer() {
        var assembler = ClipboardAssembler()
        XCTAssertTrue(assembler.start(transferID: "a", mimeType: "image/png", totalSize: 10))
        XCTAssertTrue(assembler.start(transferID: "b", mimeType: "image/jpeg", totalSize: 10))
        XCTAssertEqual(assembler.activeTransferID, "b")
        XCTAssertEqual(assembler.activeMimeType, "image/jpeg")
    }

    func testExpireCancelsStaleTransfer() {
        var assembler = ClipboardAssembler()
        let start = Date()
        XCTAssertTrue(assembler.start(transferID: "t", mimeType: "image/png", totalSize: 10, now: start))
        XCTAssertTrue(assembler.expireIfNeeded(now: start.addingTimeInterval(31)))
        XCTAssertNil(assembler.activeTransferID)
    }

    func testEndBeforeCompleteReturnsNil() {
        var assembler = ClipboardAssembler()
        XCTAssertTrue(assembler.start(transferID: "t", mimeType: "image/png", totalSize: 10))
        XCTAssertEqual(assembler.append(transferID: "t", offset: 0, data: Data(repeating: 1, count: 4)), .progress)
        XCTAssertNil(assembler.end(transferID: "t"))
    }
}
