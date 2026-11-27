//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.app

import com.yollacalls.android.voice.Call
import com.yollacalls.android.voice.CallStats
import com.yollacalls.android.voice.example.settings.Settings
import com.yollacalls.android.voice.example.telecom.AudioRouting
import com.yollacalls.android.voice.example.telecom.TelecomCallEvents
import com.yollacalls.android.voice.example.telecom.TelecomControlling
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

/** The SDK client as the app uses it. */
interface VoiceConnecting {
    val call: StateFlow<Call?>

    /** Throws [com.yollacalls.android.voice.VoiceError] when the call cannot start at all. */
    fun connect(callToken: String)

    /** Returns at once; the end comes in [call]. */
    fun hangup()
    suspend fun setMuted(muted: Boolean)
    suspend fun setOnHold(hold: Boolean)
    suspend fun sendDigits(digits: String)
    suspend fun stats(): CallStats?
}

/** One call's Telecom handle that also exposes the audio routes Telecom manages. */
interface TelecomSession : TelecomControlling, AudioRouting

interface TelecomStarting {
    /**
     * Returns once Telecom accepted the call; throws when it refuses or does not answer in time. A refusal or a timeout
     * is an ordinary exception, never a [kotlinx.coroutines.CancellationException] ([AppModel] reads that as "the scope
     * was cancelled" and shows no alert); only the caller's own cancellation is one.
     */
    suspend fun startCall(uuid: UUID, number: String, events: TelecomCallEvents): TelecomSession
}

interface CallServiceControlling {
    /** [telecom]: the call goes through Telecom; it decides the foreground-service type with [microphoneGranted]. */
    fun start(number: String, microphoneGranted: Boolean, telecom: Boolean)
    fun stop()
}

/** The audio routes without Telecom; [reset] puts the output back to the default once a direct-mode call is over. */
interface DirectAudioRouting : AudioRouting {
    fun reset()
}

data class CallPermissions(val microphone: Boolean, val notifications: Boolean, val bluetooth: Boolean)

interface PermissionRequesting {
    /**
     * Asks for what is missing; without an Activity, with a request already in flight or when the Activity goes away
     * mid-request returns the current grants. Never throws ([AppModel] does not catch around it).
     */
    suspend fun requestCallPermissions(): CallPermissions
}

fun interface TokenSupplying {
    suspend fun token(settings: Settings, number: String): String
}
