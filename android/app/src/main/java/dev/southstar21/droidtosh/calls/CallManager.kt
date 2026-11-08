package dev.southstar21.droidtosh.calls

import android.content.Context
import dev.southstar21.droidtosh.protocol.v1.ActionResult
import dev.southstar21.droidtosh.protocol.v1.CallState
import dev.southstar21.droidtosh.transport.InboundFeature
import dev.southstar21.droidtosh.transport.TransportHub
import dev.southstar21.droidtosh.transport.newEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object CallManager {
    private const val CELLULAR_PREFIX = "cellular:"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tracker: CellularCallTracker? = null
    private var collectJob: Job? = null

    /**
     * Set by the notification listener: taps an action on the default dialer's call notification.
     * Only a fallback, as an Answer that opens the dialer's call screen is dropped by Android's
     * background activity launch limits while the phone is unlocked.
     */
    @Volatile
    var dialerNotificationAction: ((VoipAction) -> Boolean)? = null

    /** Set by the notification listener: whether a VoIP call notification is showing. */
    @Volatile
    var voipCallInProgress: (() -> Boolean)? = null

    fun start(context: Context) {
        if (tracker != null) return
        val appContext = context.applicationContext
        val tracker = CellularCallTracker(
            appContext,
            voipCallInProgress = { CallManager.voipCallInProgress?.invoke() == true },
        )
        this.tracker = tracker
        tracker.start()
        val callLogProvider = CallLogProvider(appContext)
        collectJob = scope.launch {
            TransportHub.incoming(InboundFeature.CALLS).collect { envelope ->
                when {
                    envelope.hasCallAction() -> {
                        val request = envelope.callAction
                        if (!request.callId.startsWith(CELLULAR_PREFIX)) return@collect
                        val ok = tracker.handleAction(request.callId, request.action)
                        TransportHub.send(newEnvelope {
                            setActionResult(
                                ActionResult.newBuilder()
                                    .setRequestId(request.requestId)
                                    .setOk(ok)
                                    .setErrorCode(if (ok) "" else "call_action_failed")
                            )
                        })
                    }
                    envelope.hasDialRequest() -> {
                        val request = envelope.dialRequest
                        val ok = tracker.dial(request.number, request.phoneAccountId.ifEmpty { null })
                        TransportHub.send(newEnvelope {
                            setActionResult(
                                ActionResult.newBuilder()
                                    .setRequestId(request.requestId)
                                    .setOk(ok)
                                    .setErrorCode(if (ok) "" else "dial_failed")
                            )
                        })
                    }
                    envelope.hasCalllogRequest() -> {
                        val request = envelope.calllogRequest
                        val page = callLogProvider.page(
                            offset = request.offset,
                            limit = request.limit.coerceIn(1, 200),
                        )
                        TransportHub.send(newEnvelope { setCalllogPage(page) })
                    }
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        tracker?.stop()
        tracker = null
    }

    fun currentCellularCallState(): CallState? = tracker?.currentCallState()

    /** A cellular call is tracked, or an OFFHOOK is still being identified. */
    fun hasCellularCall(): Boolean = tracker?.isTrackingCall() == true

    /** The default dialer's call notification shows a call timer: the call is connected. */
    fun onDialerCallTimerShown() {
        tracker?.onDialerCallTimerShown()
    }
}
