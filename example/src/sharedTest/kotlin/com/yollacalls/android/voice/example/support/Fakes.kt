//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.support

import com.yollacalls.android.voice.Call
import com.yollacalls.android.voice.CallStats
import com.yollacalls.android.voice.VoiceError
import com.yollacalls.android.voice.example.app.CallPermissions
import com.yollacalls.android.voice.example.app.CallServiceControlling
import com.yollacalls.android.voice.example.app.DirectAudioRouting
import com.yollacalls.android.voice.example.app.PermissionRequesting
import com.yollacalls.android.voice.example.app.TelecomSession
import com.yollacalls.android.voice.example.app.TelecomStarting
import com.yollacalls.android.voice.example.app.TokenSupplying
import com.yollacalls.android.voice.example.app.VoiceConnecting
import com.yollacalls.android.voice.example.settings.KeyValueStore
import com.yollacalls.android.voice.example.settings.Settings
import com.yollacalls.android.voice.example.telecom.AudioRoute
import com.yollacalls.android.voice.example.telecom.AudioRouting
import com.yollacalls.android.voice.example.telecom.TelecomCallEvents
import com.yollacalls.android.voice.example.telecom.TelecomControlling
import com.yollacalls.android.voice.example.telecom.TelecomEndCause
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** An in-memory [KeyValueStore] for [com.yollacalls.android.voice.example.settings.SettingsStore]. */
class MemoryStore : KeyValueStore {
    val map: MutableMap<String, String> = java.util.Collections.synchronizedMap(mutableMapOf())
    override fun get(key: String) = map[key]
    override fun set(key: String, value: String) { map[key] = value }
}

class FakeTelecom : TelecomControlling {
    val held = mutableListOf<Boolean>()
    val ended = mutableListOf<TelecomEndCause>()
    var connected = 0
    /** What [setHeld] answers; false = Telecom refuses. */
    var holdAnswer = true

    override suspend fun setHeld(held: Boolean): Boolean {
        this.held += held
        return holdAnswer
    }
    override suspend fun reportConnected() { connected++ }
    override suspend fun reportEnded(cause: TelecomEndCause) { ended += cause }
}

/** Also the direct routing: counts [reset]s. */
class FakeAudioRouting : DirectAudioRouting {
    override val routes = MutableStateFlow<List<AudioRoute>>(emptyList())
    override val current = MutableStateFlow<AudioRoute?>(null)
    val selected: MutableList<AudioRoute> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile var resets = 0
    override suspend fun select(route: AudioRoute) {
        selected += route
        current.value = route
    }
    override fun reset() { resets++ }
}

class FakeTelecomSession(
    val telecom: FakeTelecom = FakeTelecom(),
    val routing: FakeAudioRouting = FakeAudioRouting(),
) : TelecomSession, TelecomControlling by telecom, AudioRouting by routing {
    val ended: List<TelecomEndCause> get() = telecom.ended
}

/**
 * The client: [call] is its flow, which tests write snapshots into ([change], [connected], [ended]); the commands are
 * recorded. Like the SDK, [connect] puts the new call's `Connecting` snapshot into [call] before it returns, and a
 * mute or hold that succeeds shows in the call's next snapshot. Records "connect" into [events]; [connectError] is
 * thrown by connect, as a call error, and then [call] does not change.
 */
class FakeVoice(private val events: MutableList<String>) : VoiceConnecting {
    override val call = MutableStateFlow<Call?>(null)
    var connectError: Throwable? = null
    @Volatile var connects = 0
    val mutes: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    val holds: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    val digits: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var hangups = 0
    /** When set, [setOnHold] throws it instead of recording. */
    var holdFailure: Exception? = null
    /** When set, [setMuted] throws it instead of recording. */
    var muteFailure: Exception? = null
    var statsCalls = 0

    /** The calls get the ids "call-1", "call-2", … in the order of the connects. */
    override fun connect(callToken: String) {
        events += "connect"
        connects++
        connectError?.let { throw it }
        call.value = Call { callID = "call-$connects"; state = Call.State.Connecting }
    }

    /** Writes the current call's next snapshot. */
    fun change(block: Call.Builder.() -> Unit) {
        call.value = call.value!!.toBuilder().apply(block).build()
    }

    fun connected() = change {
        state = Call.State.Connected
        wasConnected = true
    }

    fun ended(reason: Call.EndReason, error: VoiceError? = null) = change {
        state = Call.State.Disconnected
        endReason = reason
        this.error = error
    }

    override fun hangup() { hangups++ }

    override suspend fun setMuted(muted: Boolean) {
        muteFailure?.let { throw it }
        mutes += muted
        change { isMuted = muted }
    }

    override suspend fun setOnHold(hold: Boolean) {
        holdFailure?.let { throw it }
        holds += hold
        change { isOnHold = hold }
    }

    override suspend fun sendDigits(digits: String) { this.digits += digits }

    override suspend fun stats(): CallStats {
        statsCalls++
        return CallStats {}
    }
}

/** Records "telecom.start"; [gate] makes the start suspend until Telecom "accepts". */
class FakeTelecomStarter(private val log: MutableList<String>) : TelecomStarting {
    val session = FakeTelecomSession()
    var error: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun startCall(uuid: UUID, number: String, events: TelecomCallEvents): TelecomSession {
        log += "telecom.start"
        gate?.await()
        error?.let { throw it }
        return session
    }
}

class FakeService(private val events: MutableList<String>) : CallServiceControlling {
    data class Start(val number: String, val microphoneGranted: Boolean, val telecom: Boolean)

    val starts = mutableListOf<Start>()

    override fun start(number: String, microphoneGranted: Boolean, telecom: Boolean) {
        events += "service.start"
        starts += Start(number, microphoneGranted, telecom)
    }

    override fun stop() { events += "service.stop" }
}

class FakePermissions(
    private val events: MutableList<String>,
    var grants: CallPermissions = CallPermissions(microphone = true, notifications = true, bluetooth = true),
) : PermissionRequesting {
    override suspend fun requestCallPermissions(): CallPermissions {
        events += "permissions"
        return grants
    }
}

class FakeTokens(private val events: MutableList<String>, var token: String = "token-1") : TokenSupplying {
    var error: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null
    var requests = 0

    override suspend fun token(settings: Settings, number: String): String {
        events += "token"
        requests++
        gate?.await()
        error?.let { throw it }
        return token
    }
}

/** Records what Telecom asked for, as "hangup", "hold"/"unhold", "mute"/"unmute". Safe from any thread. */
class RecordingTelecomEvents : TelecomCallEvents {
    val events: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    override suspend fun hangupFromTelecom() { events += "hangup" }
    override suspend fun holdFromTelecom(hold: Boolean) { events += if (hold) "hold" else "unhold" }
    override suspend fun muteFromTelecom(muted: Boolean) { events += if (muted) "mute" else "unmute" }
}
