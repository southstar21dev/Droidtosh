package dev.southstar21.droidtosh.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.notifications.SensitiveNotifications

/** One-time hint shown after Android hid a verification code from Droidtosh. */
@Composable
fun SensitiveNotificationsHint(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val visible by remember { SensitiveNotifications.hintVisible(context) }.collectAsState()
    if (!visible) return
    val command = SensitiveNotifications.adbCommand(context.packageName)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.sensitive_hint_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.sensitive_hint_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(command, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { SensitiveNotifications.dismissHint(context) }) {
                    Text(stringResource(R.string.sensitive_hint_dismiss))
                }
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("adb command", command))
                }) {
                    Text(stringResource(R.string.sensitive_hint_copy))
                }
            }
        }
    }
}
