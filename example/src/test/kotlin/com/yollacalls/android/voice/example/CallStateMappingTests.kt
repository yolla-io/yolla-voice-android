//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import com.yollacalls.android.voice.Call
import com.yollacalls.android.voice.CallStats
import com.yollacalls.android.voice.VoiceError
import com.yollacalls.android.voice.example.call.CallViewModel
import com.yollacalls.android.voice.example.call.statsLine
import com.yollacalls.android.voice.example.telecom.TelecomEndCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallStateMappingTests {
    @Test
    fun describesEndReasons() {
        fun d(r: Call.EndReason, e: VoiceError? = null) = CallViewModel.describe(r, e)
        assertEquals("Busy", d(Call.EndReason.Busy))
        assertEquals("No answer", d(Call.EndReason.NoAnswer))
        assertEquals("Rejected", d(Call.EndReason.Rejected))
        assertEquals("Call ended by the other side", d(Call.EndReason.RemoteHangup))
        assertEquals("Call ended", d(Call.EndReason.LocalHangup))
        assertEquals("Cancelled", d(Call.EndReason.Cancelled))
        assertEquals("Failed", d(Call.EndReason.Failed))
        assertEquals(
            "Failed: sip_failure (Forbidden, SIP 403)",
            d(Call.EndReason.Failed, VoiceError("sip_failure", "Forbidden", sipStatus = 403)),
        )
        assertEquals("Failed: x (y)", d(Call.EndReason.Failed, VoiceError("x", "y")))
    }

    @Test
    fun mapsToTelecomDisconnectCauses() {
        fun c(r: Call.EndReason, viaTelecom: Boolean) = CallViewModel.telecomEndCause(r, viaTelecom)
        for (via in listOf(false, true)) {
            assertEquals(TelecomEndCause.Remote, c(Call.EndReason.RemoteHangup, via))
            assertEquals(TelecomEndCause.Remote, c(Call.EndReason.NoAnswer, via))
            assertEquals(TelecomEndCause.Error, c(Call.EndReason.Failed, via))
            assertEquals(TelecomEndCause.Rejected, c(Call.EndReason.Busy, via))
            assertEquals(TelecomEndCause.Rejected, c(Call.EndReason.Rejected, via))
        }
        assertEquals(TelecomEndCause.Local, c(Call.EndReason.LocalHangup, false))
        assertEquals(TelecomEndCause.Local, c(Call.EndReason.Cancelled, false))
        assertEquals(null, c(Call.EndReason.LocalHangup, true))
        assertEquals(null, c(Call.EndReason.Cancelled, true))
    }

    @Test
    fun formatsTheStatsLineAsIos() {
        val full = CallStats {
            media = CallStats.Media { candidateType = "relay" }
            sample = CallStats.Sample {
                rttMs = 40.2
                jitterMs = 3.4
                lossPercent = 0.0
                mos = 4.31
            }
        }
        assertEquals("RTT 40 ms · jitter 3 ms · loss 0.0% · MOS 4.3 · relay", statsLine(full))
        val empty = CallStats {}
        assertEquals("RTT – · jitter – · loss – · MOS –", statsLine(empty))
    }
}
