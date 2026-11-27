//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import com.yollacalls.android.voice.Call
import com.yollacalls.android.voice.VoiceError
import com.yollacalls.android.voice.example.app.AppModel
import com.yollacalls.android.voice.example.app.CallPermissions
import com.yollacalls.android.voice.example.app.loggingExceptionHandler
import com.yollacalls.android.voice.example.auth.TokenError
import com.yollacalls.android.voice.example.logs.LogStore
import com.yollacalls.android.voice.example.settings.Settings
import com.yollacalls.android.voice.example.settings.SettingsStore
import com.yollacalls.android.voice.example.settings.TokenSource
import com.yollacalls.android.voice.example.support.FakeAudioRouting
import com.yollacalls.android.voice.example.support.FakePermissions
import com.yollacalls.android.voice.example.support.FakeService
import com.yollacalls.android.voice.example.support.FakeTelecomStarter
import com.yollacalls.android.voice.example.support.FakeTokens
import com.yollacalls.android.voice.example.support.FakeVoice
import com.yollacalls.android.voice.example.support.MemoryStore
import com.yollacalls.android.voice.example.telecom.TelecomEndCause
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppModelTests {
    private class Rig(test: TestScope, settings: Settings = Settings()) {
        val events = mutableListOf<String>()
        val voice = FakeVoice(events)
        val telecom = FakeTelecomStarter(events)
        val service = FakeService(events)
        val permissions = FakePermissions(events)
        val tokens = FakeTokens(events)
        val direct = FakeAudioRouting()
        val logs = LogStore()
        val store = MemoryStore()
        val model: AppModel

        init {
            SettingsStore(store).save(settings)
            model = AppModel(
                SettingsStore(store), logs, { voice }, telecom, service, permissions, tokens, direct, test.backgroundScope,
            )
        }
    }

    @Test
    fun placeCallRunsTheStepsInOrder() = runTest {
        val rig = Rig(this)
        rig.model.placeCall("1001").join()
        assertEquals(listOf("permissions", "token", "service.start", "telecom.start", "connect"), rig.events)
        assertNotNull(rig.model.activeCall.value)
        assertSame(rig.telecom.session, rig.model.audioRouting.value)
        assertEquals("call-1", rig.model.activeCall.value!!.state.value.callID)
    }

    @Test
    fun directModeSkipsTelecom() = runTest {
        val rig = Rig(this, Settings(useTelecom = false))
        rig.model.placeCall("1001").join()
        assertEquals(listOf("permissions", "token", "service.start", "connect"), rig.events)
        assertSame(rig.direct, rig.model.audioRouting.value)
    }

    @Test
    fun invalidSettingsShowTheProblems() = runTest {
        val rig = Rig(this, Settings(tokenSource = TokenSource.Url("")))
        rig.model.placeCall("1001").join()
        assertEquals("Token URL or pasted token is required", rig.model.alert.value)
        assertTrue(rig.events.isEmpty())
    }

    @Test
    fun tokenFailureShowsNoCallScreen() = runTest {
        val rig = Rig(this)
        rig.tokens.error = TokenError.Http(500)
        rig.model.placeCall("1001").join()
        assertEquals("Token endpoint returned HTTP 500", rig.model.alert.value)
        assertNull(rig.model.activeCall.value)
        assertFalse("service.start" in rig.events)
        assertTrue(rig.logs.entries.value.any { it.message.startsWith("[token]") })
        assertFalse(rig.model.isPlacing.value)
    }

    @Test
    fun ioFailureNamesTheHost() = runTest {
        val rig = Rig(this, Settings(tokenSource = TokenSource.Url(" https://demo.yolla.io/api/calls ")))
        rig.tokens.error = IOException("timeout")
        rig.model.placeCall("1001").join()
        assertEquals("Token request failed (demo.yolla.io): timeout", rig.model.alert.value)
    }

    @Test
    fun telecomRefusalCleansUp() = runTest {
        val rig = Rig(this)
        rig.telecom.error = IllegalStateException("Telecom refused")
        rig.model.placeCall("1001").join()
        advanceTimeBy(701); runCurrent()
        assertNull(rig.model.activeCall.value)
        assertEquals("service.stop", rig.events.last())
        assertFalse("connect" in rig.events)
        assertEquals("Telecom refused", rig.model.alert.value)
        assertNull(rig.model.audioRouting.value)
    }

    @Test
    fun connectFailureCleansUp() = runTest {
        val rig = Rig(this)
        rig.voice.connectError = VoiceError.microphoneDenied
        rig.model.placeCall("1001").join()
        assertNull(rig.model.activeCall.value)
        assertEquals(listOf(TelecomEndCause.Error), rig.telecom.session.ended)
        assertEquals("service.stop", rig.events.last())
        assertNull(rig.model.alert.value)
        advanceTimeBy(699); runCurrent()
        assertNull(rig.model.alert.value)
        advanceTimeBy(2); runCurrent()
        assertEquals("microphone_denied: Microphone permission was not granted", rig.model.alert.value)
        assertTrue(rig.logs.entries.value.any { it.message == "connect failed: microphone_denied: Microphone permission was not granted" })
    }

    @Test
    fun secondPlaceCallWhileStartingIsIgnored() = runTest {
        val rig = Rig(this)
        rig.tokens.gate = CompletableDeferred()
        val first = rig.model.placeCall("1001")
        val second = rig.model.placeCall("1001")
        runCurrent()
        rig.tokens.gate!!.complete(Unit)
        first.join(); second.join()
        assertEquals(1, rig.tokens.requests)
        assertEquals(1, rig.voice.connects)
        assertNull(rig.model.alert.value)
    }

    @Test
    fun secondCallWhileOneIsActiveIsRefused() = runTest {
        val rig = Rig(this)
        rig.model.placeCall("1001").join()
        rig.model.placeCall("1002").join()
        assertEquals("A call is already active", rig.model.alert.value)
        assertEquals(1, rig.voice.connects)
    }

    @Test
    fun hangupDuringStartLeavesNothingBehind() = runTest {
        val rig = Rig(this)
        // `connect` has returned, the SDK still sets the call up: the hang-up goes to the call, which cancels it.
        rig.model.placeCall("1001").join()
        rig.model.activeCall.value!!.hangup()
        runCurrent()
        assertEquals(1, rig.voice.hangups)
        rig.voice.ended(Call.EndReason.Cancelled)
        advanceTimeBy(601); runCurrent()
        assertEquals("service.stop", rig.events.last())
    }

    @Test
    fun afterTheEndAudioIsOffAndTheServiceStops() = runTest {
        val rig = Rig(this)
        rig.model.placeCall("1001").join()
        val vm = rig.model.activeCall.value!!
        rig.voice.ended(Call.EndReason.RemoteHangup)
        advanceTimeBy(601); runCurrent()
        assertEquals("service.stop", rig.events.last())
        rig.model.dismissCall(vm)
        assertNull(rig.model.activeCall.value)
        assertNull(rig.model.audioRouting.value)
    }

    @Test
    fun deniedMicrophoneIsPassedToTheService() = runTest {
        val rig = Rig(this)
        rig.permissions.grants = CallPermissions(microphone = false, notifications = true, bluetooth = true)
        rig.model.placeCall("1001").join()
        assertEquals(listOf(FakeService.Start("1001", microphoneGranted = false, telecom = true)), rig.service.starts)
    }

    @Test
    fun theServiceIsToldTheMode() = runTest {
        val telecom = Rig(this)
        telecom.model.placeCall("1001").join()
        assertEquals(listOf(FakeService.Start("1001", microphoneGranted = true, telecom = true)), telecom.service.starts)
        val direct = Rig(this, Settings(useTelecom = false))
        direct.model.placeCall("1001").join()
        assertEquals(listOf(FakeService.Start("1001", microphoneGranted = true, telecom = false)), direct.service.starts)
    }

    @Test
    fun hangupWhileTelecomIsStartingLeavesNothingBehind() = runTest {
        val rig = Rig(this)
        rig.telecom.gate = CompletableDeferred()
        val job = rig.model.placeCall("1001")
        runCurrent()
        rig.model.activeCall.value!!.hangup()
        rig.telecom.gate!!.complete(Unit)
        job.join(); runCurrent()
        assertEquals(0, rig.voice.connects)
        assertNull(rig.model.activeCall.value)
        assertNull(rig.model.audioRouting.value)
        assertEquals(listOf(TelecomEndCause.Local), rig.telecom.session.ended)
        assertEquals("service.stop", rig.events.last())
        assertFalse(rig.model.isPlacing.value)
    }

    @Test
    fun hangupBeforeConnectPlacesNoCall() = runTest {
        val rig = Rig(this)
        rig.telecom.gate = CompletableDeferred()
        val job = rig.model.placeCall("1001")
        runCurrent()
        rig.model.activeCall.value!!.hangupFromTelecom()
        rig.telecom.gate!!.complete(Unit)
        job.join()
        advanceTimeBy(5_000); runCurrent()
        assertFalse("connect" in rig.events)
        assertNull(rig.model.alert.value)
        assertTrue(rig.logs.entries.value.none { it.message.startsWith("connect failed") })
        assertTrue(rig.logs.entries.value.any { it.message == "call ended before connect" })
    }

    @Test
    fun startFailureAfterAHangupShowsNoAlert() = runTest {
        // `connect` no longer suspends, so nothing can be hung up while it runs: the start that can still fail after a
        // hang-up is Telecom's.
        val rig = Rig(this)
        rig.telecom.gate = CompletableDeferred()
        rig.telecom.error = IllegalStateException("Telecom refused")
        val job = rig.model.placeCall("1001")
        runCurrent()
        rig.model.activeCall.value!!.hangup()
        rig.telecom.gate!!.complete(Unit)
        job.join()
        advanceTimeBy(5_000); runCurrent()
        assertNull(rig.model.alert.value)
        assertNull(rig.model.activeCall.value)
        assertTrue(rig.telecom.session.ended.isEmpty()) // Telecom never took the call: there is nothing to report to it
        assertFalse("connect" in rig.events)
        assertEquals("service.stop", rig.events.last())
    }

    @Test
    fun connectThrowingAnErrorStillCleansUp() = runTest {
        val rig = Rig(this)
        rig.voice.connectError = UnsatisfiedLinkError("no libjingle_peerconnection_so")
        rig.model.placeCall("1001").join()
        assertNull(rig.model.activeCall.value)
        assertEquals(listOf(TelecomEndCause.Error), rig.telecom.session.ended)
        assertEquals("service.stop", rig.events.last())
        assertFalse(rig.model.isPlacing.value)
        advanceTimeBy(701); runCurrent()
        assertEquals("no libjingle_peerconnection_so", rig.model.alert.value)
    }

    @Test
    fun theEndedCallIsDismissedTwoSecondsAfterTheEnd() = runTest {
        val rig = Rig(this)
        rig.model.placeCall("1001").join()
        val vm = rig.model.activeCall.value!!
        rig.voice.ended(Call.EndReason.RemoteHangup)
        advanceTimeBy(1_999); runCurrent()
        assertSame(vm, rig.model.activeCall.value)
        advanceTimeBy(1); runCurrent()
        assertNull(rig.model.activeCall.value)
        assertNull(rig.model.audioRouting.value)
    }

    @Test
    fun aNewerCallIsNotDismissedByAnOlderCallsTimer() = runTest {
        val rig = Rig(this)
        rig.model.placeCall("1001").join()
        val first = rig.model.activeCall.value!!
        rig.voice.ended(Call.EndReason.RemoteHangup)
        advanceTimeBy(1_000); runCurrent()
        rig.model.placeCall("1002").join()
        val second = rig.model.activeCall.value!!
        assertTrue(first !== second)
        // The first call's end, still in the client's flow, is not the second call's.
        assertEquals("call-2", second.state.value.callID)
        assertTrue(second.state.value.phase.isActive)
        advanceTimeBy(5_000); runCurrent()
        assertSame(second, rig.model.activeCall.value)
        assertSame(rig.telecom.session, rig.model.audioRouting.value)
    }

    @Test
    fun aDirectCallResetsTheRouteOnceAfterItsEnd() = runTest {
        val rig = Rig(this, Settings(useTelecom = false))
        rig.model.placeCall("1001").join()
        assertEquals(0, rig.direct.resets)
        rig.voice.ended(Call.EndReason.RemoteHangup)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, rig.direct.resets)
    }

    @Test
    fun aDirectConnectFailureResetsTheRoute() = runTest {
        val rig = Rig(this, Settings(useTelecom = false))
        rig.voice.connectError = VoiceError.microphoneDenied
        rig.model.placeCall("1001").join()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, rig.direct.resets)
    }

    @Test
    fun telecomModeNeverResetsTheDirectRoute() = runTest {
        val rig = Rig(this)
        rig.model.placeCall("1001").join()
        rig.voice.ended(Call.EndReason.RemoteHangup)
        advanceTimeBy(5_000); runCurrent()
        rig.voice.connectError = VoiceError.microphoneDenied
        rig.model.placeCall("1001").join()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, rig.direct.resets)
    }

    @Test
    fun anExceptionEscapingTheAppScopeIsLogged() = runTest {
        val logs = LogStore()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + loggingExceptionHandler(logs))
        scope.launch { throw IllegalStateException("boom") }
        runCurrent()
        assertEquals(listOf("error java.lang.IllegalStateException: boom"), logs.entries.value.map { it.message })
        scope.cancel()
    }

    @Test
    fun deniedNotificationsAreLogged() = runTest {
        val rig = Rig(this)
        rig.permissions.grants = CallPermissions(microphone = true, notifications = false, bluetooth = true)
        rig.model.placeCall("1001").join()
        assertTrue(rig.logs.entries.value.any { it.message == "notifications are off: the call notification will not be shown" })
    }

    @Test
    fun noTokenEverReachesTheLog() = runTest {
        val rig = Rig(this)
        rig.tokens.token = "SECRET-TOKEN"
        rig.model.placeCall("1001").join()
        rig.voice.connected()
        runCurrent()
        rig.voice.ended(Call.EndReason.RemoteHangup)
        advanceTimeBy(601); runCurrent()
        assertTrue(rig.logs.entries.value.isNotEmpty())
        assertTrue(rig.logs.entries.value.none { "SECRET-TOKEN" in it.message })
        assertFalse("SECRET-TOKEN" in rig.logs.exportText)
    }

    @Test
    fun placeCallSurvivesItsCallersScope() = runTest {
        val rig = Rig(this)
        val caller = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        caller.launch { rig.model.placeCall("1001") }
        runCurrent()
        caller.cancel()
        runCurrent()
        assertEquals(1, rig.voice.connects)
        assertNotNull(rig.model.activeCall.value)
    }

    @Test
    fun updateSettingsSavesAndLogs() = runTest {
        val rig = Rig(this)
        rig.model.updateSettings(Settings(useTelecom = false))
        assertFalse(rig.model.settings.value.useTelecom)
        assertFalse(SettingsStore(rig.store).load().useTelecom)
        assertTrue(rig.logs.entries.value.any { it.message == "settings saved" })
    }
}
