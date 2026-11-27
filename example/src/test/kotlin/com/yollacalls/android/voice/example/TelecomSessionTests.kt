//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import android.os.ParcelUuid
import android.telecom.DisconnectCause
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallException
import com.yollacalls.android.voice.example.app.TelecomSession
import com.yollacalls.android.voice.example.logs.LogStore
import com.yollacalls.android.voice.example.support.RecordingTelecomEvents
import com.yollacalls.android.voice.example.telecom.TelecomCallManager
import com.yollacalls.android.voice.example.telecom.TelecomCalls
import com.yollacalls.android.voice.example.telecom.TelecomEndCause
import com.yollacalls.android.voice.example.telecom.TelecomStartException
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [TelecomCallManager]'s session on a fake `CallsManager`: what the system does and what reaches the app's log. */
@OptIn(ExperimentalCoroutinesApi::class)
class TelecomSessionTests {
    private class FakeControl(override val coroutineContext: CoroutineContext = EmptyCoroutineContext) : CallControlScope {
        var setActiveAnswer: CallControlResult = CallControlResult.Success()
        override fun getCallId(): ParcelUuid = throw UnsupportedOperationException()
        override suspend fun setActive(): CallControlResult = setActiveAnswer
        override suspend fun setInactive(): CallControlResult = CallControlResult.Success()
        override suspend fun answer(callType: Int): CallControlResult = CallControlResult.Error(CallException.ERROR_UNKNOWN)
        override suspend fun disconnect(disconnectCause: DisconnectCause): CallControlResult =
            throw UnsupportedOperationException("the manager disconnects through TelecomCalls")
        override suspend fun requestEndpointChange(endpoint: CallEndpointCompat): CallControlResult =
            CallControlResult.Error(CallException.ERROR_UNKNOWN)
        override val currentCallEndpoint: Flow<CallEndpointCompat> = emptyFlow()
        override val availableEndpoints: Flow<List<CallEndpointCompat>> = emptyFlow()
        override val isMuted = MutableSharedFlow<Boolean>()
    }

    /** `addCall` enters its block at once (or when [admit] completes) and returns when [ended] completes. */
    private class FakeCalls : TelecomCalls {
        val control = FakeControl()
        val ended = CompletableDeferred<Unit>()
        var admit: CompletableDeferred<Unit>? = null
        var refusal: Throwable? = null
        var disconnectAnswer: suspend () -> CallControlResult = {
            ended.complete(Unit)
            CallControlResult.Success()
        }
        val disconnects = mutableListOf<TelecomEndCause>()
        var addCallCancelled = false
        lateinit var onDisconnect: suspend (String) -> Unit
        lateinit var onSetActive: suspend () -> Unit
        lateinit var onSetInactive: suspend () -> Unit

        override fun register() {}

        override suspend fun addCall(
            number: String,
            onDisconnect: suspend (cause: String) -> Unit,
            onSetActive: suspend () -> Unit,
            onSetInactive: suspend () -> Unit,
            block: CallControlScope.() -> Unit,
        ) {
            refusal?.let { throw it }
            this.onDisconnect = onDisconnect
            this.onSetActive = onSetActive
            this.onSetInactive = onSetInactive
            try {
                admit?.await()
                control.block()
                ended.await()
            } catch (e: CancellationException) {
                addCallCancelled = true
                throw e
            }
        }

        override suspend fun disconnect(control: CallControlScope, cause: TelecomEndCause): CallControlResult {
            disconnects += cause
            return disconnectAnswer()
        }
    }

    private class Rig(test: TestScope) {
        val calls = FakeCalls()
        val logs = LogStore()
        val events = RecordingTelecomEvents()
        val manager = TelecomCallManager(test.backgroundScope, logs, calls)

        suspend fun start(): TelecomSession = manager.startCall(UUID.randomUUID(), "1001", events)
        fun log(): List<String> = logs.entries.value.map { it.message }
    }

    @Test
    fun anAcceptedCallIsLogged() = runTest {
        val rig = Rig(this)
        rig.start()
        assertEquals(listOf("[telecom] call accepted"), rig.log())
    }

    @Test
    fun aRefusalIsLoggedAndReadable() = runTest {
        val rig = Rig(this)
        rig.calls.refusal = CallException(CallException.ERROR_CALL_NOT_PERMITTED_AT_PRESENT_TIME)
        val error = runCatching { rig.start() }.exceptionOrNull()
        assertTrue(error is TelecomStartException, "$error")
        assertEquals("Telecom refused the call (code 5)", error?.message)
        assertEquals(listOf("[telecom] start failed: Telecom refused the call (code 5)"), rig.log())
    }

    @Test
    fun aStartTelecomDoesNotAnswerIsLogged() = runTest {
        val rig = Rig(this)
        rig.calls.admit = CompletableDeferred()
        val error = runCatching { rig.start() }.exceptionOrNull()
        assertEquals("Telecom did not answer in time", error?.message)
        assertEquals(listOf("[telecom] start failed: Telecom did not answer in time"), rig.log())
    }

    @Test
    fun aSystemCallLostWithoutADisconnectHangsUp() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.calls.ended.complete(Unit)
        runCurrent()
        assertEquals(listOf("hangup"), rig.events.events)
        assertTrue("[telecom] the system ended the call without a disconnect" in rig.log(), "${rig.log()}")
    }

    @Test
    fun aReportedEndIsNotHungUpAgain() = runTest {
        val rig = Rig(this)
        val session = rig.start()
        session.reportEnded(TelecomEndCause.Local)
        runCurrent()
        assertEquals(listOf(TelecomEndCause.Local), rig.calls.disconnects)
        assertTrue(rig.events.events.isEmpty())
        assertFalse(rig.calls.addCallCancelled)
    }

    @Test
    fun aDisconnectTelecomDoesNotAnswerFreesTheCall() = runTest {
        val rig = Rig(this)
        val session = rig.start()
        rig.calls.disconnectAnswer = { awaitCancellation() }
        launch { session.reportEnded(TelecomEndCause.Remote) }
        advanceTimeBy(4_999); runCurrent()
        assertFalse(rig.calls.addCallCancelled)
        advanceTimeBy(1); runCurrent()
        assertTrue(rig.calls.addCallCancelled)
        assertTrue("[telecom] disconnect timed out" in rig.log(), "${rig.log()}")
        // Our own end: the SDK call is not hung up again.
        assertTrue(rig.events.events.isEmpty())
    }

    @Test
    fun systemRequestsAreLogged() = runTest {
        val rig = Rig(this)
        rig.start()
        runCurrent()
        rig.calls.onSetInactive()
        rig.calls.onSetActive()
        rig.calls.control.isMuted.emit(true)
        runCurrent()
        rig.calls.onDisconnect("code 3")
        assertEquals(listOf("hold", "unhold", "mute", "hangup"), rig.events.events)
        assertEquals(
            listOf(
                "[telecom] call accepted",
                "[telecom] hold requested by the system",
                "[telecom] resume requested by the system",
                "[telecom] mute true requested by the system",
                "[telecom] disconnect requested by the system (code 3)",
            ),
            rig.log(),
        )
    }

    @Test
    fun aRefusedSetActiveIsLogged() = runTest {
        val rig = Rig(this)
        val session = rig.start()
        rig.calls.control.setActiveAnswer = CallControlResult.Error(CallException.ERROR_CALL_CANNOT_BE_SET_TO_ACTIVE)
        session.reportConnected()
        assertTrue("[telecom] setActive refused (code 4)" in rig.log(), "${rig.log()}")
    }
}
