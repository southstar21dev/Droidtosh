import Foundation
import NIOCore
import NIOPosix
import NIOSSL
import DroidsyncProtocol

/// A phone session that passed the PSK check and the v2 negotiation.
public struct AuthenticatedSession: Equatable {
    public let psk: Data
    public let negotiation: Negotiation
    public let path: SessionPath
}

/// The Mac's phone transport. On the relay path (PROTOCOL-v2.md §1.1–1.3): outer TLS to the
/// relay pinned to its certificate fingerprint, the `DROIDSYNC/2` join line, then — once the
/// relay sends `PAIRED` — the inner TLS server, PSK auth, negotiation and the reliable stream,
/// all tunnelled inside the outer TLS. On the local path (§1.4) a LAN listener runs the same
/// inner TLS and session directly. Either path or both can be enabled; one phone session is
/// active at a time and the newest authenticated one wins.
public final class RelayTransport {
    public enum State: Equatable {
        case stopped
        case connecting
        case waitingForPhone
        case connected
        case failed(String)
        /// The relay presented a certificate other than the pinned one (the observed
        /// fingerprint). Automatic retries stop until the transport is rebuilt.
        case relayCertificateMismatch(String)
        /// The relay rejected the join line as too old. Automatic retries stop.
        case relayRequiresUpgrade
    }

    /// The local listener's state (§1.4).
    public enum LocalState: Equatable {
        case off
        case listening(port: Int)
        case failed(String)
    }

    /// How long the Mac waits for the phone's `Resume` before dropping the session.
    static let resumeTimeout: TimeInterval = 10

    public var onStateChange: ((State) -> Void)?
    public var onClientAuthenticated: ((AuthenticatedSession) -> Void)?
    public var onClientDisconnected: (() -> Void)?
    public var onEnvelope: ((DSEnvelope) -> Void)?
    public var onDebugLog: ((String) -> Void)?
    /// Called on the main queue with the relay's fingerprint when nothing was pinned yet
    /// (trust on first use); the caller should persist it.
    public var onRelayFingerprintLearned: ((String) -> Void)?
    /// Called on the main queue when the local listener's state changes.
    public var onLocalStateChange: ((LocalState) -> Void)?

    private let relay: RelayEndpoint?
    private let local: LocalListenerOptions?
    private let identity: TLSIdentity
    private let pskProvider: () -> [Data]
    private let reliableStream: ReliableStream
    private let diagnostics: DiagnosticsRecorder

    private let queue = DispatchQueue(label: "dev.southstar21.droidtosh.transport.relay")
    private var _state: State = .stopped
    /// The relay path's own state; `_state` is derived from it, the local listener and the session.
    private var relayState: State = .stopped
    private var _localState: LocalState = .off
    private var started = false
    private var suspended = false
    private var stopped = false
    /// Set by a certificate mismatch or `ERROR upgrade`; only a new transport clears it.
    private var halted = false
    private var generation: UInt64 = 0
    private var reconnectAttempt = 0
    private var reconnectScheduled = false
    private var attemptConcluded = false
    private var group: MultiThreadedEventLoopGroup?
    private var channel: Channel?
    private var sslContext: NIOSSLContext?
    private var relayClientContext: NIOSSLContext?
    private var pinnedRelayFingerprint: String?
    private var authenticatedChannel: Channel?
    private var authenticatedPath: SessionPath?
    private var negotiation: Negotiation?
    private var localServerChannel: Channel?
    /// Bumped whenever the listener opens or closes, like `generation` for the relay.
    private var localGeneration: UInt64 = 0
    /// Accepted local connections, authenticated or not.
    private var localChannels: [ObjectIdentifier: Channel] = [:]
    private var localRetryScheduled = false
    private let advertiser = LocalServiceAdvertiser()
    /// True between authentication and the phone's `Resume`; feature traffic waits.
    private var awaitingPeerResume = false
    private var postPairedLeftover: ByteBuffer?
    private var lastPostPairedLeftover: Data?
    private weak var _lastSessionForTesting: RelaySessionHandler?

    /// A relay-only transport.
    public convenience init(
        host: String,
        port: Int,
        room: String,
        identity: TLSIdentity,
        pskProvider: @escaping () -> [Data],
        relayFingerprint: String? = nil,
        reliableStream: ReliableStream = ReliableStream(),
        diagnostics: DiagnosticsRecorder = DiagnosticsRecorder(),
        onStateChange: @escaping (State) -> Void,
        onClientAuthenticated: @escaping (AuthenticatedSession) -> Void,
        onClientDisconnected: @escaping () -> Void,
        onEnvelope: @escaping (DSEnvelope) -> Void
    ) {
        self.init(
            relay: RelayEndpoint(host: host, port: UInt16(clamping: port), room: room),
            local: nil,
            identity: identity,
            pskProvider: pskProvider,
            relayFingerprint: relayFingerprint,
            reliableStream: reliableStream,
            diagnostics: diagnostics,
            onStateChange: onStateChange,
            onClientAuthenticated: onClientAuthenticated,
            onClientDisconnected: onClientDisconnected,
            onEnvelope: onEnvelope
        )
    }

    /// `relay` nil skips the relay; `local` nil skips the local listener.
    public init(
        relay: RelayEndpoint?,
        local: LocalListenerOptions?,
        identity: TLSIdentity,
        pskProvider: @escaping () -> [Data],
        relayFingerprint: String? = nil,
        reliableStream: ReliableStream = ReliableStream(),
        diagnostics: DiagnosticsRecorder = DiagnosticsRecorder(),
        onStateChange: @escaping (State) -> Void,
        onClientAuthenticated: @escaping (AuthenticatedSession) -> Void,
        onClientDisconnected: @escaping () -> Void,
        onEnvelope: @escaping (DSEnvelope) -> Void
    ) {
        self.relay = relay
        self.local = local
        self.identity = identity
        self.pskProvider = pskProvider
        self.pinnedRelayFingerprint = relayFingerprint
        self.reliableStream = reliableStream
        self.diagnostics = diagnostics
        self.onStateChange = onStateChange
        self.onClientAuthenticated = onClientAuthenticated
        self.onClientDisconnected = onClientDisconnected
        self.onEnvelope = onEnvelope
    }

    public var state: State { queue.sync { _state } }

    /// Delivery counters of the process-wide reliable stream, for diagnostics.
    public var reliabilityCounters: ReliableStream.Counters { reliableStream.counters }

    /// The negotiated session while a phone is connected.
    public var currentNegotiation: Negotiation? { queue.sync { authenticatedChannel == nil ? nil : negotiation } }

    /// The path of the session while a phone is connected.
    public var currentSessionPath: SessionPath? { queue.sync { sessionReportedLocked ? authenticatedPath : nil } }

    public var localState: LocalState { queue.sync { _localState } }

    /// Test-only record of the bytes received after the `PAIRED` line on the most
    /// recent pairing. The live copy is replayed into the session pipeline and
    /// cleared; this record survives so tests can assert nothing was dropped.
    var postPairedLeftoverForTesting: Data? {
        queue.sync { lastPostPairedLeftover }
    }

    /// Test-only weak reference to the most recently installed session. A reconnect
    /// must release the previous channel, pipeline and session, so tests assert this
    /// becomes nil after a disconnect/reconnect cycle.
    var lastSessionForTesting: RelaySessionHandler? {
        queue.sync { _lastSessionForTesting }
    }

    public func start() {
        queue.sync { startLocked() }
    }

    public func stop() {
        queue.sync { stopLocked() }
    }

    public func suspend() {
        queue.sync { suspendLocked() }
    }

    public func resume() {
        queue.sync { resumeLocked() }
    }

    /// Drops the relay connection and dials again at once, unless a phone session is
    /// up. Call it when the network path changes: a socket that died with the old
    /// path is otherwise only noticed by keepalive, or by the session idle timeout.
    public func reconnectIfIdle() {
        queue.sync { reconnectIfIdleLocked() }
    }

    /// Sends an envelope to the phone. Payloads the session didn't negotiate are dropped.
    /// Reliable payloads (§4.3) get a sequence number and stay queued until acknowledged —
    /// including while no phone is connected, so they go out on the next `Resume`.
    public func send(_ envelope: DSEnvelope) {
        queue.sync { sendLocked(envelope) }
    }

    /// Withdraws a reliable envelope that is still only queued, never written to any
    /// connection (see `ReliableStream.withdrawUnsent`); returns whether one was.
    @discardableResult
    public func withdrawUnsent(where match: (DSEnvelope) -> Bool) -> Bool {
        queue.sync { reliableStream.withdrawUnsent(where: match) }
    }

    private func sendLocked(_ envelope: DSEnvelope) {
        guard let payload = envelope.payload else { return }
        if let negotiation,
           let capability = ProtocolV2.requiredCapability(for: payload),
           !negotiation.supports(capability) {
            onDebugLog?("dropping \(capability) payload: not negotiated")
            return
        }
        let reliableSession = negotiation?.supports(ProtocolV2.Capability.reliable) ?? true
        let reliable = reliableSession && ProtocolV2.isReliableFromMac(payload)
        guard let channel = authenticatedChannel, isLiveLocked(channel), !awaitingPeerResume else {
            if reliable {
                _ = reliableStream.stamp(envelope, reliable: true, transmitted: false)
            }
            return
        }
        let stamped = negotiation?.supports(ProtocolV2.Capability.reliable) == true
            ? reliableStream.stamp(envelope, reliable: reliable)
            : envelope
        writeLocked(stamped, to: channel)
    }

    private func writeLocked(_ envelope: DSEnvelope, to channel: Channel) {
        guard let data = try? envelope.serializedData() else { return }
        var buffer = channel.allocator.buffer(capacity: data.count + 4)
        buffer.writeBytes(FrameCodec.encode(data))
        channel.writeAndFlush(buffer, promise: nil)
        diagnostics.recordOutbound(envelope.payload, bytes: data.count + 4)
    }

    private func startLocked() {
        guard !started, !stopped else { return }
        started = true
        suspended = false
        reconnectAttempt = 0
        connectLocked()
        openListenerLocked()
        refreshStateLocked()
    }

    private func stopLocked() {
        guard !stopped else { return }
        stopped = true
        started = false
        suspended = false
        generation &+= 1
        reconnectScheduled = false
        attemptConcluded = true
        channel?.close(promise: nil)
        channel = nil
        closeListenerLocked()
        if let group {
            self.group = nil
            group.shutdownGracefully { _ in }
        }
        setStateLocked(.stopped)
    }

    private func suspendLocked() {
        guard started, !stopped else { return }
        suspended = true
        generation &+= 1
        reconnectScheduled = false
        attemptConcluded = true
        channel?.close(promise: nil)
        channel = nil
        closeListenerLocked()
        if !halted { setRelayStateLocked(.stopped) }
        refreshStateLocked()
    }

    private func resumeLocked() {
        guard started, suspended, !stopped else { return }
        suspended = false
        reconnectAttempt = 0
        connectLocked()
        openListenerLocked()
        refreshStateLocked()
    }

    private func connectLocked() {
        guard let relay, started, !suspended, !stopped, !halted else { return }
        generation &+= 1
        let gen = generation
        reconnectScheduled = false
        attemptConcluded = false
        postPairedLeftover = nil
        if group == nil {
            group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
        }
        guard let group else { return }
        guard let relayContext = relayClientContextLocked() else {
            concludeAttemptLocked(with: "relay TLS unavailable")
            return
        }
        diagnostics.recordConnectAttempt()
        setRelayStateLocked(.connecting)

        let joinLine = RelayJoinLine(room: relay.room, role: .mac).formatted()
        let pinned = pinnedRelayFingerprint
        // A parked connection carries no traffic until a phone joins, so TCP keepalive
        // is what notices a relay or NAT that silently dropped it.
        let bootstrap = ClientBootstrap(group: group)
            .connectTimeout(.seconds(5))
            .channelOption(ChannelOptions.socketOption(.so_keepalive), value: 1)
            .channelOption(Self.tcpOption(TCP_KEEPALIVE), value: Self.keepaliveIdleSeconds)
            .channelOption(Self.tcpOption(TCP_KEEPINTVL), value: Self.keepaliveIntervalSeconds)
            .channelOption(Self.tcpOption(TCP_KEEPCNT), value: Self.keepaliveProbeCount)
            .channelInitializer { [weak self] channel in
                channel.eventLoop.makeCompletedFuture(withResultOf: {
                    // Outer TLS sits at the bottom of the pipeline for the whole connection:
                    // the join line, and later the inner TLS, travel inside it.
                    let outerTLS = try NIOSSLClientHandler(
                        context: relayContext,
                        serverHostname: nil,
                        customVerificationCallback: { [weak self] certificates, promise in
                            promise.succeed(
                                Self.verifyRelayCertificate(certificates, pinned: pinned) { outcome in
                                    self?.queue.async { self?.handleRelayCertificate(outcome, generation: gen) }
                                }
                            )
                        }
                    )
                    try channel.pipeline.syncOperations.addHandler(outerTLS, name: "relay.outer-tls")
                    try channel.pipeline.syncOperations.addHandler(
                        RelayJoinHandler(
                            joinLine: joinLine,
                            onJoinLineWritten: { [weak self] result in
                                self?.queue.async { self?.handleJoinLineWritten(result, generation: gen) }
                            },
                            onPaired: { [weak self] channel, leftover, joinHandler in
                                self?.queue.async {
                                    self?.handlePaired(
                                        channel: channel,
                                        leftover: leftover,
                                        joinHandler: joinHandler,
                                        generation: gen
                                    )
                                }
                            },
                            onFailure: { [weak self] error in
                                self?.queue.async { self?.handleJoinFailure(error, generation: gen) }
                            }
                        ),
                        name: "relay.join"
                    )
                })
            }
        bootstrap.connect(host: relay.host, port: Int(relay.port)).whenComplete { [weak self] result in
            self?.queue.async { self?.handleConnectResult(result, generation: gen) }
        }
    }

    enum RelayCertificateOutcome: Equatable {
        case matched
        case learned(String)
        case mismatch(String)
        case missing
    }

    /// Pins the relay's leaf certificate by the SHA-256 of its DER bytes; hostname and chain
    /// are deliberately not checked (the relay is self-signed). With nothing pinned the
    /// certificate is trusted on first use.
    static func verifyRelayCertificate(
        _ certificates: [NIOSSLCertificate],
        pinned: String?,
        report: (RelayCertificateOutcome) -> Void
    ) -> NIOSSLVerificationResult {
        guard let leaf = certificates.first, let der = try? leaf.toDERBytes() else {
            report(.missing)
            return .failed
        }
        let observed = Fingerprint.hexSHA256(Data(der))
        guard let pinned else {
            report(.learned(observed))
            return .certificateVerified
        }
        guard observed == pinned else {
            report(.mismatch(observed))
            return .failed
        }
        report(.matched)
        return .certificateVerified
    }

    private func handleRelayCertificate(_ outcome: RelayCertificateOutcome, generation gen: UInt64) {
        guard gen == generation else { return }
        switch outcome {
        case .matched:
            break
        case .learned(let fingerprint):
            diagnostics.recordEvent(.certificate, "pinned relay certificate \(DiagnosticsRedaction.short(fingerprint)) on first use")
            pinnedRelayFingerprint = fingerprint
            let callback = onRelayFingerprintLearned
            DispatchQueue.main.async { callback?(fingerprint) }
        case .mismatch(let fingerprint):
            diagnostics.recordEvent(.certificate, "relay presented \(DiagnosticsRedaction.short(fingerprint)), not the pinned certificate")
            haltLocked(.relayCertificateMismatch(fingerprint))
        case .missing:
            concludeAttemptLocked(with: "relay sent no certificate")
        }
    }

    /// Stops this transport for good: retrying can't help until the user changes settings.
    private func haltLocked(_ state: State) {
        halted = true
        attemptConcluded = true
        reconnectScheduled = false
        generation &+= 1
        channel?.close(promise: nil)
        channel = nil
        setRelayStateLocked(state)
    }

    private func relayClientContextLocked() -> NIOSSLContext? {
        if let relayClientContext { return relayClientContext }
        var configuration = TLSConfiguration.makeClientConfiguration()
        // Must not be `.none`, or NIOSSL skips the pinning callback.
        configuration.certificateVerification = .noHostnameVerification
        configuration.minimumTLSVersion = .tlsv12
        guard let context = try? NIOSSLContext(configuration: configuration) else { return nil }
        relayClientContext = context
        return context
    }

    static let keepaliveIdleSeconds: CInt = 30
    static let keepaliveIntervalSeconds: CInt = 10
    static let keepaliveProbeCount: CInt = 3

    private static func tcpOption(_ name: CInt) -> ChannelOptions.Types.SocketOption {
        ChannelOptions.Types.SocketOption(level: .tcp, name: NIOBSDSocket.Option(rawValue: name))
    }

    private func reconnectIfIdleLocked() {
        guard relay != nil, started, !suspended, !stopped, !halted, authenticatedChannel == nil else { return }
        attemptConcluded = true
        channel?.close(promise: nil)
        channel = nil
        reconnectAttempt = 0
        connectLocked()
    }

    private func handleConnectResult(_ result: Result<Channel, Error>, generation gen: UInt64) {
        guard gen == generation, started, !suspended, !stopped else {
            if case .success(let channel) = result {
                channel.close(promise: nil)
            }
            return
        }
        switch result {
        case .success(let channel):
            guard !attemptConcluded else {
                channel.close(promise: nil)
                return
            }
            self.channel = channel
            channel.closeFuture.whenComplete { [weak self] _ in
                self?.queue.async { self?.handleChannelClosed(channel, generation: gen) }
            }
        case .failure(let error):
            concludeAttemptLocked(with: String(describing: error))
        }
    }

    private func handleJoinLineWritten(_ result: Result<Void, Error>, generation gen: UInt64) {
        guard gen == generation, started, !suspended, !stopped else { return }
        switch result {
        case .success:
            if relayState == .connecting {
                setRelayStateLocked(.waitingForPhone)
            }
        case .failure(let error):
            concludeAttemptLocked(with: String(describing: error))
        }
    }

    private func handlePaired(
        channel: Channel,
        leftover: ByteBuffer,
        joinHandler: RelayJoinHandler,
        generation gen: UInt64
    ) {
        guard gen == generation, started, !suspended, !stopped else { return }
        self.channel = channel
        postPairedLeftover = leftover.readableBytes > 0 ? leftover : nil
        lastPostPairedLeftover = postPairedLeftover.map { Data($0.readableBytesView) }
        reconnectAttempt = 0
        setRelayStateLocked(.waitingForPhone)
        installSessionPipeline(on: channel, joinHandler: joinHandler, generation: gen)
    }

    /// Builds the session handler for a channel on either path. Safe to call off `queue`: it
    /// only reads constants, and every callback hops onto `queue`.
    private func makeSessionHandler(channel: Channel, path: SessionPath, generation gen: UInt64) -> RelaySessionHandler {
        let session = RelaySessionHandler(psks: pskProvider())
        session.diagnostics = diagnostics
        // `channel` is captured weakly to avoid an ARC cycle
        // (channel → pipeline → session → closure → channel). NIO currently
        // breaks the pipeline → session link when it removes handlers on close,
        // but the weak capture keeps the session from retaining its channel
        // regardless of the close path.
        session.onAuthenticated = { [weak self, weak channel] psk, negotiation in
            guard let channel else { return }
            self?.queue.async {
                self?.handleSessionAuthenticated(
                    AuthenticatedSession(psk: psk, negotiation: negotiation, path: path),
                    channel: channel,
                    generation: gen
                )
            }
        }
        session.onEnvelope = { [weak self, weak channel] envelope in
            guard let channel else { return }
            self?.queue.async {
                self?.handleEnvelope(envelope, channel: channel, path: path, generation: gen)
            }
        }
        session.onClosed = { [weak self, weak channel] in
            guard let channel else { return }
            self?.queue.async {
                self?.handleSessionClosed(channel: channel, path: path, generation: gen)
            }
        }
        session.onError = { [weak self] message in
            self?.queue.async { self?.onDebugLog?(message) }
        }
        return session
    }

    /// Adds the inner TLS server, idle timeout, framing and session; must run on the channel's event loop.
    private static func addSessionHandlers(
        to channel: Channel,
        sslContext: NIOSSLContext,
        session: RelaySessionHandler,
        diagnostics: DiagnosticsRecorder
    ) throws {
        let sslHandler = NIOSSLServerHandler(context: sslContext)
        try channel.pipeline.syncOperations.addHandler(sslHandler, name: "relay.tls")
        try channel.pipeline.syncOperations.addHandler(
            IdleStateHandler(readTimeout: RelaySessionHandler.idleTimeout),
            name: "relay.idle"
        )
        try channel.pipeline.syncOperations.addHandler(
            ByteToMessageHandler(RelayFrameDecoderHandler(diagnostics: diagnostics)),
            name: "relay.frames"
        )
        try channel.pipeline.syncOperations.addHandler(session, name: "relay.session")
    }

    /// Installs the inner TLS, framing and the session handler on the paired channel, above
    /// the outer TLS. The join handler keeps buffering any bytes that arrive until
    /// `completeHandoff()` replays them into the new pipeline, so nothing between `PAIRED`
    /// and installation is lost.
    private func installSessionPipeline(
        on channel: Channel,
        joinHandler: RelayJoinHandler,
        generation gen: UInt64
    ) {
        guard let sslContext = serverSSLContextLocked() else {
            concludeAttemptLocked(with: "TLS identity unavailable")
            return
        }
        let session = makeSessionHandler(channel: channel, path: .relay, generation: gen)
        let diagnostics = self.diagnostics
        _lastSessionForTesting = session
        channel.eventLoop.execute { [weak self] in
            do {
                try Self.addSessionHandlers(to: channel, sslContext: sslContext, session: session, diagnostics: diagnostics)
                joinHandler.completeHandoff()
                self?.queue.async { self?.clearPostPairedLeftover(generation: gen) }
            } catch {
                channel.close(promise: nil)
                self?.queue.async {
                    self?.concludeAttemptLocked(with: String(describing: error))
                }
            }
        }
    }

    private func clearPostPairedLeftover(generation gen: UInt64) {
        guard gen == generation else { return }
        postPairedLeftover = nil
    }

    private func serverSSLContextLocked() -> NIOSSLContext? {
        if let sslContext { return sslContext }
        guard let context = try? identity.nioContext() else { return nil }
        sslContext = context
        return context
    }

    /// With `reliable` negotiated the session isn't reported until the phone's `Resume`
    /// arrives (§5): the Mac sends its own `Resume` now and holds feature traffic.
    private func handleSessionAuthenticated(
        _ session: AuthenticatedSession,
        channel: Channel,
        generation gen: UInt64
    ) {
        guard isCurrentLocked(channel, path: session.path, generation: gen) else { return }
        if let previous = authenticatedChannel, previous !== channel {
            replaceSessionLocked(previous)
        }
        authenticatedChannel = channel
        authenticatedPath = session.path
        negotiation = session.negotiation
        if session.path == .local {
            diagnostics.recordEvent(.state, "phone authenticated on the local network")
        }
        guard session.negotiation.supports(ProtocolV2.Capability.reliable) else {
            completeAuthenticationLocked(session)
            return
        }
        awaitingPeerResume = true
        writeLocked(reliableStream.makeResume(), to: channel)
        queue.asyncAfter(deadline: .now() + Self.resumeTimeout) { [weak self, weak channel] in
            guard let self, let channel,
                  self.authenticatedChannel === channel, self.awaitingPeerResume else { return }
            self.onDebugLog?("phone sent no Resume; closing session")
            channel.close(promise: nil)
        }
        pendingSession = session
    }

    private var pendingSession: AuthenticatedSession?

    /// A phone session is up and has been reported to the owner.
    private var sessionReportedLocked: Bool { authenticatedChannel != nil && !awaitingPeerResume }

    /// The phone authenticated again (typically on the other path, after its old connection
    /// died unnoticed): the old session is reported as ended and closed (§1.4 "One session").
    private func replaceSessionLocked(_ previous: Channel) {
        let wasReported = sessionReportedLocked
        authenticatedChannel = nil
        authenticatedPath = nil
        awaitingPeerResume = false
        pendingSession = nil
        onDebugLog?("a new phone session replaces the current one")
        if wasReported {
            diagnostics.recordSessionEnded()
            let callback = onClientDisconnected
            DispatchQueue.main.async { callback?() }
        }
        previous.close(promise: nil)
    }

    private func completeAuthenticationLocked(_ session: AuthenticatedSession) {
        awaitingPeerResume = false
        pendingSession = nil
        diagnostics.recordSessionStarted()
        refreshStateLocked()
        if let channel = authenticatedChannel { schedulePingLocked(channel: channel) }
        let callback = onClientAuthenticated
        DispatchQueue.main.async { callback?(session) }
    }

    /// How often the Mac pings a connected phone to measure the round trip.
    static let pingInterval: TimeInterval = 30

    /// Pings the phone every `pingInterval` while this channel's session lasts; the phone
    /// echoes `sent_at_ms` in its Pong, which `handleEnvelope` turns into an RTT sample.
    private func schedulePingLocked(channel: Channel) {
        queue.asyncAfter(deadline: .now() + Self.pingInterval) { [weak self, weak channel] in
            guard let self, let channel, self.authenticatedChannel === channel,
                  !self.awaitingPeerResume else { return }
            var ping = DSPing()
            ping.sentAtMs = UInt64(Date().timeIntervalSince1970 * 1000)
            self.sendLocked(protocolEnvelope(.ping(ping)))
            self.schedulePingLocked(channel: channel)
        }
    }

    private func handleEnvelope(_ envelope: DSEnvelope, channel: Channel, path: SessionPath, generation gen: UInt64) {
        guard isCurrentLocked(channel, path: path, generation: gen), authenticatedChannel === channel else { return }
        if case .pong(let pong)? = envelope.payload {
            diagnostics.recordRoundTrip(sentAtMs: pong.sentAtMs)
        }
        guard negotiation?.supports(ProtocolV2.Capability.reliable) == true else {
            deliverLocked(envelope)
            return
        }
        if case .resume(let resume)? = envelope.payload {
            // A Resume's envelope-level `ack` is ignored: the peer may still be talking about
            // a previous stream of ours. `peer_stream_id` + `last_received_seq` carry that.
            handlePeerResumeLocked(resume, channel: channel)
            return
        }
        let disposition = reliableStream.receive(envelope, applyAck: !awaitingPeerResume)
        if envelope.seq > 0 { scheduleStandaloneAckLocked(channel: channel) }
        switch envelope.payload {
        case .ackOnly?:
            break
        default:
            if disposition == .process { deliverLocked(envelope) }
        }
    }

    private func handlePeerResumeLocked(_ resume: DSResume, channel: Channel) {
        guard awaitingPeerResume, let session = pendingSession else {
            onDebugLog?("ignoring unexpected Resume")
            return
        }
        let retransmits = reliableStream.applyPeerResume(resume)
        if !retransmits.isEmpty { onDebugLog?("resume: retransmitting \(retransmits.count)") }
        retransmits.forEach { writeLocked($0, to: channel) }
        completeAuthenticationLocked(session)
    }

    private func deliverLocked(_ envelope: DSEnvelope) {
        let callback = onEnvelope
        DispatchQueue.main.async { callback?(envelope) }
    }

    private func scheduleStandaloneAckLocked(channel: Channel) {
        queue.asyncAfter(deadline: .now() + ReliableStream.standaloneAckDelay + 0.05) { [weak self, weak channel] in
            guard let self, let channel, self.authenticatedChannel === channel,
                  let ack = self.reliableStream.standaloneAckIfDue() else { return }
            self.writeLocked(ack, to: channel)
        }
    }

    private func handleSessionClosed(channel: Channel, path: SessionPath, generation gen: UInt64) {
        let wasAuthenticated = authenticatedChannel === channel
        let wasReported = wasAuthenticated && !awaitingPeerResume
        if wasAuthenticated {
            authenticatedChannel = nil
            authenticatedPath = nil
            awaitingPeerResume = false
            pendingSession = nil
        }
        if path == .local {
            localChannels.removeValue(forKey: ObjectIdentifier(channel))
        }
        if wasReported { diagnostics.recordSessionEnded() }
        if wasReported {
            let callback = onClientDisconnected
            DispatchQueue.main.async { callback?() }
        }
        guard started, !suspended, !stopped else { return }
        if path == .relay, wasAuthenticated, gen == generation, self.channel === channel {
            setRelayStateLocked(.waitingForPhone)
        } else {
            refreshStateLocked()
        }
    }

    /// Whether `channel` is a live connection on its path. A relay channel is current while it
    /// is the relay connection; a local one while the listener that accepted it is open.
    private func isCurrentLocked(_ channel: Channel, path: SessionPath, generation gen: UInt64) -> Bool {
        guard started, !suspended, !stopped else { return false }
        switch path {
        case .relay: return gen == generation && self.channel === channel
        case .local: return gen == localGeneration && localChannels[ObjectIdentifier(channel)] != nil
        }
    }

    private func isLiveLocked(_ channel: Channel) -> Bool {
        self.channel === channel || localChannels[ObjectIdentifier(channel)] != nil
    }

    // MARK: Local listener (§1.4)

    private func openListenerLocked() {
        guard let local, started, !suspended, !stopped, localServerChannel == nil else { return }
        localGeneration &+= 1
        let gen = localGeneration
        localRetryScheduled = false
        if group == nil {
            group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
        }
        guard let group else { return }
        guard let sslContext = serverSSLContextLocked() else {
            setLocalStateLocked(.failed("TLS identity unavailable"))
            return
        }
        let diagnostics = self.diagnostics
        let bootstrap = ServerBootstrap(group: group)
            .serverChannelOption(ChannelOptions.backlog, value: 16)
            .serverChannelOption(ChannelOptions.socketOption(.so_reuseaddr), value: 1)
            .childChannelOption(ChannelOptions.socketOption(.so_keepalive), value: 1)
            .childChannelOption(Self.tcpOption(TCP_KEEPALIVE), value: Self.keepaliveIdleSeconds)
            .childChannelOption(Self.tcpOption(TCP_KEEPINTVL), value: Self.keepaliveIntervalSeconds)
            .childChannelOption(Self.tcpOption(TCP_KEEPCNT), value: Self.keepaliveProbeCount)
            .childChannelInitializer { [weak self] channel in
                guard let self, LocalAddressPolicy.isAllowed(channel.remoteAddress) else {
                    let peer = channel.remoteAddress.map { "\($0)" } ?? "unknown"
                    diagnostics.recordEvent(.state, "refused a local connection from \(peer)")
                    return channel.close()
                }
                let session = self.makeSessionHandler(channel: channel, path: .local, generation: gen)
                return channel.eventLoop.makeCompletedFuture(withResultOf: {
                    try Self.addSessionHandlers(to: channel, sslContext: sslContext, session: session, diagnostics: diagnostics)
                    // Queued before the TLS handshake can finish, so the channel is
                    // registered before any session callback for it runs.
                    self.queue.async { self.registerLocalChannelLocked(channel, generation: gen) }
                })
            }
        bootstrap.bind(host: "::", port: local.port)
            .flatMapError { _ in bootstrap.bind(host: "0.0.0.0", port: local.port) }
            .whenComplete { [weak self] result in
                self?.queue.async { self?.handleListenerBound(result, generation: gen) }
            }
    }

    private func handleListenerBound(_ result: Result<Channel, Error>, generation gen: UInt64) {
        guard gen == localGeneration, let local, started, !suspended, !stopped else {
            if case .success(let channel) = result { channel.close(promise: nil) }
            return
        }
        switch result {
        case .success(let channel):
            localServerChannel = channel
            let port = channel.localAddress?.port ?? local.port
            setLocalStateLocked(.listening(port: port))
            if let advertisement = local.advertisement {
                advertiser.start(advertisement, port: port)
            }
        case .failure(let error):
            setLocalStateLocked(.failed("can't listen on port \(local.port): \(error)"))
            guard !localRetryScheduled else { return }
            localRetryScheduled = true
            queue.asyncAfter(deadline: .now() + 10) { [weak self] in
                guard let self, self.localGeneration == gen, self.localRetryScheduled else { return }
                self.localRetryScheduled = false
                self.openListenerLocked()
            }
        }
    }

    /// Enforces the unauthenticated-connection cap and the auth deadline (§1.4).
    private func registerLocalChannelLocked(_ channel: Channel, generation gen: UInt64) {
        guard gen == localGeneration, localServerChannel != nil, started, !suspended, !stopped else {
            channel.close(promise: nil)
            return
        }
        let unauthenticated = localChannels.values.filter { $0 !== authenticatedChannel }.count
        guard unauthenticated < LocalListenerOptions.maxUnauthenticated else {
            onDebugLog?("too many unauthenticated local connections; refusing one")
            channel.close(promise: nil)
            return
        }
        localChannels[ObjectIdentifier(channel)] = channel
        channel.closeFuture.whenComplete { [weak self, weak channel] _ in
            guard let channel else { return }
            self?.queue.async { self?.localChannels.removeValue(forKey: ObjectIdentifier(channel)) }
        }
        queue.asyncAfter(deadline: .now() + LocalListenerOptions.authTimeout) { [weak self, weak channel] in
            guard let self, let channel, self.localChannels[ObjectIdentifier(channel)] != nil,
                  self.authenticatedChannel !== channel else { return }
            self.onDebugLog?("local connection didn't authenticate in time; closing")
            channel.close(promise: nil)
        }
    }

    private func closeListenerLocked() {
        guard local != nil else { return }
        localGeneration &+= 1
        localRetryScheduled = false
        localServerChannel?.close(promise: nil)
        localServerChannel = nil
        // Session clean-up runs from each channel's own close callback.
        localChannels.values.forEach { $0.close(promise: nil) }
        advertiser.stop()
        setLocalStateLocked(.off)
    }

    private func setLocalStateLocked(_ newState: LocalState) {
        guard _localState != newState else { return }
        _localState = newState
        switch newState {
        case .listening(let port): diagnostics.recordEvent(.state, "Listening on the local network (port \(port))")
        case .failed(let message): diagnostics.recordEvent(.state, "Local listener failed: \(message)")
        case .off: break
        }
        let callback = onLocalStateChange
        DispatchQueue.main.async { callback?(newState) }
        refreshStateLocked()
    }

    private func handleJoinFailure(_ error: Error, generation gen: UInt64) {
        guard gen == generation, started, !suspended, !stopped else { return }
        if case RelayTransportError.upgradeRequired = error {
            haltLocked(.relayRequiresUpgrade)
            return
        }
        concludeAttemptLocked(with: String(describing: error))
    }

    private func handleChannelClosed(_ channel: Channel, generation gen: UInt64) {
        guard gen == generation, started, !suspended, !stopped, !attemptConcluded else { return }
        concludeAttemptLocked(with: "relay connection closed")
    }

    private func concludeAttemptLocked(with message: String) {
        guard !attemptConcluded else { return }
        attemptConcluded = true
        channel?.close(promise: nil)
        channel = nil
        setRelayStateLocked(.failed(message))
        scheduleReconnectLocked()
    }

    private func scheduleReconnectLocked() {
        guard started, !suspended, !stopped, !halted, !reconnectScheduled else { return }
        reconnectScheduled = true
        reconnectAttempt = min(reconnectAttempt + 1, 6)
        let exponent = min(reconnectAttempt - 1, 5)
        let base = min(pow(2.0, Double(exponent)), 30.0)
        let delay = min(base * Double.random(in: 0.8...1.2), 30.0)
        let gen = generation
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, self.generation == gen else { return }
            self.reconnectScheduled = false
            self.connectLocked()
        }
    }

    private func setRelayStateLocked(_ newState: State) {
        relayState = newState
        refreshStateLocked()
    }

    /// The overall state: connected while a session is up; otherwise the relay's state when
    /// the relay is in use (the local listener is reported separately), else the listener's.
    private func refreshStateLocked() {
        guard !stopped else { return }
        // A suspend closes the session; its close callback arrives later and is ignored.
        if sessionReportedLocked && !suspended {
            setStateLocked(.connected)
        } else if relay != nil {
            setStateLocked(relayState)
        } else {
            switch _localState {
            case .listening: setStateLocked(.waitingForPhone)
            case .failed(let message): setStateLocked(.failed(message))
            case .off: setStateLocked(.stopped)
            }
        }
    }

    private func setStateLocked(_ newState: State) {
        guard _state != newState else { return }
        _state = newState
        diagnostics.recordState(newState.diagnosticDescription, isError: newState.isFailure)
        let callback = onStateChange
        DispatchQueue.main.async { callback?(newState) }
    }
}

private enum RelayTransportError: Error, CustomStringConvertible {
    case malformedResponse(String)
    case unexpectedResponse(String)
    case upgradeRequired

    var description: String {
        switch self {
        case .malformedResponse(let line):
            return "malformed relay response: \(line)"
        case .unexpectedResponse(let line):
            return "unexpected relay response: \(line)"
        case .upgradeRequired:
            return "relay requires a newer protocol"
        }
    }
}

private final class RelayJoinHandler: ChannelInboundHandler, RemovableChannelHandler {
    typealias InboundIn = ByteBuffer

    /// Matches the relay's own join-line cap; a longer response is not from a relay.
    static let maxResponseLineBytes = 512

    private let joinLine: String
    private let onJoinLineWritten: (Result<Void, Error>) -> Void
    private let onPaired: (Channel, ByteBuffer, RelayJoinHandler) -> Void
    private let onFailure: (Error) -> Void
    private var buffer = ByteBufferAllocator().buffer(capacity: 0)
    private var paired = false
    private var parseFinished = false
    private weak var context: ChannelHandlerContext?

    init(
        joinLine: String,
        onJoinLineWritten: @escaping (Result<Void, Error>) -> Void,
        onPaired: @escaping (Channel, ByteBuffer, RelayJoinHandler) -> Void,
        onFailure: @escaping (Error) -> Void
    ) {
        self.joinLine = joinLine
        self.onJoinLineWritten = onJoinLineWritten
        self.onPaired = onPaired
        self.onFailure = onFailure
    }

    /// The outer TLS buffers this write until its handshake completes, so the join line
    /// never leaves the Mac in the clear.
    func channelActive(context: ChannelHandlerContext) {
        var out = context.channel.allocator.buffer(capacity: joinLine.utf8.count)
        out.writeString(joinLine)
        let write = context.eventLoop.makePromise(of: Void.self)
        context.writeAndFlush(NIOAny(out), promise: write)
        write.futureResult.whenComplete { [onJoinLineWritten] result in
            onJoinLineWritten(result)
        }
        context.fireChannelActive()
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        var incoming = unwrapInboundIn(data)
        if paired {
            buffer.writeBuffer(&incoming)
            return
        }
        guard !parseFinished else { return }
        buffer.writeBuffer(&incoming)
        guard let newline = buffer.readableBytesView.firstIndex(of: 0x0A) else {
            if buffer.readableBytes > Self.maxResponseLineBytes {
                parseFinished = true
                context.close(promise: nil)
                onFailure(RelayTransportError.malformedResponse("response line exceeds \(Self.maxResponseLineBytes) bytes"))
            }
            return
        }
        let length = newline - buffer.readableBytesView.startIndex + 1
        guard let lineBytes = buffer.readBytes(length: length) else { return }
        parseFinished = true
        let line = String(decoding: lineBytes.dropLast(), as: UTF8.self)
        do {
            switch try RelayResponse.parse(line) {
            case .paired:
                paired = true
                self.context = context
                onPaired(context.channel, buffer, self)
            case .wait:
                throw RelayTransportError.unexpectedResponse("WAIT")
            case .error("upgrade"):
                throw RelayTransportError.upgradeRequired
            case .error(let reason):
                throw RelayTransportError.unexpectedResponse("ERROR \(reason)")
            }
        } catch {
            context.close(promise: nil)
            onFailure(error)
        }
    }

    /// TLS failures (including a pinning mismatch) surface here before the session exists.
    func errorCaught(context: ChannelHandlerContext, error: Error) {
        guard !paired else {
            context.fireErrorCaught(error)
            return
        }
        context.close(promise: nil)
    }

    /// Must be called on the channel's event loop, after the TLS and session handlers
    /// are installed. Replays every byte buffered since `PAIRED` into the new pipeline
    /// and removes this handler, so no bytes are lost between the join line and TLS.
    func completeHandoff() {
        guard let context else { return }
        self.context = nil
        let buffered = buffer
        buffer.clear()
        if buffered.readableBytes > 0 {
            context.fireChannelRead(NIOAny(buffered))
        }
        context.fireChannelReadComplete()
        context.pipeline.removeHandler(self, promise: nil)
    }
}

extension RelayTransport.State {
    /// A short, secret-free description for the diagnostics log.
    var diagnosticDescription: String {
        switch self {
        case .stopped: return "Stopped"
        case .connecting: return "Connecting to relay"
        case .waitingForPhone: return "Waiting for phone"
        case .connected: return "Connected"
        case .failed(let message): return "Failed: \(message)"
        case .relayCertificateMismatch: return "Relay certificate changed"
        case .relayRequiresUpgrade: return "Relay requires a newer protocol"
        }
    }

    var isFailure: Bool {
        switch self {
        case .failed, .relayCertificateMismatch, .relayRequiresUpgrade: return true
        case .stopped, .connecting, .waitingForPhone, .connected: return false
        }
    }
}
