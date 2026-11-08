import SwiftUI
import DroidsyncKit

/// Live connection health for troubleshooting, with a redacted report to copy or export.
struct DiagnosticsView: View {
    @ObservedObject var diagnostics: DiagnosticsController
    @State private var copied = false

    private var snapshot: DiagnosticsSnapshot { diagnostics.snapshot }
    private var context: DiagnosticsContext { diagnostics.context }

    var body: some View {
        Form {
            Section {
                summary
            } footer: {
                Label("Secrets are redacted from copies and exports.", systemImage: "lock.fill")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            connectionSection
            roundTripSection
            reliabilitySection
            trafficSection
            activitySection
            eventsSection
        }
        .formStyle(.grouped)
        .navigationTitle("Diagnostics")
        .onAppear { diagnostics.startUpdating() }
        .onDisappear { diagnostics.stopUpdating() }
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button {
                    diagnostics.reconnectNow()
                } label: {
                    Label("Reconnect Now", systemImage: "arrow.triangle.2.circlepath")
                }
                .help("Drop the relay connection and connect again")
                Button {
                    diagnostics.exportLogs()
                } label: {
                    Label("Export Logs…", systemImage: "square.and.arrow.down")
                }
                .help("Save the report and the debug log as a text file")
                Button {
                    diagnostics.copyReport()
                    copied = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) { copied = false }
                } label: {
                    Label(copied ? "Copied" : "Copy Diagnostics", systemImage: copied ? "checkmark" : "doc.on.doc")
                }
                .help("Copy a redacted diagnostics report")
            }
        }
    }

    // MARK: Summary

    private var summary: some View {
        HStack(spacing: 16) {
            metric("State", value: snapshot.state.capitalized, symbol: "dot.radiowaves.left.and.right", tint: stateTint)
            Divider().frame(height: 34)
            metric("Round trip", value: snapshot.rtt.last.map(DiagnosticsReport.ms) ?? "—", symbol: "timer", tint: nil)
            Divider().frame(height: 34)
            metric("Uptime", value: snapshot.sessionStartedAt.map {
                DiagnosticsReport.duration(snapshot.capturedAt.timeIntervalSince($0))
            } ?? "—", symbol: "clock", tint: nil)
            Divider().frame(height: 34)
            metric("Outbox", value: "\(context.outboxDepth)", symbol: "tray.full", tint: context.outboxDepth > 0 ? .orange : nil)
        }
        .padding(.vertical, 4)
    }

    private var stateTint: Color {
        let state = snapshot.state.lowercased()
        if state.contains("connected") && !state.contains("dis") { return .green }
        if snapshot.lastError != nil && state.contains("fail") { return .red }
        return .orange
    }

    private func metric(_ title: String, value: String, symbol: String, tint: Color?) -> some View {
        HStack(spacing: 8) {
            IconTile(symbol: symbol, color: tint, size: 28)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(value)
                    .font(.callout.weight(.semibold))
                    .monospacedDigit()
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    // MARK: Sections

    private var connectionSection: some View {
        Section("Connection") {
            row("State", "\(snapshot.state) · \(ago(snapshot.stateSince))")
            row("Relay", context.relayHost.isEmpty ? "Not configured" : "\(context.relayHost):\(context.relayPort)")
            row("Relay fingerprint", DiagnosticsRedaction.short(context.relayFingerprint), monospaced: true)
            row("Protocol", context.negotiatedVersion.map { "v\($0)" } ?? "No phone session")
            if !context.capabilities.isEmpty {
                row("Capabilities", context.capabilities.joined(separator: ", "))
            }
            row("Session uptime", snapshot.sessionStartedAt.map {
                DiagnosticsReport.duration(snapshot.capturedAt.timeIntervalSince($0))
            } ?? "—")
            row("Reconnects since launch", "\(snapshot.reconnects)")
            if let error = snapshot.lastError {
                row("Last error", "\(error.message) · \(ago(error.date))", tint: .orange)
            } else {
                row("Last error", "None")
            }
        }
    }

    private var roundTripSection: some View {
        Section("Round Trip") {
            if let last = snapshot.rtt.last, let average = snapshot.rtt.average,
               let minimum = snapshot.rtt.minimum, let maximum = snapshot.rtt.maximum {
                row("Last", DiagnosticsReport.ms(last))
                row("Average", DiagnosticsReport.ms(average))
                row("Min / max", "\(DiagnosticsReport.ms(minimum)) / \(DiagnosticsReport.ms(maximum))")
                row("Samples", "\(snapshot.rtt.sampleCount)")
            } else {
                Text("Measured every 30 s while the phone is connected.")
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var reliabilitySection: some View {
        Section("Reliability") {
            row("Outbox", "\(context.outboxDepth) waiting")
            row("Retransmits", "\(context.reliability.retransmits)")
            row("Evicted / expired", "\(context.reliability.evicted) / \(context.reliability.expired)")
            row("Gaps / duplicates", "\(context.reliability.gaps) / \(context.reliability.duplicates)")
        }
    }

    private var trafficSection: some View {
        Section("Traffic") {
            row("This session", traffic(snapshot.session))
            row("Since launch", traffic(snapshot.total))
            let features = Set(snapshot.session.byFeatureIn.keys).union(snapshot.session.byFeatureOut.keys).sorted()
            ForEach(features, id: \.self) { feature in
                row(
                    feature.capitalized,
                    "\(snapshot.session.byFeatureIn[feature] ?? 0) in · \(snapshot.session.byFeatureOut[feature] ?? 0) out"
                )
            }
            row(
                "Rejected frames",
                "\(snapshot.rejected.total) (oversize \(snapshot.rejected.oversize), undecodable \(snapshot.rejected.undecodable), version \(snapshot.rejected.wrongVersion))",
                tint: snapshot.rejected.total > 0 ? .orange : nil
            )
        }
    }

    private var activitySection: some View {
        Section("Features") {
            row(
                "Clipboard images",
                "\(snapshot.clipboard.completed) completed · \(snapshot.clipboard.rejected) rejected · \(snapshot.clipboard.timedOut) timed out"
            )
            row(
                "File transfers",
                "\(snapshot.files.sent) sent · \(snapshot.files.received) received · \(snapshot.files.failed) failed · \(snapshot.files.resumed) resumed",
                tint: snapshot.files.failed > 0 ? .orange : nil
            )
            row(
                "Phone status",
                "\(snapshot.device.statusesReceived) updates · \(snapshot.device.lowBatteryAlerts) low battery alerts"
            )
            row(
                "Find my phone",
                "\(snapshot.device.findRequests) requests · \(snapshot.device.findRings) rang · \(snapshot.device.findFailures) failed · \(snapshot.device.findNoResponses) no response",
                tint: snapshot.device.findFailures + snapshot.device.findNoResponses > 0 ? .orange : nil
            )
        }
    }

    private var eventsSection: some View {
        Section("Recent Events") {
            if snapshot.events.isEmpty {
                Text("No events yet")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(Array(snapshot.events.reversed().enumerated()), id: \.offset) { _, event in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text(event.date, style: .time)
                            .font(.callout.monospacedDigit())
                            .foregroundStyle(.secondary)
                            .frame(width: 76, alignment: .leading)
                        Text(event.kind.rawValue)
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1)
                            .foregroundStyle(event.kind == .state ? Color.secondary : Color.orange)
                            .background(Capsule().fill((event.kind == .state ? Color.secondary : Color.orange).opacity(0.14)))
                            .frame(width: 84, alignment: .leading)
                        Text(event.message)
                            .font(.callout)
                            .textSelection(.enabled)
                        Spacer(minLength: 0)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }

    // MARK: Helpers

    private func row(_ label: String, _ value: String, monospaced: Bool = false, tint: Color? = nil) -> some View {
        LabeledContent(label) {
            Text(value)
                .font(monospaced ? .system(.body, design: .monospaced) : .body)
                .foregroundStyle(tint ?? .secondary)
                .multilineTextAlignment(.trailing)
                .textSelection(.enabled)
        }
    }

    private func traffic(_ counters: TrafficCounters) -> String {
        "\(counters.envelopesIn) in (\(DiagnosticsReport.bytes(counters.bytesIn))) · \(counters.envelopesOut) out (\(DiagnosticsReport.bytes(counters.bytesOut)))"
    }

    private func ago(_ date: Date) -> String {
        "\(DiagnosticsReport.duration(snapshot.capturedAt.timeIntervalSince(date))) ago"
    }
}
