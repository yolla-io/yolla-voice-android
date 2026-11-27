//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.app

import android.content.Context
import com.yollacalls.android.voice.Call
import com.yollacalls.android.voice.CallStats
import com.yollacalls.android.voice.VoiceClient
import kotlinx.coroutines.flow.StateFlow

/** The one [VoiceClient] of the process, as [AppModel] uses it. */
class SdkVoice(context: Context) : VoiceConnecting {
    private val client = VoiceClient(context.applicationContext)

    override val call: StateFlow<Call?> get() = client.call

    override fun connect(callToken: String) = client.connect(callToken)
    override fun hangup() = client.hangup()
    override suspend fun setMuted(muted: Boolean) = client.setMuted(muted)
    override suspend fun setOnHold(hold: Boolean) = client.setOnHold(hold)
    override suspend fun sendDigits(digits: String) = client.sendDigits(digits)
    override suspend fun stats(): CallStats? = client.stats()
}
