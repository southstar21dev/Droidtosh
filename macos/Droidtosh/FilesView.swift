import AppKit
import SwiftUI
import UniformTypeIdentifiers
import DroidsyncKit

/// Send files to the phone (drop or pick) and follow transfers both ways.
struct FilesView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var files: FilesController
    @State private var dropTargeted = false

    init(model: AppModel) {
        self.model = model
        files = model.files
    }

    var body: some View {
        List {
            Section {
                dropZone
                    .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 12, trailing: 0))
                    .listRowSeparator(.hidden)
            }
            if !files.activeTransfers.isEmpty {
                Section("In Progress") {
                    ForEach(files.activeTransfers) { transfer in
                        FileTransferRow(transfer: transfer, files: files)
                    }
                }
            }
            if !finished.isEmpty {
                Section {
                    ForEach(finished) { transfer in
                        FileTransferRow(transfer: transfer, files: files)
                    }
                } header: {
                    HStack {
                        Text("Recent")
                        Spacer()
                        Button("Clear") { files.clearFinished() }
                            .buttonStyle(.link)
                            .font(.caption)
                    }
                }
            }
        }
        .listStyle(.inset)
        .overlay(alignment: .bottom) {
            if files.transfers.isEmpty {
                ContentUnavailableView {
                    Label("No Transfers Yet", systemImage: "arrow.up.arrow.down.circle")
                } description: {
                    Text("Files from your phone are saved to Downloads.")
                }
                .frame(maxHeight: 260)
                .padding(.bottom, 40)
            }
        }
        .navigationTitle("Files")
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button {
                    files.showDownloads()
                } label: {
                    Label("Show Downloads", systemImage: "folder")
                }
                .help("Open the Downloads folder, where files from the phone are saved")
                Button {
                    files.clearFinished()
                } label: {
                    Label("Clear Finished", systemImage: "trash")
                }
                .help("Remove finished transfers from the list")
                .disabled(finished.isEmpty)
                Button {
                    files.pickAndSend()
                } label: {
                    Label("Send File…", systemImage: "square.and.arrow.up")
                }
                .help("Choose files or folders to send to your phone")
                .disabled(!files.phoneSupportsFiles)
            }
        }
    }

    private var finished: [FileTransferSnapshot] { files.transfers.filter { $0.phase.isFinished } }

    private var dropZone: some View {
        VStack(spacing: 10) {
            Image(systemName: dropTargeted ? "arrow.down.doc.fill" : "square.and.arrow.up.on.square")
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(dropTargeted ? Color.accentColor : .secondary)
                .symbolEffect(.bounce, value: dropTargeted)
                .accessibilityHidden(true)
            if files.phoneSupportsFiles {
                Text(dropTargeted ? "Release to send to \(phoneName)" : "Drop files or folders to send them to \(phoneName)")
                    .font(.headline)
                Text("Folders are sent as a zip archive.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Button("Choose Files…") { files.pickAndSend() }
                    .padding(.top, 2)
            } else {
                Text(dropUnavailableText)
                    .font(.headline)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 170)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(dropTargeted ? Color.accentColor.opacity(0.1) : Color(nsColor: .quaternarySystemFill))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(style: StrokeStyle(lineWidth: dropTargeted ? 2 : 1.5, dash: [7, 5]))
                .foregroundStyle(dropTargeted ? Color.accentColor : Color.secondary.opacity(0.45))
        )
        .animation(.easeOut(duration: 0.15), value: dropTargeted)
        .sendFilesOnDrop(files: files, isTargeted: $dropTargeted)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Drop zone: drop files to send them to your phone")
    }

    private var phoneName: String { model.connection.connectedDevice ?? "your phone" }

    private var dropUnavailableText: String {
        model.connection.phoneConnected
            ? "Update Droidtosh on your phone to send files"
            : "Connect your phone to send files"
    }
}

struct FileTransferRow: View {
    let transfer: FileTransferSnapshot
    let files: FilesController
    var compact = false

    var body: some View {
        HStack(spacing: compact ? 8 : 12) {
            FileIcon(name: transfer.name, direction: transfer.direction, size: compact ? 26 : 34)
            VStack(alignment: .leading, spacing: compact ? 2 : 4) {
                Text(transfer.name)
                    .font(compact ? .callout : .body)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if transfer.phase == .transferring || transfer.phase == .offered || transfer.phase == .compressing {
                    ProgressView(value: transfer.phase == .compressing ? nil : transfer.fraction)
                        .progressViewStyle(.linear)
                        .controlSize(.small)
                }
                Text(statusText)
                    .font(.caption)
                    .foregroundStyle(statusIsError ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if transfer.phase.isFinished {
                if transfer.phase == .completed, transfer.direction == .incoming {
                    Button {
                        files.reveal(id: transfer.id)
                    } label: {
                        Image(systemName: "magnifyingglass.circle.fill")
                            .font(.title3)
                    }
                    .buttonStyle(.borderless)
                    .foregroundStyle(.secondary)
                    .help("Show in Finder")
                    .accessibilityLabel("Show \(transfer.name) in Finder")
                } else if transfer.phase == .completed {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                        .accessibilityHidden(true)
                }
            } else {
                Button {
                    files.cancel(id: transfer.id)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(compact ? .body : .title3)
                }
                .buttonStyle(.borderless)
                .foregroundStyle(.secondary)
                .help("Cancel")
                .accessibilityLabel("Cancel \(transfer.name)")
            }
        }
        .padding(.vertical, compact ? 0 : 3)
        .contextMenu {
            if transfer.phase.isFinished {
                if transfer.phase == .completed, transfer.direction == .incoming {
                    Button("Show in Finder") { files.reveal(id: transfer.id) }
                }
            } else {
                Button("Cancel Transfer") { files.cancel(id: transfer.id) }
            }
        }
    }

    private var sizeText: String {
        ByteCountFormatter.string(fromByteCount: Int64(clamping: transfer.size), countStyle: .file)
    }

    private var statusText: String {
        let toOrFrom = transfer.direction == .incoming ? "From phone" : "To phone"
        switch transfer.phase {
        case .compressing: return "Compressing…"
        case .queued: return "\(toOrFrom) · Waiting · \(sizeText)"
        case .offered: return "Waiting for phone · \(sizeText)"
        case .transferring:
            let done = ByteCountFormatter.string(fromByteCount: Int64(clamping: transfer.transferred), countStyle: .file)
            return "\(toOrFrom) · \(done) of \(sizeText)"
        case .waitingForReconnect: return "Paused — resumes when the phone reconnects"
        case .completed:
            if transfer.direction == .incoming { return "Saved to Downloads · \(sizeText)" }
            return "Sent · \(sizeText)"
        case .failed(let reason): return "Failed — \(FilesController.describe(reason))"
        case .cancelled(let reason): return reason == "user" ? "Cancelled" : "Cancelled — \(FilesController.describe(reason))"
        }
    }

    private var statusIsError: Bool {
        if case .failed = transfer.phase { return true }
        return false
    }
}

/// The Finder icon for the file's type, with a small arrow for the direction.
struct FileIcon: View {
    let name: String
    let direction: FileTransferSnapshot.Direction
    var size: CGFloat = 34

    var body: some View {
        Image(nsImage: Self.icon(for: name))
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .overlay(alignment: .bottomTrailing) {
                Image(systemName: direction == .outgoing ? "arrow.up.circle.fill" : "arrow.down.circle.fill")
                    .font(.system(size: size * 0.38))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, direction == .outgoing ? Color.accentColor : Color.green)
                    .offset(x: 3, y: 2)
            }
            .accessibilityLabel(direction == .outgoing ? "Sending" : "Receiving")
    }

    static func icon(for name: String) -> NSImage {
        let ext = (name as NSString).pathExtension
        let type = ext.isEmpty ? UTType.data : (UTType(filenameExtension: ext) ?? .data)
        return NSWorkspace.shared.icon(for: type)
    }
}

extension View {
    /// Sends dropped file URLs to the phone while it supports file transfer.
    func sendFilesOnDrop(files: FilesController, isTargeted: Binding<Bool>) -> some View {
        onDrop(of: [.fileURL], isTargeted: isTargeted) { providers in
            guard files.phoneSupportsFiles else { return false }
            for provider in providers {
                _ = provider.loadObject(ofClass: URL.self) { url, _ in
                    guard let url, url.isFileURL else { return }
                    Task { @MainActor in files.send(urls: [url]) }
                }
            }
            return !providers.isEmpty
        }
    }
}
