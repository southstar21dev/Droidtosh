import AppKit

/// Puts a verification code on the pasteboard, marked concealed so the clipboard monitor
/// does not sync it to the phone (nor clipboard managers keep it), and clears it again after
/// a minute if it is still the current content.
@MainActor
final class CodeCopier {
    static let clearDelay: TimeInterval = 60

    private let pasteboard = NSPasteboard.general
    private var clearTask: Task<Void, Never>?

    func copy(_ code: String) {
        pasteboard.clearContents()
        pasteboard.setString(code, forType: .string)
        pasteboard.setData(Data(), forType: SystemPasteboard.concealedType)
        let changeCount = pasteboard.changeCount
        clearTask?.cancel()
        clearTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.clearDelay))
            guard !Task.isCancelled, let self, self.pasteboard.changeCount == changeCount else { return }
            self.pasteboard.clearContents()
        }
    }
}
