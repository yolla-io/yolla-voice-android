//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import com.yollacalls.android.voice.Call
import com.yollacalls.android.voice.CallQualityWarning
import com.yollacalls.android.voice.VoiceError
import com.yollacalls.android.voice.example.call.CallViewModel
import com.yollacalls.android.voice.example.call.Phase
import com.yollacalls.android.voice.example.logs.LogStore
import com.yollacalls.android.voice.example.support.FakeVoice
import com.yollacalls.android.voice.example.support.FakeTelecom
import com.yollacalls.android.voice.example.telecom.TelecomEndCause
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TelecomFlowTests {
    private class Rig(scope: TestScope, withTelecom: Boolean = true) {
        val voice = FakeVoice(mutableListOf())
        val telecom = if (withTelecom) FakeTelecom() else null
        var endReported = 0
        val logs = LogStore()
        val vm = CallViewModel(UUID.randomUUID(), "1001", logs, scope.backgroundScope, onEndReported = { endReported++ }).also {
            it.telecom = telecom
        }
    }

    /** [connected]: the view model has placed its call on the fake client and read its first snapshot. */
    private fun TestScope.rig(withTelecom: Boolean = true, connected: Boolean = true) =
        Rig(this, withTelecom).also {
            if (connected) {
                it.vm.connect(it.voice, "token")
                runCurrent()
            }
        }

    private fun TestScope.connected(withTelecom: Boolean = true) = rig(withTelecom).also {
        it.voice.connected()
        runCurrent()
    }

    private suspend fun TestScope.pastEndTone() {
        advanceTimeBy(CallViewModel.longestEndToneDelay)
        runCurrent()
    }

    @Test
    fun hangupWithTelecomIsReportedToTelecom() = runTest {
        val r = rig()
        r.vm.hangup()
        r.voice.ended(Call.EndReason.LocalHangup)
        pastEndTone()
        assertEquals(1, r.voice.hangups)
        assertEquals(listOf(TelecomEndCause.Local), r.telecom!!.ended)
    }

    @Test
    fun localEndThatBypassedTelecomIsStillReported() = runTest {
        val r = rig()
        r.voice.ended(Call.EndReason.RemoteHangup)
        pastEndTone()
        assertEquals(listOf(TelecomEndCause.Remote), r.telecom!!.ended)
    }

    /** The busy tone is 3 s long: Telecom's disconnect takes the call audio, so the report waits for all of it. */
    @Test
    fun telecomEndIsReportedAfterTheBusyTone() = runTest {
        val r = rig()
        r.voice.ended(Call.EndReason.Busy)
        runCurrent()
        advanceTimeBy(3099)
        runCurrent()
        assertTrue(r.telecom!!.ended.isEmpty())
        assertEquals(0, r.endReported)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(TelecomEndCause.Rejected), r.telecom.ended)
        assertEquals(1, r.endReported)
    }

    /**
     * The client's `call` is a state flow: a call answered and ended in quick succession can reach the view model as
     * the ended snapshot alone. That snapshot says the call was connected, so the ended tone is still waited for.
     */
    @Test
    fun anAnsweredCallWhoseConnectedSnapshotWasSkippedStillWaitsForTheEndedTone() = runTest {
        val r = rig()
        r.voice.change {
            state = Call.State.Disconnected
            endReason = Call.EndReason.RemoteHangup
            wasConnected = true
        }
        runCurrent()
        assertEquals(600.milliseconds, r.vm.endToneDelay)
        advanceTimeBy(599)
        runCurrent()
        assertTrue(r.telecom!!.ended.isEmpty())
    }

    @Test
    fun endToneDelayFollowsTheSdksToneForTheEndReason() {
        fun delay(reason: Call.EndReason, wasConnected: Boolean = false) = CallViewModel.endToneDelay(reason, wasConnected)
        assertEquals(600.milliseconds, delay(Call.EndReason.RemoteHangup, wasConnected = true))
        assertEquals(600.milliseconds, delay(Call.EndReason.Failed, wasConnected = true))
        assertEquals(3100.milliseconds, delay(Call.EndReason.Busy))
        assertEquals(900.milliseconds, delay(Call.EndReason.NoAnswer))
        assertEquals(2100.milliseconds, delay(Call.EndReason.Failed))
        assertEquals(2100.milliseconds, delay(Call.EndReason.Rejected))
        assertEquals(Duration.ZERO, delay(Call.EndReason.Cancelled)) // the SDK plays nothing
        assertEquals(Duration.ZERO, delay(Call.EndReason.LocalHangup))
    }

    @Test
    fun endFromTelecomIsNotReportedBack() = runTest {
        val r = rig()
        r.vm.hangupFromTelecom()
        r.voice.ended(Call.EndReason.LocalHangup)
        pastEndTone()
        assertEquals(1, r.voice.hangups)
        assertTrue(r.telecom!!.ended.isEmpty())
        assertEquals(1, r.endReported)
    }

    @Test
    fun endRequestBeforeConnectHangsUpOnceConnected() = runTest {
        val r = rig(connected = false)
        r.vm.hangup()
        assertEquals(0, r.voice.hangups)
        r.vm.connect(r.voice, "token")
        runCurrent()
        assertEquals(1, r.voice.hangups)
        assertTrue(r.logs.exportText.contains("hangup requested before connect finished"))
    }

    @Test
    fun connectIsReportedToTelecomAndStatsArePolled() = runTest {
        val r = connected()
        assertEquals(1, r.telecom!!.connected)
        assertNotNull(r.vm.state.value.connectedAt)
        assertNull(r.vm.state.value.stats)
        advanceTimeBy(1000)
        runCurrent()
        assertNotNull(r.vm.state.value.stats)
    }

    @Test
    fun holdWithTelecomGoesThroughTelecom() = runTest {
        val r = connected()
        r.vm.toggleHold()
        runCurrent()
        assertEquals(listOf(true), r.telecom!!.held)
        assertEquals(listOf(true), r.voice.holds)
        assertTrue(r.vm.state.value.isOnHold)
    }

    @Test
    fun telecomRefusingHoldChangesNothing() = runTest {
        val r = connected()
        r.telecom!!.holdAnswer = false
        r.vm.toggleHold()
        runCurrent()
        assertTrue(r.voice.holds.isEmpty())
        assertFalse(r.vm.state.value.isOnHold)
        assertNotNull(r.vm.state.value.lastError)
    }

    @Test
    fun sdkRefusingHoldPutsTelecomBack() = runTest {
        val r = connected()
        r.voice.holdFailure = VoiceError.invalidCallState
        r.vm.toggleHold()
        runCurrent()
        assertEquals(listOf(true, false), r.telecom!!.held)
        assertFalse(r.vm.state.value.isOnHold)
        assertNotNull(r.vm.state.value.lastError)
    }

    @Test
    fun systemHoldBeforeConnectEndsTheCall() = runTest {
        val r = rig()
        r.voice.change { state = Call.State.Ringing }
        runCurrent()
        r.vm.holdFromTelecom(true)
        assertEquals(1, r.voice.hangups)
        assertTrue(r.vm.state.value.phase is Phase.Ringing)
        // The system asked for hold, not for a disconnect: Telecom must still be told the call ended.
        r.voice.ended(Call.EndReason.Cancelled)
        pastEndTone()
        assertEquals(listOf(TelecomEndCause.Local), r.telecom!!.ended)
    }

    @Test
    fun systemHoldThatThrowsAnythingFallsBackToMute() = runTest {
        val r = connected()
        r.voice.holdFailure = IllegalStateException("boom")
        r.vm.holdFromTelecom(true)
        runCurrent()
        assertEquals(listOf(true), r.voice.mutes)
        assertTrue(r.vm.state.value.isOnHold)
        assertTrue(r.vm.state.value.isMuted)
    }

    @Test
    fun resumeFromTheAppAfterAHoldByMuteUnmutes() = runTest {
        val r = connected()
        r.voice.holdFailure = VoiceError.invalidCallState
        r.vm.holdFromTelecom(true)
        runCurrent()
        r.vm.toggleHold()
        runCurrent()
        assertEquals(listOf(true, false), r.voice.mutes)
        assertFalse(r.vm.state.value.isOnHold)
        assertFalse(r.vm.state.value.isMuted)
        r.vm.toggleMute()
        runCurrent()
        r.vm.holdFromTelecom(false)
        runCurrent()
        assertEquals(listOf(true, false, true), r.voice.mutes)
        assertTrue(r.vm.state.value.isMuted)
    }

    @Test
    fun repeatedSystemHoldDoesNotForgetTheUsersMuteState() = runTest {
        val r = connected()
        r.voice.holdFailure = VoiceError.invalidCallState
        r.vm.holdFromTelecom(true)
        runCurrent()
        r.vm.holdFromTelecom(true)
        runCurrent()
        r.vm.holdFromTelecom(false)
        runCurrent()
        assertEquals(listOf(true, false), r.voice.mutes)
        assertFalse(r.vm.state.value.isMuted)
    }

    @Test
    fun statsStartWhenTheFirstSnapshotReadIsAlreadyConnected() = runTest {
        val r = rig(connected = false)
        // The call connects before the view model's collector first runs: its `Connecting` snapshot is never read.
        r.vm.connect(r.voice, "token")
        r.voice.connected()
        advanceTimeBy(1000)
        runCurrent()
        assertNotNull(r.vm.state.value.stats)
        assertEquals(1, r.telecom!!.connected)
    }

    @Test
    fun statsStopAfterTheEnd() = runTest {
        val r = connected()
        advanceTimeBy(2000)
        runCurrent()
        r.voice.ended(Call.EndReason.RemoteHangup)
        runCurrent()
        val calls = r.voice.statsCalls
        assertTrue(calls >= 1)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(calls, r.voice.statsCalls)
    }

    @Test
    fun systemHoldTheSdkRefusesMutesInstead() = runTest {
        val r = connected()
        r.voice.holdFailure = VoiceError.invalidCallState
        r.vm.holdFromTelecom(true)
        runCurrent()
        assertEquals(listOf(true), r.voice.mutes)
        assertTrue(r.vm.state.value.isOnHold)
        r.vm.holdFromTelecom(false)
        runCurrent()
        assertEquals(listOf(true, false), r.voice.mutes)
        assertFalse(r.vm.state.value.isOnHold)
    }

    @Test
    fun systemHoldFallbackKeepsTheUsersOwnMute() = runTest {
        val r = connected()
        r.vm.toggleMute()
        runCurrent()
        r.voice.holdFailure = VoiceError.invalidCallState
        r.vm.holdFromTelecom(true)
        runCurrent()
        r.vm.holdFromTelecom(false)
        runCurrent()
        assertEquals(listOf(true, true), r.voice.mutes)
        assertTrue(r.vm.state.value.isMuted)
        assertFalse(r.vm.state.value.isOnHold)
    }

    @Test
    fun systemMuteIsAppliedOnce() = runTest {
        val r = connected()
        r.vm.muteFromTelecom(true)
        runCurrent()
        r.vm.muteFromTelecom(true)
        runCurrent()
        assertEquals(listOf(true), r.voice.mutes)
    }

    @Test
    fun aMuteTheSdkRefusesLeavesTheStateAsItWas() = runTest {
        val r = connected()
        r.voice.muteFailure = VoiceError.invalidCallState
        r.vm.toggleMute()
        runCurrent()
        assertFalse(r.vm.state.value.isMuted)
        assertNotNull(r.vm.state.value.lastError)
        r.voice.muteFailure = null
        r.vm.toggleMute()
        runCurrent()
        assertTrue(r.vm.state.value.isMuted)
        assertEquals(listOf(true), r.voice.mutes)
    }

    @Test
    fun aHangupFromTelecomIsLoggedAsSuch() = runTest {
        val r = connected()
        r.vm.hangupFromTelecom()
        assertEquals(1, r.voice.hangups)
        assertTrue(r.logs.entries.value.any { it.message == "hangup (from Telecom)" }, r.logs.exportText)
        assertTrue(r.logs.entries.value.none { it.message == "hangup" })
    }

    @Test
    fun hangupRequestedIsKnownBeforeAndAfterConnect() = runTest {
        val r = rig(connected = false)
        assertFalse(r.vm.hangupRequested)
        r.vm.hangup()
        assertTrue(r.vm.hangupRequested)
        val other = rig()
        other.vm.hangupFromTelecom()
        assertTrue(other.vm.hangupRequested)
    }

    @Test
    fun withoutTelecomCommandsGoStraightToTheClient() = runTest {
        val r = connected(withTelecom = false)
        r.vm.toggleMute()
        r.vm.toggleHold()
        r.vm.send("5")
        r.vm.hangup()
        assertEquals(listOf(true), r.voice.mutes)
        assertEquals(listOf(true), r.voice.holds)
        assertEquals(listOf("5"), r.voice.digits)
        assertEquals(1, r.voice.hangups)
        r.voice.ended(Call.EndReason.LocalHangup)
        pastEndTone()
        assertEquals(1, r.endReported)
    }

    @Test
    fun errorTextClearsAfterFourSeconds() = runTest {
        val r = connected()
        r.voice.holdFailure = VoiceError.invalidCallState
        r.vm.toggleHold()
        assertNotNull(r.vm.state.value.lastError)
        advanceTimeBy(3999)
        runCurrent()
        assertNotNull(r.vm.state.value.lastError)
        advanceTimeBy(1)
        runCurrent()
        assertNull(r.vm.state.value.lastError)
    }

    @Test
    fun aCommandFromTheScreenOutlivesTheScreen() = runTest {
        val r = rig(withTelecom = false)
        val gate = CompletableDeferred<Unit>()
        val screen = Job()
        launch(screen) {
            r.vm.perform {
                gate.await()
                hangup()
            }
        }
        runCurrent()
        screen.cancel()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, r.voice.hangups)
    }

    @Test
    fun theSnapshotIsMirroredIntoTheState() = runTest {
        val r = rig()
        assertEquals("call-1", r.vm.state.value.callID)
        assertEquals(Phase.Connecting, r.vm.state.value.phase)
        r.voice.change { state = Call.State.Ringing; hasEarlyMedia = true }
        runCurrent()
        assertEquals(Phase.Ringing(earlyMedia = true), r.vm.state.value.phase)
        r.voice.connected()
        runCurrent()
        assertEquals(Phase.Connected, r.vm.state.value.phase)
        r.voice.change { qualityWarnings = setOf(CallQualityWarning.HighRTT) }
        runCurrent()
        assertEquals(setOf(CallQualityWarning.HighRTT), r.vm.state.value.warnings)
        r.voice.change { state = Call.State.Reconnecting; error = VoiceError("ice_failed", "ICE failed") }
        runCurrent()
        assertEquals(Phase.Reconnecting("ice_failed"), r.vm.state.value.phase)
        r.voice.change { state = Call.State.Connected; error = null; qualityWarnings = emptySet() }
        runCurrent()
        assertEquals(Phase.Connected, r.vm.state.value.phase)
        assertTrue(r.vm.state.value.warnings.isEmpty())
        assertEquals(1, r.telecom!!.connected) // a reconnect is not a second answer
        val error = VoiceError("sip_failure", "Forbidden", sipStatus = 403)
        r.voice.ended(Call.EndReason.Failed, error)
        pastEndTone()
        assertEquals(Phase.Ended(Call.EndReason.Failed, error), r.vm.state.value.phase)
        assertEquals(listOf(TelecomEndCause.Error), r.telecom.ended)
        val log = r.logs.entries.value.map { it.message }
        assertTrue("reconnected" in log, r.logs.exportText)
        assertTrue("reconnecting: ice_failed" in log, r.logs.exportText)
    }

    @Test
    fun aPreviousCallStillInTheFlowIsIgnored() = runTest {
        val r = rig(connected = false)
        // The client keeps its last call after it ended, until the next connect.
        r.voice.call.value = Call {
            callID = "call-0"
            state = Call.State.Disconnected
            endReason = Call.EndReason.RemoteHangup
        }
        r.vm.connect(r.voice, "token")
        pastEndTone()
        assertEquals("call-1", r.vm.state.value.callID)
        assertEquals(Phase.Connecting, r.vm.state.value.phase)
        assertTrue(r.telecom!!.ended.isEmpty())
        assertEquals(0, r.endReported)
    }

    @Test
    fun theEndIsSeenWhenTheSnapshotsBeforeItWereNot() = runTest {
        val r = rig()
        // Written faster than the collector reads: the StateFlow keeps only the last one, the end.
        r.voice.change { state = Call.State.Ringing }
        r.voice.connected()
        r.voice.ended(Call.EndReason.RemoteHangup)
        pastEndTone()
        assertEquals(Phase.Ended(Call.EndReason.RemoteHangup, null), r.vm.state.value.phase)
        assertEquals(listOf(TelecomEndCause.Remote), r.telecom!!.ended)
        assertEquals(1, r.endReported)
    }

    @Test
    fun anotherCallInTheFlowEndsTheFollowing() = runTest {
        val r = connected()
        r.voice.call.value = Call { callID = "call-2"; state = Call.State.Disconnected; endReason = Call.EndReason.Busy }
        pastEndTone()
        assertEquals(Phase.Connected, r.vm.state.value.phase)
        assertTrue(r.telecom!!.ended.isEmpty())
        // Snapshots of this view model's call are no longer read either.
        r.voice.call.value = Call { callID = "call-1"; state = Call.State.Disconnected; endReason = Call.EndReason.RemoteHangup }
        pastEndTone()
        assertEquals(Phase.Connected, r.vm.state.value.phase)
    }
}
