//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.telecom

import kotlinx.coroutines.flow.StateFlow

data class AudioRoute(val id: String, val kind: Kind, val name: String) {
    enum class Kind { Earpiece, Speaker, Bluetooth, Wired, Other }
}

/** The audio outputs of the current call and the one in use. */
interface AudioRouting {
    val routes: StateFlow<List<AudioRoute>>
    val current: StateFlow<AudioRoute?>
    suspend fun select(route: AudioRoute)
}
