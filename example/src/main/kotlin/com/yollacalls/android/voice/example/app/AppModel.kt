//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.app

import com.yollacalls.android.voice.VoiceError
import com.yollacalls.android.voice.example.call.CallViewModel
import com.yollacalls.android.voice.example.logs.LogStore
import com.yollacalls.android.voice.example.settings.Settings
import com.yollacalls.android.voice.example.settings.SettingsStore
import com.yollacalls.android.voice.example.settings.TokenSource
import com.yollacalls.android.voice.example.telecom.AudioRouting
import com.yollacalls.android.voice.example.telecom.TelecomEndCause
import java.io.IOException
import java.net.URI
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the settings, the logs and the active call. Main-thread confined; [scope] is the main scope, so a call
 * started from a screen outlives that screen.
 */
class AppModel(
    private val settingsStore: SettingsStore,
    val logs: LogStore,
    private val voice: () -> VoiceConnecting,
    private val telecom: TelecomStarting,
    private val service: CallServiceControlling,
    private val permissions: PermissionRequesting,
    private val tokens: TokenSupplying,
    private val directRouting: DirectAudioRouting,
    private val scope: CoroutineScope,
) {
    private val mutableSettings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<Settings> = mutableSettings

    private val mutableActiveCall = MutableStateFlow<CallViewModel?>(null)
    val activeCall: StateFlow<CallViewModel?> = mutableActiveCall

    private val mutableAudioRouting = MutableStateFlow<AudioRouting?>(null)
    /** The Telecom session, or the direct routing, while a call is up. */
    val audioRouting: StateFlow<AudioRouting?> = mutableAudioRouting

    private val mutableAlert = MutableStateFlow<String?>(null)
    val alert: StateFlow<String?> = mutableAlert

    private val mutableIsPlacing = MutableStateFlow(false)
    val isPlacing: StateFlow<Boolean> = mutableIsPlacing

    /** Created on the first call and kept. */
    private var client: VoiceConnecting? = null

    fun updateSettings(settings: Settings) {
        settingsStore.save(settings)
        mutableSettings.value = settings
        logs.app("settings saved")
    }

    fun dismissAlert() {
        mutableAlert.value = null
    }

    /** Runs in [scope], not in the caller's. A second call while one is starting is ignored. */
    fun placeCall(number: String): Job {
        if (mutableIsPlacing.value) return Job().apply { complete() }
        mutableIsPlacing.value = true
        return scope.launch {
            try {
                start(number)
            } finally {
                mutableIsPlacing.value = false
            }
        }
    }

    fun dismissCall(call: CallViewModel) {
        if (mutableActiveCall.value !== call) return
        mutableActiveCall.value = null
        mutableAudioRouting.value = null
    }

    private suspend fun start(number: String) {
        val settings = mutableSettings.value
        val problems = settings.validate()
        if (problems.isNotEmpty()) {
            mutableAlert.value = problems.joinToString("\n")
            return
        }
        if (number.isBlank()) return
        if (mutableActiveCall.value?.state?.value?.phase?.isActive == true) {
            mutableAlert.value = "A call is already active"
            return
        }
        val uuid = UUID.randomUUID()
        logs.app("call $number via ${if (settings.useTelecom) "Telecom" else "direct"}")

        val granted = permissions.requestCallPermissions()
        if (!granted.notifications) logs.app("notifications are off: the call notification will not be shown")

        val token = try {
            tokens.token(settings, number)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val text = describe(e, settings)
            logs.app("[token] $text")
            mutableAlert.value = text
            return
        }

        val direct = !settings.useTelecom
        lateinit var viewModel: CallViewModel
        viewModel = CallViewModel(uuid, number, logs, scope, onEndReported = { callEnded(viewModel, direct) })
        mutableActiveCall.value = viewModel
        var session: TelecomSession? = null
        try {
            service.start(number, granted.microphone, telecom = !direct)
            if (direct) {
                mutableAudioRouting.value = directRouting
            } else {
                session = telecom.startCall(uuid, number, viewModel)
                viewModel.telecom = session
                mutableAudioRouting.value = session
            }
        } catch (e: CancellationException) {
            cleanUp(viewModel, session, TelecomEndCause.Error, direct)
            throw e
        } catch (e: Throwable) {
            failedToStart(viewModel, session, direct, e, settings)
            return
        }
        // Hung up while the service or Telecom was starting: no INVITE goes out at all.
        if (viewModel.hangupRequested) {
            logs.app("call ended before connect")
            cleanUp(viewModel, session, TelecomEndCause.Local, direct)
            return
        }
        try {
            // The token carries the destination and the caller id; the platform tells the SDK which gateway to use.
            // `connect` returns at once: from here on a hang-up goes to the client, and a failure of the set-up
            // reaches the view model as the call's `Disconnected` snapshot.
            viewModel.connect(voiceClient(), token)
        } catch (e: Throwable) {
            // No call started (`connect` throws only then). Throwable, not Exception: a LinkageError must not skip the cleanup.
            failedToStart(viewModel, session, direct, e, settings)
        }
    }

    /** The call could not start: clean up, and tell the user unless they had already hung up. */
    private suspend fun failedToStart(
        viewModel: CallViewModel,
        session: TelecomSession?,
        direct: Boolean,
        e: Throwable,
        settings: Settings,
    ) {
        val hungUp = viewModel.hangupRequested
        cleanUp(viewModel, session, if (hungUp) TelecomEndCause.Local else TelecomEndCause.Error, direct)
        if (e is VirtualMachineError) throw e
        val text = describe(e, settings)
        logs.app("connect failed: $text")
        // The user already asked to hang up: the failure is not news to them.
        if (hungUp) return
        // An alert raised while the call screen is still closing is lost: let it close first.
        scope.launch {
            delay(ALERT_DELAY)
            mutableAlert.value = text
        }
    }

    private suspend fun cleanUp(viewModel: CallViewModel, session: TelecomSession?, cause: TelecomEndCause, direct: Boolean) =
        withContext(NonCancellable) {
            if (mutableActiveCall.value === viewModel) {
                mutableActiveCall.value = null
                mutableAudioRouting.value = null
            }
            if (direct) directRouting.reset()
            session?.reportEnded(cause)
            service.stop()
        }

    /**
     * After a normal end the audio is released, the direct route reset and the service stopped; a newer call keeps what
     * it owns. The ended call is dismissed [DISMISS_AFTER] after its end, whether or not a screen is showing it.
     */
    private fun callEnded(viewModel: CallViewModel, direct: Boolean) {
        scope.launch {
            delay((DISMISS_AFTER - viewModel.endToneDelay).coerceAtLeast(Duration.ZERO))
            dismissCall(viewModel)
        }
        val current = mutableActiveCall.value
        if (current != null && current !== viewModel) return
        if (direct) directRouting.reset()
        service.stop()
    }

    private fun voiceClient(): VoiceConnecting = client ?: voice().also { client = it }

    private fun describe(error: Throwable, settings: Settings): String = when (error) {
        is VoiceError -> "${error.code}: ${error.message}"
        is IOException -> {
            val host = hostOf(settings)?.let { " ($it)" } ?: ""
            "Token request failed$host: ${error.message}"
        }
        else -> error.message ?: error.toString()
    }

    private fun hostOf(settings: Settings): String? {
        val source = settings.tokenSource as? TokenSource.Url ?: return null
        return try {
            URI(source.url.trim()).host
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val ALERT_DELAY = 700.milliseconds

        /** How long an ended call stays on screen, counted from its end (the call's `Disconnected` snapshot). */
        val DISMISS_AFTER = 2.seconds
    }
}
