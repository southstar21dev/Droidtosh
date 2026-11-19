package dev.southstar21.droidtosh.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.transport.PairingVersionException
import dev.southstar21.droidtosh.ui.components.ShapeIcon
import dev.southstar21.droidtosh.ui.components.Tether
import dev.southstar21.droidtosh.ui.components.TetherState
import dev.southstar21.droidtosh.ui.components.pressScale
import dev.southstar21.droidtosh.ui.theme.DroidIcons

private const val STEP_COUNT = 2

/**
 * The guided setup: scan the Mac's code, then one permissions checklist. The step follows the
 * pairing, so a fresh scan moves on by itself and Back never unpairs.
 */
@Composable
fun OnboardingFlow(state: AppState) {
    val repairNeeded by state.repairNeeded.collectAsStateWithLifecycle()
    val isPaired by state.isPaired.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val permissionStatus by state.permissionStatus.collectAsStateWithLifecycle()

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { state.refreshPermissions() }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            runCatching { state.onPairingScanned(contents) }
                .onFailure { error ->
                    val message = (error as? PairingVersionException)?.message
                        ?: resources.getString(R.string.pair_invalid_qr)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.refreshPermissions() }

    OnboardingContent(
        step = if (isPaired) 1 else 0,
        permissions = permissionStatus,
        repairNeeded = repairNeeded,
        onRequestPermissions = { permissionsLauncher.launch(it) },
        onOpenNotificationAccess = {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        onScan = {
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(resources.getString(R.string.pair_scanner_prompt))
                    .setCaptureActivity(PortraitCaptureActivity::class.java)
            )
        },
        onFinish = state::finishSetup,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingContent(
    step: Int,
    permissions: PermissionStatus,
    repairNeeded: Boolean,
    onRequestPermissions: (Array<String>) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onScan: () -> Unit,
    onFinish: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepIndicator(step = step)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.onboarding_step_indicator, step + 1, STEP_COUNT),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val spec = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally(spec) { if (forward) it / 3 else -it / 3 } + fadeIn())
                        .togetherWith(slideOutHorizontally(spec) { if (forward) -it / 3 else it / 3 } + fadeOut())
                },
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 560.dp),
                label = "onboarding-step",
            ) { current ->
                when (current) {
                    0 -> ScanStep(repairNeeded = repairNeeded, onScan = onScan)
                    else -> PermissionsStep(
                        status = permissions,
                        onRequestPermissions = onRequestPermissions,
                        onOpenNotificationAccess = onOpenNotificationAccess,
                        onContinue = onFinish,
                        onSkip = onFinish,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepIndicator(step: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(STEP_COUNT) { index ->
            val width by animateDpAsState(
                targetValue = if (index == step) 32.dp else 10.dp,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "step-width",
            )
            val color by animateColorAsState(
                targetValue = if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "step-color",
            )
            Box(
                modifier = Modifier
                    .size(width = width, height = 10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** Page skeleton: scrollable body, pinned actions at the bottom. */
@Composable
private fun StepPage(
    body: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            content = body,
        )
        Spacer(Modifier.height(16.dp))
        actions()
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BigButton(text: String, onClick: () -> Unit, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ButtonDefaults.MediumContainerHeight),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.MediumIconSize))
            Spacer(Modifier.width(ButtonDefaults.MediumIconSpacing))
        }
        Text(text, style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
    }
}

/** Step 1: the welcome folded into the scan, a viewfinder with the Tether seeking inside. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScanStep(repairNeeded: Boolean, onScan: () -> Unit) {
    StepPage(
        body = {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_tagline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.setup_scan_title),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(20.dp))
            Viewfinder(onScan = onScan)
            Spacer(Modifier.height(16.dp))
            if (repairNeeded) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.pair_repair_needed), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                PairInstruction(1, MaterialShapes.Cookie4Sided, stringResource(R.string.pair_step_open))
                PairInstruction(2, MaterialShapes.Clover4Leaf, stringResource(R.string.pair_step_show))
                PairInstruction(3, MaterialShapes.Sunny, stringResource(R.string.pair_step_scan))
            }
        },
        actions = {
            BigButton(stringResource(R.string.pair_scan), onScan, icon = DroidIcons.QrCode)
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.pair_wifi_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/** A dark viewfinder card: corner marks, the Tether seeking, and the waiting line. Tapping scans. */
@Composable
private fun Viewfinder(onScan: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onScan,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .aspectRatio(1.15f)
            .pressScale(interaction, pressed = 0.97f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxSize(0.7f)
                    .border(
                        BorderStroke(3.dp, MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.35f)),
                        MaterialTheme.shapes.large,
                    ),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Tether(state = TetherState.Seeking, size = 88.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.setup_scan_waiting),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun PairInstruction(number: Int, polygon: RoundedPolygon, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center) {
            ShapeIcon(
                icon = DroidIcons.Spark,
                polygon = polygon,
                size = 36.dp,
                iconSize = 0.dp,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.secondaryContainer,
            )
            Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Step 2: one checklist; each row asks for its permission, and Continue or Skip ends the setup. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PermissionsStep(
    status: PermissionStatus,
    onRequestPermissions: (Array<String>) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    StepPage(
        body = {
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tether(state = TetherState.Handshake, size = 28.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.setup_paired), style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.onboarding_permissions_title),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.setup_permissions_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PermissionCard(
                    icon = Icons.Default.Notifications,
                    polygon = MaterialShapes.Cookie4Sided,
                    title = stringResource(R.string.permission_notifications_title),
                    description = stringResource(R.string.permission_notifications_description),
                    granted = status.notifications,
                    required = true,
                    onClick = { onRequestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
                )
                PermissionCard(
                    icon = Icons.Default.Settings,
                    polygon = MaterialShapes.SoftBurst,
                    title = stringResource(R.string.permission_notification_access_title),
                    description = stringResource(R.string.permission_notification_access_description),
                    granted = status.notificationAccess,
                    required = true,
                    onClick = onOpenNotificationAccess,
                )
                PermissionCard(
                    icon = Icons.Default.Phone,
                    polygon = MaterialShapes.Cookie7Sided,
                    title = stringResource(R.string.permission_phone_title),
                    description = stringResource(R.string.permission_phone_description),
                    granted = status.phone,
                    required = true,
                    onClick = { onRequestPermissions(PhonePermissions) },
                )
                PermissionCard(
                    icon = Icons.Default.Call,
                    polygon = MaterialShapes.Cookie9Sided,
                    title = stringResource(R.string.permission_call_log_title),
                    description = stringResource(R.string.permission_call_log_description),
                    granted = status.callLog,
                    required = false,
                    onClick = { onRequestPermissions(CallLogPermissions) },
                )
                PermissionCard(
                    icon = Icons.Default.Call,
                    polygon = MaterialShapes.Cookie9Sided,
                    title = stringResource(R.string.permission_outgoing_calls_title),
                    description = stringResource(R.string.permission_outgoing_calls_description),
                    granted = status.outgoingCalls,
                    required = false,
                    onClick = { onRequestPermissions(OutgoingCallPermissions) },
                )
                PermissionCard(
                    icon = Icons.Default.Person,
                    polygon = MaterialShapes.Clover4Leaf,
                    title = stringResource(R.string.permission_contacts_title),
                    description = stringResource(R.string.permission_contacts_description),
                    granted = status.contacts,
                    required = false,
                    onClick = { onRequestPermissions(arrayOf(Manifest.permission.READ_CONTACTS)) },
                )
            }
            Spacer(Modifier.height(8.dp))
        },
        actions = {
            BigButton(stringResource(R.string.onboarding_continue), onContinue, enabled = status.requiredGranted)
            TextButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.onboarding_skip)) }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PermissionCard(
    icon: ImageVector,
    polygon: RoundedPolygon,
    title: String,
    description: String,
    granted: Boolean,
    required: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        if (granted) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "permission-container",
    )
    val stateLabel = stringResource(if (granted) R.string.permission_granted else R.string.permission_not_granted)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = container,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressed = 0.97f)
            .semantics { contentDescription = "$title. $description. $stateLabel" },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShapeIcon(
                icon = if (granted) Icons.Default.Check else icon,
                polygon = polygon,
                size = 48.dp,
                containerColor = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (granted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    if (!required) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.onboarding_optional),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (granted) {
                Text(stringResource(R.string.permission_granted), style = MaterialTheme.typography.labelLarge)
            } else {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Text(
                        stringResource(R.string.fix_allow),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
