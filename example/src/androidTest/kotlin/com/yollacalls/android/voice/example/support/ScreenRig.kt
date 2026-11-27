//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.support

import com.yollacalls.android.voice.example.app.AppModel
import com.yollacalls.android.voice.example.logs.LogStore
import com.yollacalls.android.voice.example.settings.Settings
import com.yollacalls.android.voice.example.settings.SettingsStore
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** An [AppModel] on the shared fakes with a main-thread scope, for the UI tests. No network, no Telecom, no SDK. */
class ScreenRig(settings: Settings = Settings()) {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val voice = FakeVoice(events)
    val telecom = FakeTelecomStarter(events)
    val service = FakeService(events)
    val permissions = FakePermissions(events)
    val tokens = FakeTokens(events)
    val direct = FakeAudioRouting()
    val logs = LogStore()
    val store = MemoryStore()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val model: AppModel

    init {
        SettingsStore(store).save(settings)
        model = AppModel(SettingsStore(store), logs, { voice }, telecom, service, permissions, tokens, direct, scope)
    }

    fun close() = scope.cancel()
}
