import CryptoKit
import XCTest
@testable import DroidsyncProtocol
@testable import DroidsyncTransport

final class FileNameSanitizerTests: XCTestCase {
    func testStripsSeparatorsAndTraversal() {
        XCTAssertEqual(FileNameSanitizer.sanitize("../x"), "x")
        XCTAssertEqual(FileNameSanitizer.sanitize("a/b"), "ab")
        XCTAssertEqual(FileNameSanitizer.sanitize("..\\..\\evil.sh"), "evil.sh")
        XCTAssertEqual(FileNameSanitizer.sanitize("C:report.pdf"), "Creport.pdf")
        XCTAssertEqual(FileNameSanitizer.sanitize("/etc/passwd"), "etcpasswd")
    }

    func testNormalizesToNFCBeforeCapping() {
        // Swift's == ignores normalization, so compare the UTF-8 bytes.
        XCTAssertEqual(Array(FileNameSanitizer.sanitize("cafe\u{301}.txt").utf8), Array("caf\u{E9}.txt".utf8))
        let capped = FileNameSanitizer.sanitize(String(repeating: "e\u{301}", count: 120) + ".md")
        XCTAssertEqual(Array(capped.utf8), Array((String(repeating: "\u{E9}", count: 98) + ".md").utf8))
    }

    func testDotsAndEmptyFallBack() {
        XCTAssertEqual(FileNameSanitizer.sanitize(""), "file")
        XCTAssertEqual(FileNameSanitizer.sanitize("."), "file")
        XCTAssertEqual(FileNameSanitizer.sanitize(".."), "file")
        XCTAssertEqual(FileNameSanitizer.sanitize("   "), "file")
        XCTAssertEqual(FileNameSanitizer.sanitize("///"), "file")
        XCTAssertEqual(FileNameSanitizer.sanitize(".bashrc"), "bashrc")
    }

    func testStripsControlAndBidiCharacters() {
        XCTAssertEqual(FileNameSanitizer.sanitize("a\u{0}b\nc\td\u{7F}.txt"), "abcd.txt")
        XCTAssertEqual(FileNameSanitizer.sanitize("photo\u{202E}gpj.exe"), "photogpj.exe")
        XCTAssertEqual(FileNameSanitizer.sanitize("line\u{2028}break"), "linebreak")
    }

    func testKeepsUnicode() {
        XCTAssertEqual(FileNameSanitizer.sanitize("Résumé 📄.pdf"), "Résumé 📄.pdf")
        XCTAssertEqual(FileNameSanitizer.sanitize("отчёт.docx"), "отчёт.docx")
    }

    func testLongNamesAreCappedKeepingTheExtension() {
        let long = String(repeating: "a", count: 300) + ".jpeg"
        let result = FileNameSanitizer.sanitize(long)
        XCTAssertLessThanOrEqual(result.utf8.count, 200)
        XCTAssertTrue(result.hasSuffix(".jpeg"))
        let emoji = String(repeating: "😀", count: 100)
        let capped = FileNameSanitizer.sanitize(emoji)
        XCTAssertLessThanOrEqual(capped.utf8.count, 200)
        XCTAssertEqual(capped.count, 50, "cut on character boundaries")
    }

    func testUniqueURLAddsSuffixes() throws {
        let dir = try temporaryDirectory()
        XCTAssertEqual(FileNameSanitizer.uniqueURL(for: "a.txt", in: dir).lastPathComponent, "a.txt")
        FileManager.default.createFile(atPath: dir.appendingPathComponent("a.txt").path, contents: Data())
        XCTAssertEqual(FileNameSanitizer.uniqueURL(for: "a.txt", in: dir).lastPathComponent, "a (1).txt")
        FileManager.default.createFile(atPath: dir.appendingPathComponent("a (1).txt").path, contents: Data())
        XCTAssertEqual(FileNameSanitizer.uniqueURL(for: "a.txt", in: dir).lastPathComponent, "a (2).txt")
        FileManager.default.createFile(atPath: dir.appendingPathComponent("noext").path, contents: Data())
        XCTAssertEqual(FileNameSanitizer.uniqueURL(for: "noext", in: dir).lastPathComponent, "noext (1)")
    }
}

/// Two engines wired back to back, with a switch to cut the "connection".
private final class Link {
    var now = Date(timeIntervalSince1970: 1_000)
    var connected = true
    /// Messages from A to B and B to A, delivered by `pump()`.
    var toB: [DSEnvelope.OneOf_Payload] = []
    var toA: [DSEnvelope.OneOf_Payload] = []
    var log: [(from: String, payload: DSEnvelope.OneOf_Payload)] = []
    var maxInFlight: UInt64 = 0
    lazy var a: FileTransferEngine = makeEngine("a") { [unowned self] in self.queue($0, from: "a") }
    lazy var b: FileTransferEngine = makeEngine("b") { [unowned self] in self.queue($0, from: "b") }
    var snapshots: [String: FileTransferSnapshot] = [:]
    var outcomes: [String: [FileTransferOutcome]] = ["a": [], "b": []]
    let root: URL
    /// Drop chunk messages instead of delivering them (simulates frames lost on a dying link).
    var dropChunks = false
    private var highestSent: [String: UInt64] = [:]
    private var acked: [String: UInt64] = [:]

    init() throws {
        root = try temporaryDirectory()
    }

    func dir(_ name: String, _ kind: String) -> URL { root.appendingPathComponent(name).appendingPathComponent(kind) }

    private func makeEngine(_ name: String, send: @escaping FileTransferEngine.Send) -> FileTransferEngine {
        var config = FileTransferEngine.Configuration(
            downloadsDirectory: dir(name, "Downloads"),
            partialDirectory: dir(name, "partial")
        )
        config.chunkSize = 4096
        config.window = 64 * 1024
        config.progressInterval = 16 * 1024
        let engine = FileTransferEngine(configuration: config, clock: { [unowned self] in self.now }, send: send)
        engine.onChange = { [unowned self] snapshot in self.snapshots[snapshot.id] = snapshot }
        engine.onOutcome = { [unowned self] outcome in self.outcomes[name, default: []].append(outcome) }
        return engine
    }

    private func queue(_ payload: DSEnvelope.OneOf_Payload, from: String) {
        log.append((from, payload))
        if case .fileChunk(let chunk) = payload {
            let end = chunk.offset + UInt64(chunk.data.count)
            highestSent[chunk.transferID] = max(highestSent[chunk.transferID] ?? 0, end)
            let inFlight = (highestSent[chunk.transferID] ?? 0) - (acked[chunk.transferID] ?? 0)
            maxInFlight = max(maxInFlight, inFlight)
            if dropChunks { return }
        }
        if case .fileAccept(let accept) = payload {
            acked[accept.transferID] = accept.resumeOffset
            highestSent[accept.transferID] = accept.resumeOffset
        }
        if case .fileProgress(let progress) = payload {
            acked[progress.transferID] = progress.received
        }
        guard connected else { return }
        if from == "a" { toB.append(payload) } else { toA.append(payload) }
    }

    func connect() {
        connected = true
        a.sessionStarted()
        b.sessionStarted()
        pump()
    }

    func disconnect() {
        connected = false
        toA.removeAll()
        toB.removeAll()
        a.sessionEnded()
        b.sessionEnded()
    }

    func pump(limit: Int = 100_000) {
        var steps = 0
        while (!toA.isEmpty || !toB.isEmpty) && steps < limit {
            if !toB.isEmpty { b.handle(toB.removeFirst()) }
            if !toA.isEmpty { a.handle(toA.removeFirst()) }
            steps += 1
        }
    }

    func sent(from: String) -> [DSEnvelope.OneOf_Payload] { log.filter { $0.from == from }.map(\.payload) }
}

final class FileTransferEngineTests: XCTestCase {
    private func makeFile(_ link: Link, name: String = "photo.jpg", size: Int) throws -> (URL, Data) {
        var bytes = [UInt8](repeating: 0, count: size)
        for i in 0..<size { bytes[i] = UInt8(truncatingIfNeeded: i &* 31 &+ i >> 8) }
        let data = Data(bytes)
        let src = link.root.appendingPathComponent("src")
        try FileManager.default.createDirectory(at: src, withIntermediateDirectories: true)
        let url = src.appendingPathComponent(name)
        try data.write(to: url)
        return (url, data)
    }

    func testRoundTripSavesVerifiedFileAndKeepsWindow() throws {
        let link = try Link()
        link.connect()
        let (url, data) = try makeFile(link, size: 300_000)
        let id = try link.a.enqueue(fileAt: url)
        link.pump()
        let saved = link.dir("b", "Downloads").appendingPathComponent("photo.jpg")
        XCTAssertEqual(try Data(contentsOf: saved), data)
        XCTAssertEqual(link.snapshots[id]?.phase, .completed)
        XCTAssertEqual(link.snapshots[id]?.savedName, "photo.jpg")
        XCTAssertEqual(link.outcomes["a"], [.sent])
        XCTAssertEqual(link.outcomes["b"], [.received])
        XCTAssertLessThanOrEqual(link.maxInFlight, 64 * 1024, "window never exceeded")
        XCTAssertTrue(link.sent(from: "b").contains { if case .fileProgress = $0 { return true }; return false })
        // No partial left behind.
        let partials = try FileManager.default.contentsOfDirectory(atPath: link.dir("b", "partial").path)
        XCTAssertEqual(partials, [])
    }

    func testEmptyFileTransfers() throws {
        let link = try Link()
        link.connect()
        let (url, _) = try makeFile(link, name: "empty.txt", size: 0)
        let id = try link.a.enqueue(fileAt: url)
        link.pump()
        XCTAssertEqual(link.snapshots[id]?.phase, .completed)
        let saved = link.dir("b", "Downloads").appendingPathComponent("empty.txt")
        XCTAssertEqual(try Data(contentsOf: saved), Data())
    }

    func testOneTransferAtATimeInQueueOrder() throws {
        let link = try Link()
        link.connect()
        let (first, _) = try makeFile(link, name: "one.bin", size: 50_000)
        let (second, _) = try makeFile(link, name: "two.bin", size: 50_000)
        try link.a.enqueue(fileAt: first)
        try link.a.enqueue(fileAt: second)
        let offers = link.sent(from: "a").compactMap { payload -> String? in
            if case .fileOffer(let offer) = payload { return offer.name }
            return nil
        }
        XCTAssertEqual(offers, ["one.bin"], "second waits for the first")
        link.pump()
        let allOffers = link.sent(from: "a").compactMap { payload -> String? in
            if case .fileOffer(let offer) = payload { return offer.name }
            return nil
        }
        XCTAssertEqual(allOffers, ["one.bin", "two.bin"])
        XCTAssertEqual(link.outcomes["b"], [.received, .received])
    }

    func testCollidingNamesGetSuffixes() throws {
        let link = try Link()
        link.connect()
        let (url, _) = try makeFile(link, name: "same.txt", size: 10)
        try link.a.enqueue(fileAt: url)
        try link.a.enqueue(fileAt: url)
        link.pump()
        let names = try FileManager.default.contentsOfDirectory(atPath: link.dir("b", "Downloads").path).sorted()
        XCTAssertEqual(names, ["same (1).txt", "same.txt"])
    }

    func testResumesAfterDisconnectFromReceivedOffset() throws {
        let link = try Link()
        link.connect()
        let (url, data) = try makeFile(link, size: 200_000)
        let id = try link.a.enqueue(fileAt: url)
        // Deliver part of the transfer, then cut the link.
        link.pump(limit: 6)
        link.disconnect()
        XCTAssertEqual(link.snapshots[id]?.phase, .waitingForReconnect)
        let receivedBefore = link.log.filter { $0.from == "b" }.compactMap { payload -> UInt64? in
            if case .fileProgress(let progress) = payload.payload { return progress.received }
            return nil
        }.max() ?? 0
        XCTAssertGreaterThan(receivedBefore, 0)
        link.connect()
        let accepts = link.sent(from: "b").compactMap { payload -> UInt64? in
            if case .fileAccept(let accept) = payload { return accept.resumeOffset }
            return nil
        }
        XCTAssertEqual(accepts.count, 2)
        XCTAssertGreaterThan(accepts[1], 0, "resumed, not restarted")
        let saved = link.dir("b", "Downloads").appendingPathComponent("photo.jpg")
        XCTAssertEqual(try Data(contentsOf: saved), data)
        XCTAssertEqual(link.snapshots[id]?.phase, .completed)
        XCTAssertTrue(link.outcomes["a"]!.contains(.resumed))
        XCTAssertTrue(link.outcomes["b"]!.contains(.resumed))
    }

    func testDuplicateOfferWhileSendingIsTreatedAsResumeAndIgnoredBySender() throws {
        let link = try Link()
        link.connect()
        let (url, data) = try makeFile(link, size: 100_000)
        let id = try link.a.enqueue(fileAt: url)
        link.pump(limit: 3)
        // A retransmitted copy of the original offer arrives mid-transfer.
        var offer = DSFileOffer()
        offer.transferID = id
        offer.name = "photo.jpg"
        offer.size = 100_000
        link.b.handle(.fileOffer(offer))
        link.pump()
        let saved = link.dir("b", "Downloads").appendingPathComponent("photo.jpg")
        XCTAssertEqual(try Data(contentsOf: saved), data)
        XCTAssertEqual(link.outcomes["b"], [.received])
    }

    func testEarlyEndIsIgnoredUntilReOffer() throws {
        let link = try Link()
        link.connect()
        let (url, data) = try makeFile(link, size: 50_000)
        let id = try link.a.enqueue(fileAt: url)
        // Chunks are lost on the dying link but the reliable End is retransmitted.
        link.dropChunks = true
        link.pump()
        link.dropChunks = false
        XCTAssertNotEqual(link.snapshots[id]?.phase, .completed)
        link.disconnect()
        link.connect()
        let saved = link.dir("b", "Downloads").appendingPathComponent("photo.jpg")
        XCTAssertEqual(try Data(contentsOf: saved), data)
    }

    func testChecksumMismatchDeletesPartial() throws {
        let link = try Link()
        link.connect()
        var offer = DSFileOffer()
        offer.transferID = "t-1"
        offer.name = "x.bin"
        offer.size = 4
        link.b.handle(.fileOffer(offer))
        var chunk = DSFileChunk()
        chunk.transferID = "t-1"
        chunk.data = Data([1, 2, 3, 4])
        link.b.handle(.fileChunk(chunk))
        var end = DSFileEnd()
        end.transferID = "t-1"
        end.sha256 = Data(repeating: 0, count: 32)
        link.b.handle(.fileEnd(end))
        let complete = link.sent(from: "b").compactMap { payload -> DSFileComplete? in
            if case .fileComplete(let c) = payload { return c }
            return nil
        }.last
        XCTAssertEqual(complete?.ok, false)
        XCTAssertEqual(complete?.error, "checksum_mismatch")
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: link.dir("b", "partial").path), [])
        XCTAssertFalse(FileManager.default.fileExists(atPath: link.dir("b", "Downloads").appendingPathComponent("x.bin").path))
        // A retransmitted End gets the same answer.
        link.b.handle(.fileEnd(end))
        let completes = link.sent(from: "b").filter { if case .fileComplete = $0 { return true }; return false }
        XCTAssertEqual(completes.count, 2)
    }

    func testRejectsInvalidTooLargeDisabledAndFullDisk() throws {
        let link = try Link()
        func reject(_ offer: DSFileOffer) -> String? {
            link.b.handle(.fileOffer(offer))
            if case .fileReject(let r)? = link.sent(from: "b").last { return r.reason }
            return nil
        }
        var offer = DSFileOffer()
        offer.transferID = "../etc"
        offer.size = 1
        XCTAssertEqual(reject(offer), "invalid")
        offer.transferID = "big"
        offer.size = FileTransferLimits.maxFileSize + 1
        XCTAssertEqual(reject(offer), "too_large")
        offer.transferID = "full"
        offer.size = 1000
        link.b.availableCapacity = { _ in 10 }
        XCTAssertEqual(reject(offer), "disk_full")
        link.b.availableCapacity = { _ in nil }
        link.b.isReceivingEnabled = { false }
        offer.transferID = "off"
        XCTAssertEqual(reject(offer), "disabled")
    }

    func testRejectedOfferFailsSenderAndStartsNext() throws {
        let link = try Link()
        link.b.isReceivingEnabled = { false }
        link.connect()
        let (url, _) = try makeFile(link, size: 10)
        let id = try link.a.enqueue(fileAt: url)
        link.pump()
        XCTAssertEqual(link.snapshots[id]?.phase, .failed("disabled"))
    }

    func testWriteFailureReportsDiskFullOrWriteFailed() throws {
        final class FailingSink: FilePartialSink {
            let error: Error
            init(_ error: Error) { self.error = error }
            func write(_ data: Data) throws { throw error }
            func close() {}
        }
        for (error, expectCancel) in [(POSIXError(.ENOSPC) as Error, true), (POSIXError(.EIO) as Error, false)] {
            let link = try Link()
            link.b.makeSink = { _, _ in FailingSink(error) }
            link.connect()
            let (url, _) = try makeFile(link, size: 10_000)
            let id = try link.a.enqueue(fileAt: url)
            link.pump()
            if expectCancel {
                XCTAssertEqual(link.snapshots[id]?.phase, .cancelled("disk_full"))
            } else {
                XCTAssertEqual(link.snapshots[id]?.phase, .failed("write_failed"))
            }
            XCTAssertEqual(link.outcomes["b"], [.failed])
        }
    }

    func testSenderCancelRemovesReceiverPartial() throws {
        let link = try Link()
        link.connect()
        let (url, _) = try makeFile(link, size: 200_000)
        let id = try link.a.enqueue(fileAt: url)
        link.pump(limit: 4)
        link.a.cancel(id: id)
        link.pump()
        XCTAssertEqual(link.snapshots[id]?.phase, .cancelled("user"))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: link.dir("b", "partial").path), [])
        XCTAssertFalse(FileManager.default.fileExists(atPath: link.dir("b", "Downloads").appendingPathComponent("photo.jpg").path))
    }

    func testReceiverCancelStopsSender() throws {
        let link = try Link()
        link.connect()
        let (url, _) = try makeFile(link, size: 200_000)
        let id = try link.a.enqueue(fileAt: url)
        link.pump(limit: 4)
        link.b.cancel(id: id)
        link.pump()
        XCTAssertEqual(link.outcomes["a"], [.failed])
        let chunksAfterCancel = link.log.drop { entry in
            if case .fileCancel = entry.payload { return false }
            return true
        }.filter { if case .fileChunk = $0.payload { return true }; return false }
        XCTAssertTrue(chunksAfterCancel.count <= 64 * 1024 / 4096, "only chunks already in flight")
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: link.dir("b", "partial").path), [])
    }

    func testCancellingQueuedTransferSendsNothing() throws {
        let link = try Link()
        let (url, _) = try makeFile(link, size: 10)
        let id = try link.a.enqueue(fileAt: url)
        link.a.cancel(id: id)
        link.connect()
        XCTAssertTrue(link.sent(from: "a").isEmpty)
        XCTAssertEqual(link.snapshots[id]?.phase, .cancelled("user"))
    }

    func testQueuedFilesFailWhenThePhoneLacksFiles() throws {
        let link = try Link()
        let (url, _) = try makeFile(link, size: 10)
        let queued = try link.a.enqueue(fileAt: url)
        link.a.sessionStartedWithoutFiles()
        XCTAssertEqual(link.snapshots[queued]?.phase, .failed("unsupported"))
        // Queued while connected to that phone: fails at once.
        let later = try link.a.enqueue(fileAt: url)
        XCTAssertEqual(link.snapshots[later]?.phase, .failed("unsupported"))
        XCTAssertTrue(link.sent(from: "a").isEmpty)
        XCTAssertEqual(link.outcomes["a"], [.failed, .failed])
        // A later session that has files sends normally again.
        link.a.sessionEnded()
        link.connect()
        let next = try link.a.enqueue(fileAt: url)
        link.pump()
        XCTAssertEqual(link.snapshots[next]?.phase, .completed)
    }

    func testStallParksUntilNextSession() throws {
        let link = try Link()
        link.connect()
        let (url, data) = try makeFile(link, size: 300_000)
        let id = try link.a.enqueue(fileAt: url)
        // The receiver goes silent: nothing it sends reaches the sender.
        link.pump(limit: 2)
        link.toA.removeAll()
        link.connected = false
        link.now.addTimeInterval(31)
        link.a.tick()
        XCTAssertEqual(link.snapshots[id]?.phase, .waitingForReconnect)
        link.disconnect()
        link.connect()
        XCTAssertEqual(try Data(contentsOf: link.dir("b", "Downloads").appendingPathComponent("photo.jpg")), data)
    }

    func testPartialsExpireAfterRetention() throws {
        let link = try Link()
        link.connect()
        let (url, _) = try makeFile(link, size: 200_000)
        try link.a.enqueue(fileAt: url)
        link.pump(limit: 4)
        link.disconnect()
        link.now.addTimeInterval(601)
        link.b.tick()
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: link.dir("b", "partial").path), [])
        XCTAssertEqual(link.outcomes["b"], [.failed])
        // The re-offer starts over from zero.
        link.connect()
        let accepts = link.sent(from: "b").compactMap { payload -> UInt64? in
            if case .fileAccept(let accept) = payload { return accept.resumeOffset }
            return nil
        }
        XCTAssertEqual(accepts.last, 0)
        XCTAssertTrue(link.outcomes["b"]!.contains(.received))
    }

    func testOutOfOrderChunkCancels() throws {
        let link = try Link()
        var offer = DSFileOffer()
        offer.transferID = "t-2"
        offer.size = 100
        link.b.handle(.fileOffer(offer))
        var chunk = DSFileChunk()
        chunk.transferID = "t-2"
        chunk.offset = 50
        chunk.data = Data(repeating: 1, count: 10)
        link.b.handle(.fileChunk(chunk))
        if case .fileCancel(let cancel)? = link.sent(from: "b").last {
            XCTAssertEqual(cancel.reason, "error")
        } else {
            XCTFail("expected a cancel")
        }
    }

    func testEnqueueRejectsDirectoriesAndOversizedFiles() throws {
        let link = try Link()
        XCTAssertThrowsError(try link.a.enqueue(fileAt: link.root)) { error in
            XCTAssertEqual(error as? FileTransferError, .notARegularFile)
        }
    }

    func testSanitizedNameIsUsedForTheSavedFile() throws {
        let link = try Link()
        link.connect()
        var offer = DSFileOffer()
        offer.transferID = "t-3"
        offer.name = "../../.ssh/authorized_keys"
        offer.size = 3
        link.b.handle(.fileOffer(offer))
        var chunk = DSFileChunk()
        chunk.transferID = "t-3"
        chunk.data = Data([1, 2, 3])
        link.b.handle(.fileChunk(chunk))
        var end = DSFileEnd()
        end.transferID = "t-3"
        end.sha256 = Data(SHA256.hash(data: Data([1, 2, 3])))
        link.b.handle(.fileEnd(end))
        let names = try FileManager.default.contentsOfDirectory(atPath: link.dir("b", "Downloads").path)
        XCTAssertEqual(names, ["sshauthorized_keys"])
    }
}

final class FolderSendingTests: XCTestCase {
    private func makeFolder(in root: URL) throws -> URL {
        let folder = root.appendingPathComponent("Trip Photos")
        let nested = folder.appendingPathComponent("day 1/raw")
        try FileManager.default.createDirectory(at: nested, withIntermediateDirectories: true)
        try Data("top".utf8).write(to: folder.appendingPathComponent("notes.txt"))
        try Data(repeating: 7, count: 100_000).write(to: nested.appendingPathComponent("img.dng"))
        return folder
    }

    private func relativeFiles(under root: URL) -> [String: Data] {
        var files: [String: Data] = [:]
        let base = root.standardizedFileURL.resolvingSymlinksInPath().path + "/"
        let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey])
        while let url = enumerator?.nextObject() as? URL {
            guard (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true else { continue }
            let path = url.standardizedFileURL.resolvingSymlinksInPath().path
            files[String(path.dropFirst(base.count))] = try? Data(contentsOf: url)
        }
        return files
    }

    func testZipsTheFolderWithItsNameAndRelativeStructure() throws {
        let root = try temporaryDirectory()
        let folder = try makeFolder(in: root)
        XCTAssertTrue(FolderArchive.isFolder(folder))
        XCTAssertFalse(FolderArchive.isFolder(folder.appendingPathComponent("notes.txt")))
        let temp = root.appendingPathComponent("tmp")
        let archive = try FolderArchive.zip(folder: folder, temporaryDirectory: temp)
        XCTAssertEqual(archive.lastPathComponent, "Trip Photos.zip")
        let out = root.appendingPathComponent("out")
        let unzip = Process()
        unzip.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        unzip.arguments = ["-x", "-k", archive.path, out.path]
        try unzip.run()
        unzip.waitUntilExit()
        XCTAssertEqual(unzip.terminationStatus, 0)
        XCTAssertEqual(relativeFiles(under: out), [
            "Trip Photos/notes.txt": Data("top".utf8),
            "Trip Photos/day 1/raw/img.dng": Data(repeating: 7, count: 100_000),
        ])
        // Only the archive in its own directory: no ditto log left behind.
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: archive.deletingLastPathComponent().path), ["Trip Photos.zip"])
    }

    func testTooLargeOrCancelledLeavesNothingBehind() throws {
        let root = try temporaryDirectory()
        let folder = try makeFolder(in: root)
        let temp = root.appendingPathComponent("tmp")
        XCTAssertThrowsError(try FolderArchive.zip(folder: folder, temporaryDirectory: temp, maxSize: 100)) { error in
            XCTAssertEqual(error as? FileTransferError, .tooLarge)
        }
        XCTAssertThrowsError(try FolderArchive.zip(folder: folder, temporaryDirectory: temp, isCancelled: { true })) { error in
            XCTAssertEqual(error as? FileTransferError, .cancelled)
        }
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: temp.path), [])
    }

    func testTheArchiveIsDeletedWhenItsTransferFinishes() throws {
        let link = try Link()
        let folder = try makeFolder(in: link.root)
        let temp = link.root.appendingPathComponent("tmp")
        let sent = try FolderArchive.zip(folder: folder, temporaryDirectory: temp)
        let cancelled = try FolderArchive.zip(folder: folder, temporaryDirectory: temp)
        let sentID = try link.a.enqueue(fileAt: sent, name: "Trip Photos.zip", id: "folder-1", removingWhenFinished: sent.deletingLastPathComponent())
        XCTAssertEqual(sentID, "folder-1")
        let cancelledID = try link.a.enqueue(fileAt: cancelled, id: "folder-2", removingWhenFinished: cancelled.deletingLastPathComponent())
        link.a.cancel(id: cancelledID)
        XCTAssertFalse(FileManager.default.fileExists(atPath: cancelled.deletingLastPathComponent().path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: sent.path), "kept until its transfer finishes")
        link.connect()
        XCTAssertEqual(link.snapshots[sentID]?.phase, .completed)
        XCTAssertFalse(FileManager.default.fileExists(atPath: sent.deletingLastPathComponent().path))
        XCTAssertNotNil(FileManager.default.contents(atPath: link.dir("b", "Downloads").appendingPathComponent("Trip Photos.zip").path))
    }
}

private func temporaryDirectory() throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("ft-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}
