//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.call

import com.yollacalls.android.voice.Call
import com.yollacalls.android.voice.CallQualityWarning
import com.yollacalls.android.voice.CallStats
import com.yollacalls.android.voice.VoiceError
import com.yollacalls.android.voice.example.app.VoiceConnecting
import com.yollacalls.android.voice.example.logs.LogStore
import com.yollacalls.android.voice.example.telecom.TelecomCallEvents
import com.yollacalls.android.voice.example.telecom.TelecomControlling
import com.yollacalls.android.voice.example.telecom.TelecomEndCause
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Phase {
    data object Connecting : Phase
    data class Ringing(val earlyMedia: Boolean) : Phase
    data object Connected : Phase
    data class Reconnecting(val reason: String) : Phase
    data class Ended(val reason: Call.EndReason, val error: VoiceError?) : Phase

    val isActive: Boolean get() = this !is Ended
}

data class CallState(
    val phase: Phase = Phase.Connecting,
    val connectedAt: Instant? = null,
    val isMuted: Boolean = false,
    val isOnHold: Boolean = false,
    val warnings: Set<CallQualityWarning> = emptySet(),
    val stats: CallStats? = null,
    val callID: String? = null,
    val lastError: String? = null,
)

/**
 * One call as seen by the UI. [connect] places the call and follows its snapshots in `VoiceConnecting.call`, mirroring
 * them into [state]; the view model is confined to the main thread ([scope] is the main scope).
 *
 * With Telecom on, user commands go through it where it has a say (hold) and the end is reported to it after the
 * SDK's end tone; without it ([telecom] is null) commands go straight to the client.
 */
class CallViewModel(
    val uuid: UUID,
    val number: String,
    private val logs: LogStore,
    private val scope: CoroutineScope,
    private val onEndReported: () -> Unit = {},
    private val now: () -> Instant = Instant::now,
) : TelecomCallEvents {
    /** Set by AppModel before connect; null = direct mode. */
    var telecom: TelecomControlling? = null

    private val mutableState = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = mutableState

    /** The client, once [connect] has placed this view model's call on it. */
    private var voice: VoiceConnecting? = null
    /** The last snapshot of this view model's call that was applied to [state]. */
    private var last: Call? = null
    private var statsJob: Job? = null
    private var errorJob: Job? = null
    private var hangupPending = false
    /** True once a hang-up was asked for (from the screen, the notification or Telecom); AppModel reads it before connect. */
    var hangupRequested = false
        private set
    private var endedViaTelecom = false
    /** Set while a system hold the SDK refused is emulated by muting; holds the user's own mute to restore. */
    private var holdByMute = false
    private var mutedBeforeHold = false

    private fun update(change: (CallState) -> CallState) = mutableState.update(change)

    /**
     * Places the call on [voice] and follows it to its end. Throws what `VoiceConnecting.connect` throws, and then
     * there is no call to follow.
     *
     * `connect` puts the new call's first snapshot into `voice.call` before it returns, so the value read right after it
     * is this view model's call, never the ended previous one that stayed there; its callID is the one followed. A
     * StateFlow may skip snapshots a slow collector has not read yet, never the latest one: the call's `Disconnected`
     * snapshot is its last and stays in `voice.call` until the next `connect`, which the app makes only once this call
     * has ended here. So the end is always seen; [apply] reads states, not transitions, so a skipped snapshot in
     * between loses nothing. Following stops at the end, or if another call shows up in `voice.call`.
     */
    fun connect(voice: VoiceConnecting, callToken: String) {
        voice.connect(callToken)
        this.voice = voice
        val callID = voice.call.value?.callID ?: throw VoiceError.invalidCallState
        update { it.copy(callID = callID) }
        scope.launch {
            voice.call
                .transformWhile { call ->
                    if (call?.callID != callID) {
                        logs.app("the client moved on to another call; no longer following $callID")
                        return@transformWhile false
                    }
                    emit(call)
                    call.state != Call.State.Disconnected
                }
                .collect(::apply)
        }
        if (hangupPending) {
            hangupPending = false
            voice.hangup()
        }
    }

    // region Commands from the UI

    /**
     * Runs a command of the screen in the call's own scope, not the screen's: a hang-up (or a mute) is not cut short
     * when the screen leaves the composition or the Activity is destroyed.
     */
    fun perform(command: suspend CallViewModel.() -> Unit): Job = scope.launch { command() }

    /** A mute the SDK refuses is shown as the error, the state stays as it was. */
    suspend fun toggleMute() {
        tryMute(!state.value.isMuted)
    }

    suspend fun toggleHold() {
        val target = !state.value.isOnHold
        val telecom = telecom
        if (telecom != null && !telecom.setHeld(target)) {
            report("Telecom did not allow the hold change")
            return
        }
        if (!target && holdByMute) {
            endHoldByMute()
            return
        }
        try {
            applyHold(target)
        } catch (e: VoiceError) {
            report(e)
            telecom?.setHeld(!target)
        }
    }

    suspend fun send(digit: String) {
        val voice = voice ?: return
        try {
            voice.sendDigits(digit)
            logs.app("dtmf $digit")
        } catch (e: VoiceError) {
            report(e)
        }
    }

    /** Returns at once: the client ends the call on its own scope, whatever happens to this one. */
    fun hangup() = requestHangup(fromTelecom = false)

    // endregion

    // region Commands from the system

    override suspend fun hangupFromTelecom() {
        endedViaTelecom = true
        requestHangup(fromTelecom = true)
    }

    override suspend fun holdFromTelecom(hold: Boolean) {
        if (hold) {
            if (state.value.phase != Phase.Connected) {
                // The system asked for hold, not for a disconnect: the end is still reported to Telecom.
                logs.app("hold requested by the system while not connected")
                requestHangup(fromTelecom = false)
                return
            }
            if (state.value.isOnHold) return
            try {
                applyHold(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The SDK refused: keep the system's view of the call and silence the microphone instead.
                reportAny(e)
                holdByMute = true
                mutedBeforeHold = state.value.isMuted
                tryMute(true)
                update { it.copy(isOnHold = true) }
            }
        } else {
            if (holdByMute) {
                endHoldByMute()
            } else {
                try {
                    applyHold(false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportAny(e)
                }
            }
        }
    }

    /** Ends a hold that was emulated by muting: restores the user's own mute state. */
    private suspend fun endHoldByMute() {
        holdByMute = false
        if (!mutedBeforeHold) tryMute(false)
        update { it.copy(isOnHold = last?.isOnHold ?: false) }
        logs.app("hold false")
    }

    /** [applyMute] for the system callbacks, which must not throw. */
    private suspend fun tryMute(muted: Boolean) {
        try {
            applyMute(muted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportAny(e)
        }
    }

    private fun reportAny(e: Exception) {
        if (e is VoiceError) report(e) else report(e.message ?: e.toString())
    }

    override suspend fun muteFromTelecom(muted: Boolean) {
        if (muted != state.value.isMuted) tryMute(muted)
    }

    // endregion

    /** The SDK shows the change in the call's next snapshot, which [apply] mirrors into [state]. */
    private suspend fun applyMute(muted: Boolean) {
        val voice = voice ?: return
        voice.setMuted(muted)
        logs.app("mute $muted")
    }

    /** Throws [VoiceError] when the SDK refuses. */
    private suspend fun applyHold(hold: Boolean) {
        val voice = voice ?: throw VoiceError.invalidCallState
        voice.setOnHold(hold)
        logs.app("hold $hold")
    }

    /** Ends the call now, or as soon as `connect` returns when the end request arrived during setup. */
    private fun requestHangup(fromTelecom: Boolean) {
        hangupRequested = true
        val source = if (fromTelecom) " (from Telecom)" else ""
        val voice = voice
        if (voice == null) {
            hangupPending = true
            logs.app("hangup$source requested before connect finished")
            return
        }
        logs.app("hangup$source")
        voice.hangup()
    }

    private fun report(error: VoiceError) {
        val text = "${error.code}: ${error.message}"
        report(text)
    }

    private fun report(text: String) {
        update { it.copy(lastError = text) }
        logs.app("error $text")
        errorJob?.cancel()
        errorJob = scope.launch {
            delay(ERROR_VISIBLE)
            update { it.copy(lastError = null) }
        }
    }

    // region Snapshots

    /** Mirrors a snapshot of this view model's call into [state], and acts on what changed since [last]. */
    private fun apply(call: Call) {
        val before = last
        last = call
        update {
            it.copy(
                callID = call.callID,
                isMuted = call.isMuted,
                // A hold emulated by muting is the app's own: the SDK's call is not on hold.
                isOnHold = call.isOnHold || holdByMute,
            )
        }
        if (call.qualityWarnings != (before?.qualityWarnings ?: emptySet<CallQualityWarning>())) {
            update { it.copy(warnings = call.qualityWarnings) }
            logs.app("quality warnings: ${call.qualityWarnings.map { it.rawValue }.sorted().joinToString(",")}")
        }
        if (before != null && call.state == before.state && call.hasEarlyMedia == before.hasEarlyMedia) return
        when (call.state) {
            Call.State.Connecting -> Unit
            Call.State.Ringing -> {
                update { it.copy(phase = Phase.Ringing(call.hasEarlyMedia)) }
                logs.app("ringing (early media: ${call.hasEarlyMedia})")
            }
            Call.State.Connected ->
                if (state.value.connectedAt == null) {
                    didConnect()
                } else {
                    update { it.copy(phase = Phase.Connected) }
                    logs.app("reconnected")
                }
            Call.State.Reconnecting -> {
                // A call only reconnects once answered; the answer itself may be a snapshot this one replaced.
                if (state.value.connectedAt == null) didConnect()
                val code = call.error?.code ?: "reconnecting"
                update { it.copy(phase = Phase.Reconnecting(code)) }
                logs.app("reconnecting: $code")
            }
            Call.State.Disconnected ->
                didDisconnect(call.endReason ?: Call.EndReason.Failed, call.error, call.wasConnected)
        }
    }

    /** How long the end report of this call was held for the SDK's end tone; set when the call ends. */
    var endToneDelay: Duration = Duration.ZERO
        private set

    private fun didConnect() {
        update { it.copy(phase = Phase.Connected, connectedAt = now()) }
        logs.app("connected")
        telecom?.let { scope.launch { it.reportConnected() } }
        startStats()
    }

    /** [wasConnected] comes from the SDK's snapshot: the connected one may have been replaced before it was read. */
    private fun didDisconnect(reason: Call.EndReason, error: VoiceError?, wasConnected: Boolean) {
        statsJob?.cancel(); statsJob = null
        val endToneDelay = endToneDelay(reason, wasConnected)
        this.endToneDelay = endToneDelay
        update { it.copy(phase = Phase.Ended(reason, error)) }
        logs.app("ended: ${describe(reason, error)}")
        val cause = telecomEndCause(reason, endedViaTelecom)
        val telecom = telecom
        scope.launch {
            // Hold the report back so the SDK's end/busy tone is heard before Telecom takes the audio away.
            delay(endToneDelay)
            if (cause != null) telecom?.reportEnded(cause)
            onEndReported()
        }
    }

    // endregion

    private fun startStats() {
        val voice = voice ?: return
        statsJob?.cancel()
        statsJob = scope.launch {
            while (true) {
                delay(1000)
                if (!state.value.phase.isActive) return@launch
                try {
                    val stats = voice.stats() ?: continue
                    update { it.copy(stats = stats) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed sample is skipped; the next tick tries again.
                }
            }
        }
    }

    companion object {
        private val ERROR_VISIBLE = 4000.milliseconds

        // The tones the SDK plays when a call ends, as its integration guide lists them.
        private val TONE_ENDED = 500.milliseconds
        private val TONE_BUSY = 3.seconds
        private val TONE_NO_ANSWER = 800.milliseconds
        private val TONE_FAILED = 2.seconds
        private val TONE_MARGIN = 100.milliseconds

        /** The longest [endToneDelay] there is. */
        val longestEndToneDelay: Duration = TONE_BUSY + TONE_MARGIN

        /**
         * How long the Telecom end report is held so the SDK's tone for this end is heard before Telecom takes the
         * audio away: the tone's length with a small margin, zero when the SDK plays none. The SDK itself turns the
         * call audio off once the tone has played.
         */
        fun endToneDelay(reason: Call.EndReason, wasConnected: Boolean): Duration {
            val tone = if (wasConnected) {
                TONE_ENDED
            } else {
                when (reason) {
                    Call.EndReason.Busy -> TONE_BUSY
                    Call.EndReason.NoAnswer -> TONE_NO_ANSWER
                    Call.EndReason.Failed, Call.EndReason.Rejected -> TONE_FAILED
                    Call.EndReason.LocalHangup, Call.EndReason.RemoteHangup, Call.EndReason.Cancelled -> return Duration.ZERO
                }
            }
            return tone + TONE_MARGIN
        }

        fun describe(reason: Call.EndReason, error: VoiceError?): String = when (reason) {
            Call.EndReason.Busy -> "Busy"
            Call.EndReason.NoAnswer -> "No answer"
            Call.EndReason.Rejected -> "Rejected"
            Call.EndReason.RemoteHangup -> "Call ended by the other side"
            Call.EndReason.LocalHangup -> "Call ended"
            Call.EndReason.Cancelled -> "Cancelled"
            Call.EndReason.Failed -> {
                if (error == null) {
                    "Failed"
                } else {
                    val sip = error.sipStatus?.let { ", SIP $it" } ?: ""
                    "Failed: ${error.code} (${error.message}$sip)"
                }
            }
        }

        /**
         * What to report to Telecom; null when the end came from Telecom itself (it already knows). A local end
         * that bypassed Telecom is still reported so no stale system call remains.
         */
        fun telecomEndCause(reason: Call.EndReason, endedViaTelecom: Boolean): TelecomEndCause? = when (reason) {
            Call.EndReason.RemoteHangup, Call.EndReason.NoAnswer -> TelecomEndCause.Remote
            Call.EndReason.Failed -> TelecomEndCause.Error
            Call.EndReason.Busy, Call.EndReason.Rejected -> TelecomEndCause.Rejected
            Call.EndReason.LocalHangup, Call.EndReason.Cancelled -> if (endedViaTelecom) null else TelecomEndCause.Local
        }
    }
}
