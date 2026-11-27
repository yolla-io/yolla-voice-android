//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallException
import com.yollacalls.android.voice.example.telecom.TelecomStartException
import com.yollacalls.android.voice.example.telecom.awaitTelecomStart
import com.yollacalls.android.voice.example.telecom.boundedTelecomRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A refused or stuck Telecom start is an ordinary exception: AppModel reads a CancellationException as "cancelled".
 * A Telecom request that never answers is bounded: AppModel awaits the end report before it stops the service.
 */
class TelecomStartTests {
    @Test
    fun theStartedValueIsReturned() = runTest {
        val started = CompletableDeferred("call")
        assertEquals("call", awaitTelecomStart(started, 10.seconds))
    }

    @Test
    fun aTimeoutIsNotACancellation() = runTest {
        val error = catching { awaitTelecomStart(CompletableDeferred<String>(), 10.seconds) }
        assertTrue(error is TelecomStartException, "$error")
        assertFalse(error is CancellationException)
        assertEquals("Telecom did not answer in time", error?.message)
    }

    @Test
    fun aTelecomRefusalReadsAsOne() = runTest {
        val started = CompletableDeferred<String>().apply {
            completeExceptionally(CallException(CallException.ERROR_CALL_NOT_PERMITTED_AT_PRESENT_TIME))
        }
        val error = catching { awaitTelecomStart(started, 10.seconds) }
        assertTrue(error is TelecomStartException, "$error")
        assertEquals("Telecom refused the call (code 5)", error?.message)
    }

    @Test
    fun aCancelledStartIsNotACancellationOfTheCaller() = runTest {
        val started = CompletableDeferred<String>().apply { cancel() }
        val error = catching { awaitTelecomStart(started, 10.seconds) }
        assertTrue(error is TelecomStartException, "$error")
    }

    @Test
    fun aRefusalIsPassedOn() = runTest {
        val refusal = IllegalStateException("refused")
        val started = CompletableDeferred<String>().apply { completeExceptionally(refusal) }
        // Stack-trace recovery may hand over a copy: same type and message.
        val error = catching { awaitTelecomStart(started, 10.seconds) }
        assertTrue(error is IllegalStateException, "$error")
        assertEquals(refusal.message, error?.message)
    }

    @Test
    fun theCallerBeingCancelledStaysACancellation() = runTest {
        var error: Throwable? = null
        val job = launch { error = catching { awaitTelecomStart(CompletableDeferred<String>(), 10.seconds) } }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertTrue(error is CancellationException, "$error")
    }

    @Test
    fun aTelecomRequestThatNeverAnswersTimesOut() = runTest {
        val result = boundedTelecomRequest(5.seconds) { awaitCancellation() }
        assertEquals(CallControlResult.Error(CallException.ERROR_OPERATION_TIMED_OUT), result)
    }

    @Test
    fun aTelecomRequestsAnswerIsPassedOn() = runTest {
        assertEquals(CallControlResult.Success(), boundedTelecomRequest(5.seconds) { CallControlResult.Success() })
    }

    private suspend fun catching(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (e: Throwable) {
        e
    }
}
