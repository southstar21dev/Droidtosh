package dev.southstar21.droidtosh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.diagnostics.DiagnosticsReport
import dev.southstar21.droidtosh.diagnostics.DiagnosticsSnapshot
import dev.southstar21.droidtosh.diagnostics.EventKind
import dev.southstar21.droidtosh.ui.theme.DroidIcons

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    snapshot: DiagnosticsSnapshot?,
    actions: AppActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                subtitle = { Text(stringResource(R.string.diagnostics_subtitle)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.diagnostics_back))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = actions.copyDiagnosticsReport,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(DroidIcons.Copy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.diagnostics_copy_report))
                }
                FilledTonalButton(
                    onClick = actions.shareDiagnosticsLogs,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.diagnostics_share_logs))
                }
                OutlinedButton(
                    onClick = actions.reconnectNow,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.diagnostics_reconnect))
                }
            }
            if (snapshot == null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
                    LoadingIndicator(Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.diagnostics_loading))
                }
            } else {
                DiagnosticsContent(snapshot)
            }
            Text(
                text = stringResource(R.string.diagnostics_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun DiagnosticsContent(s: DiagnosticsSnapshot) {
    val now = s.takenAtMs
    Section("Connection") {
        Stat("State", "${s.state} · ${DiagnosticsReport.duration(now - s.stateSinceMs)}")
        Stat("Mode", s.endpoint?.mode ?: "—")
        Stat("Session path", s.sessionPath ?: "—")
        Stat("Relay", DiagnosticsReport.relayText(s.endpoint))
        Stat("Relay fingerprint", s.endpoint?.relayFingerprint?.let { "$it…" } ?: "—")
        Stat("Protocol", s.negotiatedVersion?.let { "v$it" } ?: "—")
        Stat("Capabilities", s.capabilities.joinToString(", ").ifEmpty { "—" })
        Stat("Session uptime", s.sessionStartedAtMs?.let { DiagnosticsReport.duration(now - it) } ?: "—")
        Stat("Reconnects", DiagnosticsReport.reconnectsText(s))
        Stat("Push token", if (s.pushTokenPresent) "present" else "none")
        Stat("Last push", s.lastPushAtMs?.let { "${DiagnosticsReport.duration(now - it)} ago" } ?: "never")
    }
    Section("Background") {
        Stat("Battery optimization", DiagnosticsReport.batteryOptimizationText(s.batteryOptimizationIgnored))
        Stat("Display over other apps", DiagnosticsReport.overlayText(s.overlayAllowed))
    }
    Section("Round trip") {
        val rtt = s.rtt
        if (rtt == null) {
            Stat("RTT", "no samples yet")
        } else {
            Stat("Last", "${rtt.lastMs} ms")
            Stat("Avg / min / max", "${rtt.avgMs} / ${rtt.minMs} / ${rtt.maxMs} ms")
            Stat("Samples", rtt.samples.toString())
        }
    }
    Section("Traffic") {
        Stat("Session", DiagnosticsReport.traffic(s.sessionTraffic))
        Stat("Total", DiagnosticsReport.traffic(s.totalTraffic))
        Stat("Frames rejected", s.framesRejected.toString())
        s.inboundByFeature.forEach { (feature, count) ->
            val dropped = s.droppedByFeature[feature] ?: 0L
            Stat(feature.name.lowercase().replaceFirstChar(Char::titlecase), "$count in · $dropped dropped")
        }
    }
    Section("Reliable delivery") {
        val r = s.reliable
        if (r == null) {
            Stat("Stream", "inactive")
        } else {
            Stat("Outbox", r.outboxSize.toString())
            Stat("Evicted / expired", "${r.evicted} / ${r.expired}")
            Stat("Gaps / duplicates", "${r.gaps} / ${r.duplicates}")
            Stat("Retransmits", r.retransmits.toString())
        }
    }
    Section("Clipboard") {
        Stat("Sent / received", "${s.clipboard.sent} / ${s.clipboard.received}")
        Stat("Rejected / interrupted", "${s.clipboard.rejected} / ${s.clipboard.interrupted}")
    }
    Section("Files") {
        Stat("Sent / received", "${s.files.sent} / ${s.files.received}")
        Stat("Failed / resumed", "${s.files.failed} / ${s.files.resumed}")
    }
    Section("Phone status") {
        Stat("Status sent", s.device.statusSent.toString())
        Stat("Finds started / stopped / failed", "${s.device.findsStarted} / ${s.device.findsStopped} / ${s.device.findsFailed}")
    }
    Section("Notifications") {
        val n = s.notifications
        Stat("Forwarded", n.forwarded.toString())
        Stat("Muted / VoIP", "${n.suppressedMuted} / ${n.classifiedVoip}")
        Stat("Text truncated", n.textTruncated.toString())
        Stat(
            "Last snapshot",
            "${n.lastSnapshotRecords} · ${DiagnosticsReport.bytes(n.lastSnapshotBytes.toLong())}",
        )
        Stat("Snapshot drops", "${n.snapshotIconsDropped} icons · ${n.snapshotRecordsDropped} records")
    }
    Section("Recent events") {
        if (s.events.isEmpty()) {
            Text("None yet", style = MaterialTheme.typography.bodyMedium)
        }
        s.events.asReversed().forEach { event ->
            Text(
                text = "${DiagnosticsReport.clock(event.atMs)}  ${event.message}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (event.kind == EventKind.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 8.dp)
                .semantics { heading() },
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.58f),
        )
    }
}
