package dev.southstar21.droidtosh.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dev.southstar21.droidtosh.protocol.v1.CallActionType
import dev.southstar21.droidtosh.protocol.v1.CallDirection
import dev.southstar21.droidtosh.protocol.v1.CallKind
import dev.southstar21.droidtosh.protocol.v1.CallPhase
import dev.southstar21.droidtosh.protocol.v1.CallState
import dev.southstar21.droidtosh.transport.TransportHub
import dev.southstar21.droidtosh.transport.newEnvelope
import java.util.UUID

class CellularCallTracker(
    private val context: Context,
    private val voipCallInProgress: () -> Boolean = { false },
    private val simCallCheck: (() -> Boolean)? = null,
    private val sendState: (CallState) -> Unit = { state -> TransportHub.send(newEnvelope { setCallState(state) }) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val postDelayed: (Long, () -> Unit) -> Unit = { delayMs, block ->
        Handler(Looper.getMainLooper()).postDelayed(block, delayMs)
    },
    private val contactLookup: (String) -> String = { number -> PhoneLookups.contactName(context, number) },
    private val outgoingLogLookup: (Long) -> OutgoingLogEntry? = { sinceMs -> PhoneLookups.latestOutgoing(context, sinceMs) },
    private val callControl: CellularCallControl = TelecomCallControl(context),
    private val dialerAction: (VoipAction) -> Boolean = { CallManager.dialerNotificationAction?.invoke(it) == true },
) {
    private val telecomManager: TelecomManager = context.getSystemService(TelecomManager::class.java)
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val telephonyManager: TelephonyManager = context.getSystemService(TelephonyManager::class.java)

    @Volatile
    private var currentCallId: String? = null

    @Volatile
    private var currentNumber: String = ""

    @Volatile
    private var currentPhoneAccountId: String = ""

    @Volatile
    private var currentSimLabel: String = ""

    @Volatile
    private var startedAtMs: Long = 0L

    @Volatile
    private var accountResolved = false

    @Volatile
    private var wasOffhook = false

    @Volatile
    private var pendingOutgoing = false

    @Volatile
    private var ringMuted = false

    /** When the Mac's dial request was placed; a request that never became a call goes stale. */
    @Volatile
    private var macDialAtMs = 0L

    /** The call was dialled on the phone itself (not from the Mac). */
    @Volatile
    private var dialledOnPhone = false

    /** A call dialled on the phone has connected (the dialer started its call timer). */
    @Volatile
    private var outgoingConnected = false

    /** OFFHOOK with no ring and no Mac dial: waiting to tell a SIM call from a VoIP one. */
    @Volatile
    private var awaitingOutgoing = false

    /** That OFFHOOK turned out not to be a SIM call; ignore it until the phone is idle again. */
    @Volatile
    private var offhookIgnored = false

    @Volatile
    private var offhookAtMs = 0L

    @Volatile
    private var decisionGeneration = 0

    /** The number from ACTION_NEW_OUTGOING_CALL, until the call it belongs to goes OFFHOOK. */
    @Volatile
    private var dialledNumber = ""

    @Volatile
    private var dialledAtMs = 0L

    /** The subscription a phone-state broadcast named, if any. */
    @Volatile
    private var broadcastSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID

    /** With several SIMs and no way to tell which one a dialled call uses, the call log says. */
    @Volatile
    private var accountUncertain = false

    @Volatile
    private var contactNumber = ""

    @Volatile
    private var contactName = ""

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_NEW_OUTGOING_CALL ->
                    onNewOutgoingCall(intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER).orEmpty())
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                    val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                        TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                        TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                        TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                        else -> return
                    }
                    val number = if (intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)) {
                        intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
                    } else {
                        null
                    }
                    android.util.Log.d("DroidsyncCalls", "phone-state state=$state hasNumber=${number != null}")
                    onPhoneState(
                        state,
                        number,
                        intent.getIntExtra(
                            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                            SubscriptionManager.INVALID_SUBSCRIPTION_ID,
                        ),
                    )
                }
            }
        }
    }

    /**
     * A phone-state broadcast. With READ_CALL_LOG it arrives twice, first without the number;
     * the number is the call that changed state, an incoming caller or a number dialled on the
     * phone. Telecom never includes it for self-managed (VoIP) calls.
     */
    internal fun onPhoneState(
        state: Int,
        number: String?,
        subId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    ) {
        // A waiting second call must not overwrite the details of the call in progress.
        val waitingCall = inProgress() || (awaitingOutgoing && state == TelephonyManager.CALL_STATE_RINGING)
        if (!waitingCall) {
            if (number != null && (number.isNotEmpty() || currentNumber.isEmpty())) currentNumber = number
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) broadcastSubId = subId
        }
        // Without the permission (or for withheld numbers) the number never comes, so announce
        // the call anyway.
        handleState(
            state,
            numberFinal = number != null || !canReadCallLog(),
            numbered = !number.isNullOrEmpty(),
        )
    }

    /**
     * ACTION_NEW_OUTGOING_CALL (PROCESS_OUTGOING_CALLS): a number was dialled. Telecom sends it
     * for every managed (SIM) call, emergency numbers included, and never for self-managed VoIP
     * calls. It can arrive before or just after the OFFHOOK it belongs to.
     */
    internal fun onNewOutgoingCall(number: String) {
        if (number.isBlank()) return
        android.util.Log.d("DroidsyncCalls", "new outgoing call")
        if (currentCallId != null) {
            // The call dialled on the phone, announced before its number was known.
            if (dialledOnPhone && currentNumber.isBlank()) {
                currentNumber = number
                emit(activePhase())
            }
            // Otherwise a further call placed during a call: keep showing the first one.
            return
        }
        if (pendingOutgoing) {
            // The Mac's own dial request.
            if (!macDialExpired() && sameNumber(number, currentNumber)) return
            // A different number dialled on the phone: the Mac's request didn't become a call.
            clearMacDial()
        }
        dialledNumber = number
        dialledAtMs = clock()
        // Also after an OFFHOOK was taken for VoIP: this broadcast proves a SIM call.
        if (awaitingOutgoing || offhookIgnored) startOutgoing()
    }

    fun start() {
        runCatching {
            // PHONE_STATE is a protected broadcast only the system can send, so exporting is safe and
            // keeps delivery independent of how the platform treats system broadcasts to unexported receivers.
            // NEW_OUTGOING_CALL is protected too, and only delivered with PROCESS_OUTGOING_CALLS;
            // registering without the permission is harmless and picks it up once granted.
            @Suppress("DEPRECATION")
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
                addAction(Intent.ACTION_NEW_OUTGOING_CALL)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun currentCallState(): CallState? = currentCallId?.let { id ->
        applyAccountFields(
            CallState.newBuilder()
                .setCallId(id)
                .setKind(CallKind.CALL_KIND_CELLULAR)
                .setDirection(currentDirection())
                .setPhase(if (wasOffhook) activePhase() else CallPhase.CALL_PHASE_RINGING)
                .setNumber(currentNumber)
                .setContactName(contactFor(currentNumber))
        ).build()
    }

    /** Whether a call is tracked, or an OFFHOOK is still being told apart from a VoIP call. */
    fun isTrackingCall(): Boolean = currentCallId != null || awaitingOutgoing

    /**
     * The default dialer's call notification started its call timer: a call dialled on the
     * phone was answered. The phone-state broadcast is OFFHOOK from dialing onwards, so this is
     * the only sign of it an app without the dialer role gets.
     */
    fun onDialerCallTimerShown() {
        if (currentCallId == null || !dialledOnPhone || outgoingConnected) return
        outgoingConnected = true
        emit(CallPhase.CALL_PHASE_ACTIVE)
    }

    private fun inProgress(): Boolean = wasOffhook && currentCallId != null

    private fun canReadCallLog(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    private fun currentDirection(): CallDirection =
        if (pendingOutgoing || dialledOnPhone) CallDirection.CALL_DIRECTION_OUTGOING
        else CallDirection.CALL_DIRECTION_INCOMING

    /** A call dialled on the phone is DIALING until the dialer shows it connected. */
    private fun activePhase(): CallPhase =
        if (dialledOnPhone && !outgoingConnected) CallPhase.CALL_PHASE_DIALING
        else CallPhase.CALL_PHASE_ACTIVE

    fun handleAction(callId: String, action: CallActionType): Boolean {
        if (!callId.startsWith(CELLULAR_PREFIX)) return false
        val ok = when (action) {
            CallActionType.CALL_ACTION_ACCEPT -> accept()
            CallActionType.CALL_ACTION_DECLINE ->
                callControl.endCall() || dialerAction(VoipAction.DECLINE)
            CallActionType.CALL_ACTION_END ->
                callControl.endCall() || dialerAction(VoipAction.END)
            CallActionType.CALL_ACTION_MUTE_RING -> muteRing()
            else -> false
        }
        android.util.Log.d("DroidsyncCalls", "cellular action=$action ok=$ok")
        return ok
    }

    /**
     * Answers through Telecom first: it starts no activity, so it works whether the phone is
     * locked or not. The dialer notification's Answer usually opens the in-call screen, which
     * Android 14+ silently drops while the phone is unlocked and Droidtosh is in the background,
     * and PendingIntent.send reports success anyway, so it is only a fallback: without
     * ANSWER_PHONE_CALLS, when Telecom throws, or when the call is still ringing a moment later
     * (acceptRingingCall returns nothing, and some OEMs ignore it for apps that aren't the
     * default dialer).
     */
    private fun accept(): Boolean {
        if (!callControl.acceptRingingCall()) {
            android.util.Log.d("DroidsyncCalls", "acceptRingingCall unavailable, trying the dialer notification")
            return dialerAction(VoipAction.ACCEPT)
        }
        val callId = currentCallId
        if (callId != null) {
            postDelayed(ACCEPT_VERIFY_MS) {
                if (currentCallId == callId && !wasOffhook) {
                    android.util.Log.d("DroidsyncCalls", "acceptRingingCall had no effect, trying the dialer notification")
                    dialerAction(VoipAction.ACCEPT)
                }
            }
        }
        return true
    }

    private fun muteRing(): Boolean {
        // TelecomManager.silenceRinger needs MODIFY_PHONE_STATE (system apps only) or the
        // default-dialer role, neither of which this app has, so it always threw. Mute the ring
        // stream instead and undo it once the call stops ringing, so later calls still ring.
        return runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            ringMuted = true
        }.isSuccess
    }

    private fun restoreRing() {
        if (!ringMuted) return
        ringMuted = false
        runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0) }
    }

    fun dial(number: String, phoneAccountId: String?): Boolean {
        // Don't clobber the fields of a call that is already ringing or in progress.
        if (currentCallId != null || awaitingOutgoing) return placeCall(number, phoneAccountId)
        return runCatching {
            dialNew(number, phoneAccountId)
        }.getOrElse {
            clearMacDial()
            false
        }
    }

    private fun placeCall(number: String, phoneAccountId: String?): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val extras = Bundle()
        if (!phoneAccountId.isNullOrEmpty()) {
            runCatching { callCapablePhoneAccounts() }.getOrDefault(emptyList())
                .firstOrNull { it.id == phoneAccountId }
                ?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        }
        return runCatching { telecomManager.placeCall(Uri.fromParts("tel", number, null), extras) }.isSuccess
    }

    private fun dialNew(number: String, phoneAccountId: String?): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("CALL_PHONE not granted")
        val extras = Bundle()
        currentNumber = number
        if (!phoneAccountId.isNullOrEmpty()) {
            callCapablePhoneAccounts()
                .firstOrNull { it.id == phoneAccountId }
                ?.let { handle ->
                    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    currentPhoneAccountId = handle.id
                    currentSimLabel = phoneAccountLabel(handle)
                    accountResolved = true
                }
        }
        pendingOutgoing = true
        macDialAtMs = clock()
        telecomManager.placeCall(Uri.fromParts("tel", number, null), extras)
        return true
    }

    internal fun handleState(state: Int, numberFinal: Boolean, numbered: Boolean = false) {
        val phase = CellularStateMapper.phaseFor(state, wasOffhook) ?: return
        when (phase) {
            CallPhase.CALL_PHASE_RINGING -> {
                // Call waiting: keep reporting the active (or dialing) call rather than flipping it
                // back to ringing.
                if (inProgress() || awaitingOutgoing) return
                if (currentNumber.isBlank() && !numberFinal) return
                if (currentCallId == null) {
                    if (!isSimCall()) {
                        android.util.Log.d("DroidsyncCalls", "phone-state ringing ignored: no SIM call")
                        currentNumber = ""
                        return
                    }
                    currentCallId = CELLULAR_PREFIX + UUID.randomUUID()
                    startedAtMs = clock()
                    resolveAccountIfNeeded(guessDefault = true)
                }
                pendingOutgoing = false
                emit(phase)
            }
            CallPhase.CALL_PHASE_ACTIVE -> {
                if (currentCallId == null && pendingOutgoing && macDialExpired()) clearMacDial()
                if (currentCallId == null && !pendingOutgoing) {
                    onOffhookWithoutCall(numbered)
                    return
                }
                // OFFHOOK also comes from a self-managed VoIP call being answered (or still running
                // after a waiting SIM call was declined); only a SIM call may become active.
                if (!wasOffhook && !isSimCall()) {
                    android.util.Log.d("DroidsyncCalls", "phone-state offhook ignored: no SIM call")
                    if (currentCallId != null) emit(CallPhase.CALL_PHASE_MISSED)
                    clearCall()
                    return
                }
                if (currentCallId == null) {
                    currentCallId = CELLULAR_PREFIX + UUID.randomUUID()
                    startedAtMs = clock()
                    resolveAccountIfNeeded(guessDefault = true)
                }
                wasOffhook = true
                restoreRing()
                emit(activePhase())
            }
            CallPhase.CALL_PHASE_ENDED,
            CallPhase.CALL_PHASE_MISSED -> {
                if (currentCallId != null && dialledOnPhone) {
                    finishDialledCall()
                    return
                }
                if (currentCallId != null) emit(phase, endedAtMs = clock())
                clearCall()
            }
            else -> Unit
        }
    }

    /**
     * OFFHOOK with no ring and no Mac dial: a call dialled on the phone, or a VoIP call. A
     * number in the broadcast or a NEW_OUTGOING_CALL proves a SIM call (Telecom gives neither
     * for self-managed calls); otherwise wait briefly, since the number, the VoIP app's call
     * notification and the audio mode can all lag the broadcast, then ask [CellularCallGate].
     */
    private fun onOffhookWithoutCall(numbered: Boolean) {
        if (offhookIgnored && !numbered) return
        if (!awaitingOutgoing) {
            awaitingOutgoing = true
            offhookIgnored = false
            if (offhookAtMs == 0L) offhookAtMs = clock()
            val generation = ++decisionGeneration
            postDelayed(OUTGOING_SETTLE_MS) {
                if (generation == decisionGeneration && awaitingOutgoing) decideOutgoing()
            }
        }
        if (numbered || hasRecentDialledNumber()) startOutgoing()
    }

    private fun decideOutgoing() {
        if (isSimCall()) {
            startOutgoing()
            return
        }
        android.util.Log.d("DroidsyncCalls", "phone-state offhook ignored: no SIM call")
        cancelOutgoingDecision()
        offhookIgnored = true
        currentNumber = ""
    }

    private fun startOutgoing() {
        cancelOutgoingDecision()
        dialledOnPhone = true
        wasOffhook = true
        currentCallId = CELLULAR_PREFIX + UUID.randomUUID()
        startedAtMs = offhookAtMs.takeIf { it > 0L } ?: clock()
        if (currentNumber.isBlank() && hasRecentDialledNumber()) currentNumber = dialledNumber
        dialledNumber = ""
        resolveAccountIfNeeded(guessDefault = false)
        android.util.Log.d("DroidsyncCalls", "outgoing call dialled on the phone hasNumber=${currentNumber.isNotBlank()}")
        emit(CallPhase.CALL_PHASE_DIALING)
    }

    private fun cancelOutgoingDecision() {
        awaitingOutgoing = false
        decisionGeneration++
    }

    /**
     * A call dialled on the phone ended. Without its number (no PROCESS_OUTGOING_CALLS or
     * READ_CALL_LOG at the time), or with several SIMs and no way to tell which one it used,
     * read the call log entry the dialer writes just after the call, and send ENDED with it.
     */
    private fun finishDialledCall() {
        val ended = stateBuilder(CallPhase.CALL_PHASE_ENDED).setEndedAtMs(clock())
        val needsLog = ended.number.isBlank() || accountUncertain
        val sinceMs = startedAtMs - CALL_LOG_SLACK_MS
        clearCall()
        if (needsLog && canReadCallLog()) {
            fillFromCallLog(ended, sinceMs, attempt = 0)
        } else {
            sendState(ended.build())
        }
    }

    private fun fillFromCallLog(ended: CallState.Builder, sinceMs: Long, attempt: Int) {
        postDelayed(CALL_LOG_POLL_MS) {
            val entry = runCatching { outgoingLogLookup(sinceMs) }.getOrNull()
            if (entry == null && attempt + 1 < CALL_LOG_POLLS) {
                fillFromCallLog(ended, sinceMs, attempt + 1)
                return@postDelayed
            }
            if (entry != null) {
                if (ended.number.isBlank() && entry.number.isNotBlank()) {
                    ended.setNumber(entry.number)
                        .setContactName(runCatching { contactLookup(entry.number) }.getOrDefault(""))
                }
                if (entry.phoneAccountId.isNotBlank() && entry.phoneAccountId != ended.phoneAccountId) {
                    val handle = runCatching { callCapablePhoneAccounts() }.getOrDefault(emptyList())
                        .firstOrNull { it.id == entry.phoneAccountId }
                    ended.setPhoneAccountId(entry.phoneAccountId)
                        .setSimLabel(handle?.let { phoneAccountLabel(it) }.orEmpty())
                }
            }
            sendState(ended.build())
        }
    }

    private fun hasRecentDialledNumber(): Boolean =
        dialledNumber.isNotBlank() && clock() - dialledAtMs <= DIALLED_NUMBER_TTL_MS

    private fun macDialExpired(): Boolean = clock() - macDialAtMs > MAC_DIAL_TTL_MS

    private fun sameNumber(a: String, b: String): Boolean =
        a.isBlank() || b.isBlank() || CallLogMapper.normalizeNumber(a) == CallLogMapper.normalizeNumber(b)

    private fun clearMacDial() {
        pendingOutgoing = false
        macDialAtMs = 0L
        currentNumber = ""
        currentPhoneAccountId = ""
        currentSimLabel = ""
        accountResolved = false
    }

    private fun clearCall() {
        restoreRing()
        cancelOutgoingDecision()
        currentCallId = null
        currentNumber = ""
        currentPhoneAccountId = ""
        currentSimLabel = ""
        startedAtMs = 0L
        accountResolved = false
        wasOffhook = false
        pendingOutgoing = false
        macDialAtMs = 0L
        dialledOnPhone = false
        outgoingConnected = false
        offhookIgnored = false
        offhookAtMs = 0L
        dialledNumber = ""
        dialledAtMs = 0L
        broadcastSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        accountUncertain = false
    }

    private fun isSimCall(): Boolean = simCallCheck?.invoke() ?: CellularCallGate.isSimCall(
        inManagedCall = inManagedCall(),
        audioMode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL),
        voipCallInProgress = voipCallInProgress(),
        ownDial = pendingOutgoing,
    )

    /** Whether a SIM (managed, not self-managed) call exists, or null without READ_PHONE_STATE. */
    private fun inManagedCall(): Boolean? {
        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching { telecomManager.isInManagedCall }.getOrNull()
    }

    private fun emit(phase: CallPhase, endedAtMs: Long = 0L) {
        if (currentCallId == null) return
        sendState(stateBuilder(phase).setEndedAtMs(endedAtMs).build())
    }

    private fun stateBuilder(phase: CallPhase): CallState.Builder =
        applyAccountFields(
            CallState.newBuilder()
                .setCallId(currentCallId.orEmpty())
                .setKind(CallKind.CALL_KIND_CELLULAR)
                .setDirection(currentDirection())
                .setPhase(phase)
                .setNumber(currentNumber)
                .setContactName(contactFor(currentNumber))
        )

    /** The contact's name for [number], looked up once per number. */
    private fun contactFor(number: String): String {
        if (number.isBlank()) return ""
        if (number != contactNumber) {
            contactName = runCatching { contactLookup(number) }.getOrDefault("")
            contactNumber = number
        }
        return contactName
    }

    private fun applyAccountFields(builder: CallState.Builder): CallState.Builder =
        builder
            .setPhoneAccountId(currentPhoneAccountId)
            .setSimLabel(currentSimLabel)
            .setStartedAtMs(startedAtMs)

    /**
     * The call's SIM: the only one, the subscription the phone-state broadcast named, or (for
     * incoming calls, as before) the default subscription. A call dialled on the phone with
     * several SIMs and none of that leaves it to the call log once the call ends.
     */
    private fun resolveAccountIfNeeded(guessDefault: Boolean) {
        if (accountResolved) return
        accountResolved = true
        val accounts = runCatching { callCapablePhoneAccounts() }.getOrDefault(emptyList())
        if (accounts.size == 1) {
            setAccount(accounts.first())
            return
        }
        if (accounts.size < 2) return
        val subId = broadcastSubId.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            ?: if (guessDefault) {
                runCatching { telephonyManager.subscriptionId }
                    .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            } else {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
        val handle = accounts.firstOrNull { it.id == subId.toString() }
        if (handle != null) setAccount(handle) else accountUncertain = true
    }

    private fun setAccount(handle: PhoneAccountHandle) {
        currentPhoneAccountId = handle.id
        currentSimLabel = phoneAccountLabel(handle)
    }

    /** The SIM accounts, or none without READ_PHONE_STATE. */
    private fun callCapablePhoneAccounts(): List<PhoneAccountHandle> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        return telecomManager.callCapablePhoneAccounts
    }

    private fun phoneAccountLabel(handle: PhoneAccountHandle): String =
        runCatching { telecomManager.getPhoneAccount(handle)?.label?.toString().orEmpty() }
            .getOrDefault("")

    private companion object {
        const val CELLULAR_PREFIX = "cellular:"

        /** How long an unexplained OFFHOOK waits for its number or the VoIP signals. */
        const val OUTGOING_SETTLE_MS = 1_000L

        /** A NEW_OUTGOING_CALL older than this belongs to no OFFHOOK still to come. */
        const val DIALLED_NUMBER_TTL_MS = 30_000L

        /** A Mac dial request that hasn't gone OFFHOOK by then never became a call. */
        const val MAC_DIAL_TTL_MS = 60_000L

        /** The call log dates a call from its creation, just before OFFHOOK. */
        const val CALL_LOG_SLACK_MS = 3_000L
        const val CALL_LOG_POLL_MS = 400L
        const val CALL_LOG_POLLS = 6

        /** How long an accepted call may take to go OFFHOOK before the dialer notification is tried. */
        const val ACCEPT_VERIFY_MS = 2_000L
    }
}

/** The Telecom calls behind the Mac's Accept, Decline and End buttons for SIM calls. */
interface CellularCallControl {
    /** Asks Telecom to answer the ringing SIM call; false without ANSWER_PHONE_CALLS or when it throws. */
    fun acceptRingingCall(): Boolean

    /** Rejects the ringing SIM call or ends the active one; false when there was none or it failed. */
    fun endCall(): Boolean
}

/**
 * ANSWER_PHONE_CALLS lets any app answer and end managed (SIM) calls. Telecom ignores both for
 * self-managed (VoIP) calls unless the caller is privileged or system UI.
 */
class TelecomCallControl(private val context: Context) : CellularCallControl {
    private val telecomManager: TelecomManager by lazy { context.getSystemService(TelecomManager::class.java) }

    // Lint only accepts the permission check inline, next to each call that needs it.
    override fun acceptRingingCall(): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching {
            @Suppress("DEPRECATION")
            telecomManager.acceptRingingCall()
        }.onFailure { android.util.Log.w("DroidsyncCalls", "acceptRingingCall failed", it) }.isSuccess
    }

    override fun endCall(): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching {
            @Suppress("DEPRECATION")
            telecomManager.endCall()
        }.onFailure { android.util.Log.w("DroidsyncCalls", "endCall failed", it) }.getOrDefault(false)
    }
}
