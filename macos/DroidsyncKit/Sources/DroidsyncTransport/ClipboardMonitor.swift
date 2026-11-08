import Foundation

public protocol ClipboardPasteboard: AnyObject {
    var changeCount: Int { get }
    /// True when the current contents are marked concealed, transient or
    /// auto-generated (nspasteboard.org conventions), e.g. a password manager copy.
    var holdsPrivateContent: Bool { get }
    func readText() -> String?
    func readImagePNG() -> Data?
    func writeText(_ text: String)
    func writeImage(png: Data)
}

public final class ClipboardMonitor {
    public enum Event: Equatable {
        case text(String)
        case image(Data)
        case deferredImage(Data)
    }

    public var onEvent: ((Event) -> Void)?
    public var isEnabled: () -> Bool = { true }
    public var isConnected: () -> Bool = { false }
    public var isPhoneMetered: () -> Bool = { true }

    private let pasteboard: ClipboardPasteboard
    private var lastChangeCount: Int
    private var lastAppliedHash: String?
    private var lastSentHash: String?

    public init(pasteboard: ClipboardPasteboard) {
        self.pasteboard = pasteboard
        self.lastChangeCount = pasteboard.changeCount
    }

    public func poll() {
        let changeCount = pasteboard.changeCount
        guard changeCount != lastChangeCount else { return }
        lastChangeCount = changeCount
        guard isEnabled(), isConnected(), !pasteboard.holdsPrivateContent else { return }

        if let text = pasteboard.readText(), !text.isEmpty {
            let hash = ClipboardHash.sha256Hex(Data(text.utf8))
            guard hash != lastAppliedHash, hash != lastSentHash else { return }
            markSent(hash)
            onEvent?(.text(text))
            return
        }
        if let image = pasteboard.readImagePNG(), !image.isEmpty {
            let hash = ClipboardHash.sha256Hex(image)
            guard hash != lastAppliedHash, hash != lastSentHash else { return }
            if isPhoneMetered() {
                onEvent?(.deferredImage(image))
            } else {
                markSent(hash)
                onEvent?(.image(image))
            }
        }
    }

    public func noteSent(_ data: Data) {
        markSent(ClipboardHash.sha256Hex(data))
    }

    /// Each side's hash only suppresses echoes until the other side changes the
    /// clipboard, so copying earlier content again still syncs.
    public func noteApplied(_ data: Data) {
        lastAppliedHash = ClipboardHash.sha256Hex(data)
        lastSentHash = nil
        lastChangeCount = pasteboard.changeCount
    }

    private func markSent(_ hash: String) {
        lastSentHash = hash
        lastAppliedHash = nil
    }
}
