package dev.southstar21.droidtosh.notifications

import android.app.KeyguardManager
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.telecom.TelecomManager
import android.service.notification.StatusBarNotification
import dev.southstar21.droidtosh.calls.CallManager
import dev.southstar21.droidtosh.calls.CallNotificationClassifier
import dev.southstar21.droidtosh.calls.PhoneAccountsProvider
import dev.southstar21.droidtosh.diagnostics.Diagnostics
import dev.southstar21.droidtosh.launch.AppVisibility
import dev.southstar21.droidtosh.launch.BackgroundLauncher
import dev.southstar21.droidtosh.launch.OverlayPermission
import dev.southstar21.droidtosh.calls.VoipAction
import dev.southstar21.droidtosh.calls.VoipActionMatcher
import dev.southstar21.droidtosh.calls.VoipCallSignal
import dev.southstar21.droidtosh.calls.VoipProfiles
import dev.southstar21.droidtosh.protocol.v1.ActionResult
import dev.southstar21.droidtosh.protocol.v1.AppIcon
import dev.southstar21.droidtosh.protocol.v1.AppsList
import dev.southstar21.droidtosh.protocol.v1.CallAction
import dev.southstar21.droidtosh.protocol.v1.CallActionType
import dev.southstar21.droidtosh.protocol.v1.CallDirection
import dev.southstar21.droidtosh.protocol.v1.CallKind
import dev.southstar21.droidtosh.protocol.v1.CallPhase
import dev.southstar21.droidtosh.protocol.v1.CallState
import dev.southstar21.droidtosh.protocol.v1.Envelope
import dev.southstar21.droidtosh.protocol.v1.NotificationRecord
import dev.southstar21.droidtosh.protocol.v1.NotificationPosted
import dev.southstar21.droidtosh.protocol.v1.NotificationRemoved
import dev.southstar21.droidtosh.protocol.v1.NotificationUpdated
import dev.southstar21.droidtosh.protocol.v1.RelayStatus
import dev.southstar21.droidtosh.protocol.v1.SnapshotReply
import dev.southstar21.droidtosh.transport.ConnectionService
import dev.southstar21.droidtosh.transport.InboundFeature
import dev.southstar21.droidtosh.transport.TransportHub
import dev.southstar21.droidtosh.transport.newEnvelope
import com.google.protobuf.ByteString
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object RelayState {
    private val mutableEnabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    fun setEnabled(value: Boolean) { mutableEnabled.value = value }
}

class RelayNotificationListenerService : NotificationListenerService() {
    private companion object {
        const val ANSWER_GRACE_MS = 1_500L

        /** How long an answered VoIP call may take to show as in progress. */
        const val ACCEPT_VERIFY_MS = 2_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null
    private var muteJob: Job? = null
    private var relayStatusJob: Job? = null
    private val settingsStore by lazy { NotificationSettingsStore(applicationContext) }
    private val filter by lazy { NotificationFilter({ mutedSnapshot }, packageName) }
    private val executor by lazy {
        NotificationActionExecutor(
            activeNotifications = { activeNotificationList() },
            sender = AndroidPendingIntentSender(this),
        )
    }
    private val appLabels = ConcurrentHashMap<String, String>()
    private val seenKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val truncations = TruncationCounter { Diagnostics.recorder.onTextTruncated() }
    private val voipMatcher by lazy { VoipActionMatcher(VoipProfiles.load(this), VoipProfiles.generic(this)) }
    private val defaultDialerPackage: String? by lazy {
        runCatching {
            applicationContext.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
    }
    private val callClassifier by lazy {
        CallNotificationClassifier(
            matcher = voipMatcher,
            defaultDialerPackage = defaultDialerPackage,
        )
    }
    private val voipCalls = ConcurrentHashMap<String, VoipCallSignal>()
    private val voipActive: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val voipStartedAt = ConcurrentHashMap<String, Long>()
    /** Ringing calls whose notification just went away, by package, until they count as missed. */
    private val recentlyRinging = ConcurrentHashMap<String, VoipCallSignal>()
    private val sentIcons: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var mutedSnapshot: Set<String> = emptySet()

    override fun onListenerConnected() {
        RelayState.setEnabled(true)
        sendRelayStatus(true)
        CallManager.dialerNotificationAction = { role -> dialerNotificationAction(role) }
        CallManager.voipCallInProgress = { voipCalls.isNotEmpty() }
        if (muteJob?.isActive != true) {
            muteJob = scope.launch { settingsStore.mutedPackages.collect { mutedSnapshot = it } }
        }
        if (relayStatusJob?.isActive != true) {
            relayStatusJob = scope.launch {
                TransportHub.isConnected.collect { connected ->
                    if (connected) {
                        sentIcons.clear()
                        sendRelayStatus(RelayState.enabled.value)
                    }
                }
            }
        }
        if (collectJob?.isActive != true) {
            collectJob = scope.launch { TransportHub.incoming(InboundFeature.NOTIFICATIONS).collect(::handleRequest) }
        }
        if (TransportHub.isConnected.value) {
            sendSnapshot()
        }
    }

    override fun onListenerDisconnected() {
        RelayState.setEnabled(false)
        sendRelayStatus(false)
        collectJob?.cancel()
        collectJob = null
    }

    override fun onDestroy() {
        RelayState.setEnabled(false)
        relayStatusJob?.cancel()
        relayStatusJob = null
        CallManager.dialerNotificationAction = null
        CallManager.voipCallInProgress = null
        sendRelayStatus(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        android.util.Log.d("DroidsyncCalls", "posted pkg=${sbn.packageName} key=${sbn.key} category=${notification.category}")
        val signal = callClassifier.classify(sbn.packageName, notification)
        if (signal != null) {
            Diagnostics.recorder.onNotificationVoip()
            handleVoipCallPosted(sbn.key, signal)
            return
        }
        if (sbn.packageName == defaultDialerPackage && CallManager.hasCellularCall()) {
            // The dialer starts its call timer when a call is answered (AOSP Dialer:
            // setUsesChronometer only in the ACTIVE state), the one sign that a call dialled on
            // the phone has connected.
            if (notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) {
                CallManager.onDialerCallTimerShown()
            }
            return
        }
        if (!filter.shouldForward(sbn.packageName, isGroupSummary(notification))) {
            if (sbn.packageName in mutedSnapshot) Diagnostics.recorder.onNotificationMuted()
            return
        }
        val record = maybeWithIcon(
            NotificationRecordMapper.toRecord(
                packageName = sbn.packageName,
                appLabel = labelFor(sbn.packageName),
                key = sbn.key,
                notification = notification,
                postTimeMs = sbn.postTime,
                onTruncated = { truncations.onTruncated(sbn.key) },
            )
        )
        val isUpdate = !seenKeys.add(sbn.key)
        val sent = TransportHub.send(
            if (isUpdate) {
                newEnvelope { setNotificationUpdated(NotificationUpdated.newBuilder().setRecord(record)) }
            } else {
                newEnvelope { setNotificationPosted(NotificationPosted.newBuilder().setRecord(record)) }
            }
        )
        if (sent) Diagnostics.recorder.onNotificationForwarded()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) {
            if (sbn.id == ConnectionService.NOTIFICATION_ID) {
                ConnectionService.repostNotification()
            }
            return
        }
        if (NotificationFilter.isSystemNoise(sbn.packageName)) return
        seenKeys.remove(sbn.key)
        truncations.onRemoved(sbn.key)
        val signal = voipCalls.remove(sbn.key)
        val startedAtMs = voipStartedAt.remove(sbn.key) ?: 0L
        if (signal != null) {
            android.util.Log.d("DroidsyncCalls", "voip call removed key=${sbn.key}")
            val wasActive = voipActive.remove(sbn.key)
            if (wasActive) {
                sendCallState(
                    callId = voipCallId(sbn.key),
                    packageName = signal.packageName,
                    phase = CallPhase.CALL_PHASE_ENDED,
                    contactName = signal.title,
                    startedAtMs = startedAtMs,
                )
            } else {
                // Answering on the phone removes the ringing notification and posts an ongoing one,
                // possibly in that order. Wait briefly so the answered call isn't reported as missed.
                recentlyRinging[signal.packageName] = signal
                scope.launch {
                    kotlinx.coroutines.delay(ANSWER_GRACE_MS)
                    recentlyRinging.remove(signal.packageName, signal)
                    if (hasActiveCall(signal.packageName)) return@launch
                    sendCallState(
                        callId = voipCallId(sbn.key),
                        packageName = signal.packageName,
                        phase = CallPhase.CALL_PHASE_MISSED,
                        contactName = signal.title,
                        startedAtMs = startedAtMs,
                    )
                }
            }
        }
        TransportHub.send(newEnvelope {
            setNotificationRemoved(NotificationRemoved.newBuilder().setKey(sbn.key))
        })
    }

    private fun handleRequest(envelope: Envelope) {
        runCatching {
            when {
                envelope.hasNotificationAction() -> {
                    val request = envelope.notificationAction
                    val outcome = executor.execute(
                        key = request.key,
                        actionIndex = request.actionIndex,
                        replyText = request.replyText.ifEmpty { null },
                    )
                    TransportHub.send(newEnvelope {
                        setActionResult(
                            ActionResult.newBuilder()
                                .setRequestId(request.requestId)
                                .setOk(outcome.ok)
                                .setErrorCode(outcome.errorCode)
                                .setMessage(outcome.message)
                        )
                    })
                }
                envelope.hasNotificationDismiss() ->
                    runCatching { cancelNotification(envelope.notificationDismiss.key) }
                envelope.hasSnapshotRequest() -> sendSnapshot()
                envelope.hasMuteListSet() -> scope.launch {
                    runCatching {
                        settingsStore.setMutedPackages(envelope.muteListSet.packagesList.toSet())
                    }
                }
                // Loading every app label is slow; keep it off the collector so other envelopes keep flowing.
                envelope.hasAppsListRequest() -> scope.launch {
                    runCatching {
                        TransportHub.send(newEnvelope {
                            setAppsList(
                                AppsList.newBuilder().addAllApps(
                                    InstalledAppsProvider(packageManager).launchableApps()
                                )
                            )
                        })
                    }
                }
                envelope.hasAppIcon() && envelope.appIcon.png.isEmpty() -> scope.launch {
                    val pkg = envelope.appIcon.packageName
                    val png = AppIconProvider(packageManager).iconPng(pkg)
                    android.util.Log.d("DroidsyncCalls", "icon request pkg=$pkg bytes=${png?.size ?: 0}")
                    if (png != null) {
                        TransportHub.send(newEnvelope {
                            setAppIcon(
                                AppIcon.newBuilder()
                                    .setPackageName(pkg)
                                    .setPng(ByteString.copyFrom(png))
                            )
                        })
                    }
                }
                envelope.hasCallAction() ->
                    handleVoipCallAction(envelope.callAction)
                envelope.hasOpenAppRequest() ->
                    openApp(envelope.openAppRequest.packageName)
            }
        }
    }

    private fun sendRelayStatus(enabled: Boolean) {
        TransportHub.send(newEnvelope {
            setRelayStatus(RelayStatus.newBuilder().setNotificationsEnabled(enabled))
        })
    }

    private fun voipCallId(key: String) = "voip:$key"

    private fun handleVoipCallPosted(key: String, signal: VoipCallSignal) {
        android.util.Log.d("DroidsyncCalls", "voip call posted key=$key phase=${if (signal.isOngoing) "active" else "ringing"} actions=${signal.actionIndices}")
        var effective = signal
        val previous = voipCalls[key]
        if (effective.title.isEmpty() && previous?.title?.isNotEmpty() == true) {
            effective = effective.copy(title = previous.title)
        }
        val justRemoved = recentlyRinging[effective.packageName]
        if (effective.title.isEmpty() && justRemoved?.title?.isNotEmpty() == true) {
            effective = effective.copy(title = justRemoved.title)
        }
        val answered = CallNotificationClassifier.phaseFor(effective, voipActive.contains(key)) ==
            CallPhase.CALL_PHASE_ACTIVE
        for (otherKey in voipCalls.keys.toList()) {
            if (otherKey == key) continue
            // Another app's call is independent; only reconcile this app's own earlier call notification.
            if (voipCalls[otherKey]?.packageName != effective.packageName) continue
            val other = voipCalls.remove(otherKey) ?: continue
            val wasActive = voipActive.remove(otherKey)
            val startedAt = voipStartedAt.remove(otherKey)
            // The same app's earlier notification for this call: still in progress, or just
            // answered (its ringing notification replaced by the ongoing one).
            val continuation = (wasActive || answered) && other.packageName == effective.packageName
            if (continuation) {
                if (effective.title.isEmpty() && other.title.isNotEmpty()) {
                    effective = effective.copy(title = other.title)
                }
                // An answered call's duration starts now, not when it started ringing.
                if (wasActive && startedAt != null && !voipStartedAt.containsKey(key)) {
                    voipStartedAt[key] = startedAt
                }
            } else {
                sendCallState(
                    callId = voipCallId(otherKey),
                    packageName = other.packageName,
                    phase = if (wasActive) CallPhase.CALL_PHASE_ENDED else CallPhase.CALL_PHASE_MISSED,
                    contactName = other.title,
                )
            }
        }
        voipCalls.put(key, effective)
        val wasActive = voipActive.contains(key)
        val phase = CallNotificationClassifier.phaseFor(effective, wasActive)
        if (phase == CallPhase.CALL_PHASE_ACTIVE) {
            voipActive.add(key)
        } else {
            voipActive.remove(key)
        }
        sendCallState(
            callId = voipCallId(key),
            packageName = effective.packageName,
            phase = phase,
            contactName = effective.title,
            startedAtMs = voipStartedAt.computeIfAbsent(key) { System.currentTimeMillis() },
        )
    }

    private fun sendCallState(
        callId: String,
        packageName: String,
        phase: CallPhase,
        contactName: String,
        startedAtMs: Long = 0L,
    ) {
        android.util.Log.d("DroidsyncCalls", "send state id=$callId pkg=$packageName phase=$phase")
        TransportHub.send(newEnvelope {
            setCallState(
                CallState.newBuilder()
                    .setCallId(callId)
                    .setKind(CallKind.CALL_KIND_VOIP)
                    .setPackageName(packageName)
                    .setDirection(CallDirection.CALL_DIRECTION_INCOMING)
                    .setPhase(phase)
                    .setContactName(contactName)
                    .setStartedAtMs(startedAtMs)
            )
        })
    }

    private fun handleVoipCallAction(request: CallAction) {
        val key = request.callId.removePrefix("voip:")
        val signal = voipCalls[key]
        val role = when (request.action) {
            CallActionType.CALL_ACTION_ACCEPT -> VoipAction.ACCEPT
            CallActionType.CALL_ACTION_DECLINE -> VoipAction.DECLINE
            CallActionType.CALL_ACTION_END -> VoipAction.END
            else -> null
        }
        val acceptIndex = signal?.actionIndices?.get(VoipAction.ACCEPT)
        val fallbackIndex = signal?.actions?.indices?.firstOrNull { it != acceptIndex }
        val index = signal?.let { active ->
            when (role) {
                null -> null
                VoipAction.ACCEPT -> active.actionIndices[VoipAction.ACCEPT]
                VoipAction.DECLINE ->
                    active.actionIndices[VoipAction.DECLINE]
                        ?: active.actionIndices[VoipAction.END]
                        ?: fallbackIndex
                VoipAction.END ->
                    active.actionIndices[VoipAction.END]
                        ?: active.actionIndices[VoipAction.DECLINE]
                        ?: fallbackIndex
            }
        }
        val outcome = if (signal == null) {
            ActionOutcome(false, "call_not_found", "call is no longer active")
        } else if (index == null) {
            ActionOutcome(false, "no_matching_action", "no matching action for this call")
        } else {
            if (role == VoipAction.ACCEPT &&
                VoipAnswerCheck.likelyBlocked(mayStartActivities(), keyguardLocked())
            ) {
                // Send it anyway: an app whose Answer starts no activity still answers.
                Diagnostics.recorder.onInfo("voip answer (${signal.packageName}): phone unlocked, may be blocked")
            }
            executor.execute(key, index, null)
        }
        if (outcome.ok && role == VoipAction.ACCEPT && signal != null) {
            // PendingIntent.send succeeds even when Android drops the activity it starts, so only
            // the call showing as in progress tells; report to the Mac once that's known.
            val packageName = signal.packageName
            scope.launch {
                kotlinx.coroutines.delay(ACCEPT_VERIFY_MS)
                val answered = voipActive.contains(key) || hasActiveCall(packageName)
                val checked = VoipAnswerCheck.outcome(
                    answered = answered,
                    mayStartActivities = mayStartActivities(),
                    keyguardLocked = keyguardLocked(),
                    appLabel = labelFor(packageName),
                )
                if (!answered) {
                    android.util.Log.d("DroidsyncCalls", "accept had no effect (${checked.errorCode}), opening $packageName")
                    Diagnostics.recorder.onInfo("voip answer (${packageName}): no effect, ${checked.errorCode}")
                    openApp(packageName)
                }
                sendActionResult(request.requestId, checked)
            }
            return
        }
        sendActionResult(request.requestId, outcome)
    }

    private fun sendActionResult(requestId: String, outcome: ActionOutcome) {
        TransportHub.send(newEnvelope {
            setActionResult(
                ActionResult.newBuilder()
                    .setRequestId(requestId)
                    .setOk(outcome.ok)
                    .setErrorCode(outcome.errorCode)
                    .setMessage(outcome.message)
            )
        })
    }

    /** Droidtosh may start activities from here: one of its windows is visible, or it may draw over apps. */
    private fun mayStartActivities(): Boolean = AppVisibility.visible || OverlayPermission.granted(this)

    private fun keyguardLocked(): Boolean =
        runCatching { getSystemService(KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(false)

    private fun hasActiveCall(packageName: String): Boolean =
        voipActive.any { voipCalls[it]?.packageName == packageName }

    private fun openApp(packageName: String) {
        BackgroundLauncher.openApp(this, packageName, labelFor(packageName))
    }

    /** Taps Answer, Decline or End on the default dialer's call notification, when it has one. */
    private fun dialerNotificationAction(role: VoipAction): Boolean {
        val dialer = defaultDialerPackage ?: return false
        val sbn = activeNotifications.orEmpty().firstOrNull { it.packageName == dialer } ?: return false
        val actions = sbn.notification.actions.orEmpty().toList()
        val indices = voipMatcher.match(dialer, actions)
        val index = when (role) {
            VoipAction.ACCEPT -> indices[VoipAction.ACCEPT]
            VoipAction.DECLINE -> indices[VoipAction.DECLINE] ?: indices[VoipAction.END]
            VoipAction.END -> indices[VoipAction.END] ?: indices[VoipAction.DECLINE]
        } ?: return false
        return executor.execute(sbn.key, index, null).ok
    }

    private fun maybeWithIcon(record: NotificationRecord): NotificationRecord {
        if (!sentIcons.add(record.packageName)) return record
        val png = AppIconProvider(packageManager).iconPng(record.packageName)
        if (png == null) {
            sentIcons.remove(record.packageName)
            return record
        }
        return record.toBuilder().setIconPng(ByteString.copyFrom(png)).build()
    }

    private fun sendSnapshot() {
        val candidates = activeNotifications.orEmpty().asSequence()
            .filter { sbn -> callClassifier.classify(sbn.packageName, sbn.notification) == null }
            .filter { sbn -> filter.shouldForward(sbn.packageName, isGroupSummary(sbn.notification)) }
            .map { sbn ->
                maybeWithIcon(
                    NotificationRecordMapper.toRecord(
                        packageName = sbn.packageName,
                        appLabel = labelFor(sbn.packageName),
                        key = sbn.key,
                        notification = sbn.notification,
                        postTimeMs = sbn.postTime,
                        onTruncated = { truncations.onTruncated(sbn.key) },
                    )
                )
            }
        val fitted = SnapshotBudget.fit(candidates) { sentIcons.remove(it.packageName) }
        fitted.records.forEach { seenKeys.add(it.key) }
        val snapshot = SnapshotReply.newBuilder().addAllNotifications(fitted.records)
        snapshot.addAllPhoneAccounts(PhoneAccountsProvider(applicationContext).accounts())
        CallManager.currentCellularCallState()?.let { snapshot.setCallState(it) }
        voipCalls.entries.firstOrNull()?.let { (key, signal) ->
            val active = voipActive.contains(key)
            snapshot.setCallState(
                CallState.newBuilder()
                    .setCallId(voipCallId(key))
                    .setKind(CallKind.CALL_KIND_VOIP)
                    .setPackageName(signal.packageName)
                    .setDirection(CallDirection.CALL_DIRECTION_INCOMING)
                    .setPhase(if (active) CallPhase.CALL_PHASE_ACTIVE else CallPhase.CALL_PHASE_RINGING)
                    .setContactName(signal.title)
                    .setStartedAtMs(voipStartedAt[key] ?: 0L)
            )
        }
        val reply = snapshot.build()
        Diagnostics.recorder.onSnapshot(
            reply.notificationsCount,
            reply.serializedSize,
            fitted.iconsDropped,
            fitted.recordsDropped,
        )
        TransportHub.send(newEnvelope { setSnapshotReply(reply) })
    }

    private fun activeNotificationList(): List<ActiveNotification> =
        activeNotifications.orEmpty().map { ActiveNotification(it.key, it.notification) }

    private fun isGroupSummary(notification: Notification): Boolean =
        notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    private fun labelFor(packageName: String): String =
        appLabels.computeIfAbsent(packageName) {
            runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)
                ).toString()
            }.getOrDefault(packageName)
        }
}
