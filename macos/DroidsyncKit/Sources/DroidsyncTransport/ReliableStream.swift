import Foundation
import DroidsyncProtocol

/// Sequence numbers, acknowledgements, the retransmit outbox and resume for protocol v2
/// (PROTOCOL-v2.md §4–5). Pure bookkeeping: it does no I/O, takes `now` from the caller
/// and is safe to call from any thread. One instance lives for the whole process so the
/// outbox survives reconnects and transport rebuilds.
public final class ReliableStream {
    public struct Counters: Equatable, Sendable {
        /// Reliable envelopes evicted unacknowledged because the outbox was full.
        public var evicted = 0
        /// Reliable envelopes evicted unacknowledged because they outlived the TTL.
        public var expired = 0
        /// Sequence numbers the peer skipped (only possible after its own evictions).
        public var gaps = 0
        /// Inbound reliable envelopes dropped as already processed.
        public var duplicates = 0
        /// Envelopes re-sent on resume.
        public var retransmits = 0

        public init() {}
    }

    public enum Disposition: Equatable {
        case process
        case duplicate
    }

    public static let maxEntries = 256
    public static let maxBytes = 4 * 1024 * 1024
    public static let ttl: TimeInterval = 120
    public static let standaloneAckDelay: TimeInterval = 1

    public let streamID: String

    private struct Entry {
        var seq: UInt64
        var envelope: DSEnvelope
        var size: Int
        let enqueuedAt: Date
        /// Written to some connection at least once (directly or as a Resume retransmit).
        /// Entries that never were form a suffix of the outbox: they're queued while no
        /// session is ready, and the next Resume sends them all.
        var transmitted: Bool
    }

    private let lock = NSLock()
    private let maxEntries: Int
    private let maxBytes: Int
    private let ttl: TimeInterval
    private var nextSeq: UInt64 = 0
    private var outbox: [Entry] = []
    private var outboxBytes = 0
    private var peerStreamID = ""
    private var lastProcessed: UInt64 = 0
    /// When a reliable envelope arrived whose ack hasn't gone out yet.
    private var ackPendingSince: Date?
    private var _counters = Counters()

    public init(
        streamID: String = UUID().uuidString,
        maxEntries: Int = ReliableStream.maxEntries,
        maxBytes: Int = ReliableStream.maxBytes,
        ttl: TimeInterval = ReliableStream.ttl
    ) {
        self.streamID = streamID
        self.maxEntries = maxEntries
        self.maxBytes = maxBytes
        self.ttl = ttl
    }

    public var counters: Counters { lock.withLock { _counters } }
    public var pendingCount: Int { lock.withLock { outbox.count } }
    public var lastProcessedSeq: UInt64 { lock.withLock { lastProcessed } }

    // MARK: Sending

    /// Stamps an outgoing envelope: a reliable one gets the next `seq` and is kept until
    /// acknowledged; every envelope carries the current `ack`. Pass `transmitted: false`
    /// when a reliable envelope is only queued (no session yet), so the owed ack stays owed.
    public func stamp(
        _ envelope: DSEnvelope,
        reliable: Bool,
        transmitted: Bool = true,
        now: Date = Date()
    ) -> DSEnvelope {
        lock.withLock {
            expireLocked(now: now)
            var stamped = envelope
            stamped.ack = lastProcessed
            if transmitted { ackPendingSince = nil }
            guard reliable else {
                stamped.seq = 0
                return stamped
            }
            nextSeq += 1
            stamped.seq = nextSeq
            let size = (try? stamped.serializedData().count) ?? 0
            outbox.append(Entry(seq: nextSeq, envelope: stamped, size: size, enqueuedAt: now, transmitted: transmitted))
            outboxBytes += size
            while outbox.count > maxEntries || (outboxBytes > maxBytes && outbox.count > 1) {
                outboxBytes -= outbox.removeFirst().size
                _counters.evicted += 1
            }
            return stamped
        }
    }

    /// Withdraws the first queued reliable envelope matching `match` that was never
    /// transmitted on any connection, as if it had never been sent; returns whether one was.
    /// The entries queued after it move down one `seq`, so the peer sees no gap. Once an
    /// envelope was transmitted it can't be withdrawn: the peer may have processed it.
    @discardableResult
    public func withdrawUnsent(where match: (DSEnvelope) -> Bool) -> Bool {
        lock.withLock {
            guard let index = outbox.firstIndex(where: { !$0.transmitted && match($0.envelope) }),
                  outbox[index...].allSatisfy({ !$0.transmitted }) else { return false }
            outboxBytes -= outbox.remove(at: index).size
            for later in index..<outbox.count {
                outbox[later].seq -= 1
                outbox[later].envelope.seq = outbox[later].seq
                outboxBytes -= outbox[later].size
                outbox[later].size = (try? outbox[later].envelope.serializedData().count) ?? 0
                outboxBytes += outbox[later].size
            }
            nextSeq -= 1
            return true
        }
    }

    /// A standalone acknowledgement (§4.1), or nil when no ack is owed yet.
    public func standaloneAckIfDue(now: Date = Date()) -> DSEnvelope? {
        lock.withLock {
            guard let since = ackPendingSince,
                  now.timeIntervalSince(since) >= Self.standaloneAckDelay else { return nil }
            ackPendingSince = nil
            var envelope = protocolEnvelope(.ackOnly(DSAck()))
            envelope.ack = lastProcessed
            return envelope
        }
    }

    // MARK: Receiving

    /// Applies the envelope's `ack` and decides whether to deliver it (§4.2). Pass
    /// `applyAck: false` for envelopes that arrive before the peer's `Resume` in a session:
    /// until then the peer's ack may refer to a previous stream of ours.
    public func receive(_ envelope: DSEnvelope, applyAck: Bool = true, now: Date = Date()) -> Disposition {
        lock.withLock {
            if applyAck { acknowledgeLocked(upTo: envelope.ack) }
            expireLocked(now: now)
            let seq = envelope.seq
            guard seq > 0 else { return .process }
            if ackPendingSince == nil { ackPendingSince = now }
            if seq <= lastProcessed {
                _counters.duplicates += 1
                return .duplicate
            }
            if seq > lastProcessed + 1 {
                _counters.gaps += Int(seq - lastProcessed - 1)
            }
            lastProcessed = seq
            return .process
        }
    }

    // MARK: Resume

    /// The `Resume` this side sends right after `AuthOk`.
    public func makeResume() -> DSEnvelope {
        lock.withLock {
            var resume = DSResume()
            resume.streamID = streamID
            resume.peerStreamID = peerStreamID
            resume.lastReceivedSeq = lastProcessed
            var envelope = protocolEnvelope(.resume(resume))
            envelope.ack = lastProcessed
            return envelope
        }
    }

    /// Applies the peer's `Resume` and returns the envelopes to retransmit, in order,
    /// with refreshed acks (§5).
    public func applyPeerResume(_ resume: DSResume, now: Date = Date()) -> [DSEnvelope] {
        lock.withLock {
            if resume.streamID != peerStreamID {
                // The peer restarted (or this is first contact): its seq numbers start over.
                peerStreamID = resume.streamID
                lastProcessed = 0
                ackPendingSince = nil
            }
            if resume.peerStreamID == streamID {
                acknowledgeLocked(upTo: resume.lastReceivedSeq)
            }
            // Otherwise the peer lost its receive state for our stream: resend everything.
            expireLocked(now: now)
            _counters.retransmits += outbox.count
            // The caller writes all of them now.
            for index in outbox.indices { outbox[index].transmitted = true }
            return outbox.map { entry in
                var envelope = entry.envelope
                envelope.ack = lastProcessed
                return envelope
            }
        }
    }

    // MARK: Private

    private func acknowledgeLocked(upTo ack: UInt64) {
        guard ack > 0 else { return }
        while let first = outbox.first, first.seq <= ack {
            outboxBytes -= first.size
            outbox.removeFirst()
        }
    }

    private func expireLocked(now: Date) {
        while let first = outbox.first, now.timeIntervalSince(first.enqueuedAt) > ttl {
            outboxBytes -= first.size
            outbox.removeFirst()
            _counters.expired += 1
        }
    }
}
