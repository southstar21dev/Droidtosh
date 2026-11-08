package dev.southstar21.droidtosh.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Strips secrets from free text (event messages, logcat) before it leaves the device. */
class Redactor(secrets: Collection<String>) {
    // Longest first, so a secret containing another is replaced whole.
    private val secrets = secrets.filter { it.length >= MIN_SECRET_LENGTH }.distinct().sortedByDescending { it.length }

    fun redact(text: String): String {
        var out = text
        for (secret in secrets) out = out.replace(secret, REDACTED)
        // Anything shaped like a fingerprint (64 hex) keeps only its first 8 characters.
        out = HEX64.replace(out) { it.value.take(8) + "…" }
        // Room IDs and base64url keys: 43+ chars of base64url alphabet.
        out = BASE64URL_TOKEN.replace(out, REDACTED)
        return out
    }

    companion object {
        const val REDACTED = "[redacted]"
        private const val MIN_SECRET_LENGTH = 8
        private val HEX64 = Regex("\\b[0-9a-fA-F]{64}\\b")
        private val BASE64URL_TOKEN = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43,}(?![A-Za-z0-9_-])")
    }
}

data class ReportHeader(val appVersion: String, val androidVersion: String, val device: String)

/** Plain-text diagnostics report. Built only from a [DiagnosticsSnapshot], which holds no secrets. */
object DiagnosticsReport {
    fun relayText(endpoint: Endpoint?): String =
        endpoint?.host?.let { host -> endpoint.port?.let { "$host:$it" } ?: host } ?: "—"

    fun format(snapshot: DiagnosticsSnapshot, header: ReportHeader, redactor: Redactor): String = buildString {
        val now = snapshot.takenAtMs
        appendLine("Droidtosh diagnostics — ${timestamp(now)}")
        appendLine("App ${header.appVersion} · Android ${header.androidVersion} · ${header.device}")
        appendLine()

        section("Connection")
        line("State", "${snapshot.state} (for ${duration(now - snapshot.stateSinceMs)})")
        val endpoint = snapshot.endpoint
        line("Mode", endpoint?.mode ?: "—")
        line("Session path", snapshot.sessionPath ?: "—")
        line("Relay", relayText(endpoint))
        line("Relay fingerprint", endpoint?.relayFingerprint?.let { "$it…" } ?: "—")
        line("Local address", endpoint?.local ?: "—")
        line("Mac fingerprint", endpoint?.macFingerprint?.let { "$it…" } ?: "—")
        line("Protocol", snapshot.negotiatedVersion?.let { "v$it" } ?: "—")
        line("Capabilities", snapshot.capabilities.joinToString(", ").ifEmpty { "—" })
        line("Session uptime", snapshot.sessionStartedAtMs?.let { duration(now - it) } ?: "—")
        line("Reconnects", reconnectsText(snapshot))
        line("Push token", if (snapshot.pushTokenPresent) "present" else "none")
        line("Last push", snapshot.lastPushAtMs?.let { "${duration(now - it)} ago" } ?: "never")
        appendLine()

        section("Background")
        line("Battery optimization", batteryOptimizationText(snapshot.batteryOptimizationIgnored))
        line("Display over other apps", overlayText(snapshot.overlayAllowed))
        appendLine()

        section("Round trip")
        val rtt = snapshot.rtt
        if (rtt == null) {
            line("RTT", "no samples")
        } else {
            line("RTT", "last ${rtt.lastMs} ms · avg ${rtt.avgMs} · min ${rtt.minMs} · max ${rtt.maxMs} (${rtt.samples} samples)")
        }
        appendLine()

        section("Traffic")
        line("Session", traffic(snapshot.sessionTraffic))
        line("Total", traffic(snapshot.totalTraffic))
        line("Frames rejected", snapshot.framesRejected.toString())
        line("Inbound by feature", featureMap(snapshot.inboundByFeature))
        line("Dropped (inbox full)", featureMap(snapshot.droppedByFeature))
        appendLine()

        section("Reliable delivery")
        val reliable = snapshot.reliable
        if (reliable == null) {
            line("Stream", "inactive")
        } else {
            line("Outbox", reliable.outboxSize.toString())
            line("Evicted / expired", "${reliable.evicted} / ${reliable.expired}")
            line("Gaps / duplicates", "${reliable.gaps} / ${reliable.duplicates}")
            line("Retransmits", reliable.retransmits.toString())
        }
        appendLine()

        section("Clipboard")
        val clip = snapshot.clipboard
        line("Sent / received", "${clip.sent} / ${clip.received}")
        line("Rejected / interrupted", "${clip.rejected} / ${clip.interrupted}")
        appendLine()

        section("Files")
        val files = snapshot.files
        line("Sent / received", "${files.sent} / ${files.received}")
        line("Failed / resumed", "${files.failed} / ${files.resumed}")
        appendLine()

        section("Phone status")
        val device = snapshot.device
        line("Status sent", device.statusSent.toString())
        line("Finds started / stopped / failed", "${device.findsStarted} / ${device.findsStopped} / ${device.findsFailed}")
        appendLine()

        section("Notifications")
        val n = snapshot.notifications
        line("Forwarded", n.forwarded.toString())
        line("Suppressed (muted / VoIP)", "${n.suppressedMuted} / ${n.classifiedVoip}")
        line("Text truncated", n.textTruncated.toString())
        line("Last snapshot", "${n.lastSnapshotRecords} records, ${bytes(n.lastSnapshotBytes.toLong())}")
        line("Snapshot drops (icons / records)", "${n.snapshotIconsDropped} / ${n.snapshotRecordsDropped}")
        appendLine()

        section("Recent events")
        if (snapshot.events.isEmpty()) appendLine("  (none)")
        snapshot.events.forEach { event ->
            appendLine("  ${clock(event.atMs)}  ${event.kind.name.padEnd(7)} ${redactor.redact(event.message)}")
        }
    }.let(redactor::redact)

    fun reconnectsText(snapshot: DiagnosticsSnapshot): String {
        if (snapshot.reconnects == 0L) return "0"
        val breakdown = snapshot.reconnectReasons.entries
            .sortedByDescending { it.value }
            .joinToString(", ") { "${it.key.label} ${it.value}" }
        return "${snapshot.reconnects} ($breakdown)"
    }

    fun batteryOptimizationText(ignored: Boolean?): String = when (ignored) {
        null -> "—"
        true -> "off (unrestricted)"
        false -> "on (may pause the connection)"
    }

    fun overlayText(allowed: Boolean?): String = when (allowed) {
        null -> "—"
        true -> "allowed (can answer app calls while unlocked)"
        false -> "not allowed (app calls can't be answered while unlocked)"
    }

    fun traffic(counts: TrafficCounts): String =
        "in ${counts.envelopesIn} (${bytes(counts.bytesIn)}) · out ${counts.envelopesOut} (${bytes(counts.bytesOut)})"

    fun bytes(value: Long): String = when {
        value >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024))
        value >= 1024 -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        else -> "$value B"
    }

    fun duration(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0)) / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            seconds < 86_400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            else -> "${seconds / 86_400}d ${(seconds % 86_400) / 3600}h"
        }
    }

    private fun featureMap(map: Map<*, Long>): String =
        map.entries.joinToString(", ") { "${it.key.toString().lowercase()} ${it.value}" }.ifEmpty { "—" }

    private fun StringBuilder.section(title: String) = appendLine("[$title]")

    private fun StringBuilder.line(label: String, value: String) = appendLine("  $label: $value")

    private fun timestamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(ms))

    fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
}
