//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.app

import android.app.Application
import android.util.Log
import com.yollacalls.android.voice.example.auth.TokenProvider
import com.yollacalls.android.voice.example.logs.LogStore
import com.yollacalls.android.voice.example.settings.SettingsStore
import com.yollacalls.android.voice.example.settings.SharedPreferencesStore
import com.yollacalls.android.voice.example.telecom.CallServiceController
import com.yollacalls.android.voice.example.telecom.DirectAudioRouter
import com.yollacalls.android.voice.example.telecom.ProximityLock
import com.yollacalls.android.voice.example.telecom.TelecomCallManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Writes `error <exception>` to the app's log for an exception that escaped a coroutine of the application scope (a
 * screen's command, a permission request), instead of crashing the app mid-call; [alsoTo] gets it too (logcat).
 * A [VirtualMachineError] is rethrown after it is logged.
 */
internal fun loggingExceptionHandler(logs: LogStore, alsoTo: (Throwable) -> Unit = {}): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, e ->
        logs.app("error $e")
        alsoTo(e)
        if (e is VirtualMachineError) throw e
    }

/** Builds the one [AppModel] of the process from the real implementations. */
class ExampleApplication : Application() {
    /** The app's own log; created first, so that the scope's exception handler can write to it. */
    private val logs = LogStore()

    /** Main-thread scope of the process: calls outlive every screen. An exception that escapes it is logged. */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            loggingExceptionHandler(logs) { Log.e("YollaVoiceExample", "uncaught in the app scope", it) },
    )

    /** Set once in [onCreate]; a `var` so that an emulator test can put a model built on fakes in its place. */
    lateinit var appModel: AppModel

    /** The Activity attaches itself here so that [AppModel] can ask for permissions. */
    lateinit var permissions: ActivityPermissions
        private set

    override fun onCreate() {
        super.onCreate()
        permissions = ActivityPermissions(this)
        appModel = AppModel(
            settingsStore = SettingsStore(SharedPreferencesStore(this)),
            logs = logs,
            voice = { SdkVoice(this) },
            telecom = TelecomCallManager(this, scope, logs),
            service = CallServiceController(this, logs::app) { appModel.activeCall.value?.hangup() },
            permissions = permissions,
            tokens = { settings, number -> TokenProvider(settings).token(number) },
            directRouting = DirectAudioRouter(this),
            scope = scope,
        )
        holdProximityLock(appModel, ProximityLock(this))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun holdProximityLock(model: AppModel, lock: ProximityLock) {
        val callActive = model.activeCall.flatMapLatest { call ->
            call?.state?.map { it.phase.isActive } ?: flowOf(false)
        }
        val route = model.audioRouting.flatMapLatest { it?.current ?: flowOf(null) }
        combine(callActive, route) { active, current -> lock.update(active, current) }.launchIn(scope)
    }
}
