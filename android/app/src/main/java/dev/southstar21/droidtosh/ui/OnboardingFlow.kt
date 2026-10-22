package dev.southstar21.droidtosh.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.transport.PairingVersionException
import dev.southstar21.droidtosh.ui.components.DevicesIllustration
import dev.southstar21.droidtosh.ui.components.ScanIllustration
import dev.southstar21.droidtosh.ui.components.ShapeIcon
import dev.southstar21.droidtosh.ui.components.pressScale
import dev.southstar21.droidtosh.ui.theme.DroidIcons
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private const val STEP_COUNT = 3

@Composable
fun OnboardingFlow(state: AppState) {
    val repairNeeded by state.repairNeeded.collectAsStateWithLifecycle()
    // Someone re-pairing after an update has been through onboarding already; go straight to scanning.
    var step by rememberSaveable { mutableIntStateOf(if (repairNeeded) STEP_COUNT - 1 else 0) }
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

    BackHandler(enabled = step > 0) { step-- }

    OnboardingContent(
        step = step,
        onStepChange = { step = it },
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
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingContent(
    step: Int,
    onStepChange: (Int) -> Unit,
    permissions: PermissionStatus,
    repairNeeded: Boolean,
    onRequestPermissions: (Array<String>) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onScan: () -> Unit,
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
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    IconButton(onClick = { onStepChange(step - 1) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back),
                        )
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                Spacer(Modifier.weight(1f))
                StepIndicator(step = step)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
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
                    0 -> WelcomeStep(onGetStarted = { onStepChange(1) })
                    1 -> PermissionsStep(
                        status = permissions,
                        onRequestPermissions = onRequestPermissions,
                        onOpenNotificationAccess = onOpenNotificationAccess,
                        onContinue = { onStepChange(2) },
                        onSkip = { onStepChange(2) },
                    )
                    else -> PairStep(repairNeeded = repairNeeded, onScan = onScan)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepIndicator(step: Int) {
    val label = stringResource(R.string.onboarding_step_indicator, step + 1, STEP_COUNT)
    Row(
        modifier = Modifier.semantics { contentDescription = label },
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
    centered: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = if (centered) Arrangement.Center else Arrangement.Top,
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

@Composable
private fun WelcomeStep(onGetStarted: () -> Unit) {
    StepPage(
        body = {
            DevicesIllustration()
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_tagline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeaturePill(Icons.Default.Notifications, stringResource(R.string.onboarding_pill_notifications))
                FeaturePill(Icons.Default.Call, stringResource(R.string.onboarding_pill_calls))
                FeaturePill(DroidIcons.Transfers, stringResource(R.string.onboarding_pill_files))
            }
        },
        actions = { BigButton(stringResource(R.string.onboarding_get_started), onGetStarted) },
    )
}

@Composable
private fun FeaturePill(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

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
        centered = false,
        body = {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_permissions_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_permissions_subtitle),
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
        if (granted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
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
                Text(
                    stringResource(R.string.permission_granted),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Text(
                    stringResource(R.string.fix_allow),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PairStep(repairNeeded: Boolean, onScan: () -> Unit) {
    StepPage(
        body = {
            ScanIllustration()
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.pair_title),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(20.dp))
            if (repairNeeded) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
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
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                PairInstruction(1, stringResource(R.string.pair_step_open))
                PairInstruction(2, stringResource(R.string.pair_step_show))
                PairInstruction(3, stringResource(R.string.pair_step_scan))
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

@Composable
private fun PairInstruction(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
