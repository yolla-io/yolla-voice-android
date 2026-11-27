//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
import com.yollacalls.android.voice.example.telecom.foregroundServiceFallback
import com.yollacalls.android.voice.example.telecom.foregroundServiceTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The call service's foreground types by API level, mode and microphone grant, and the retry after a refusal. */
class CallServiceTypesTests {
    private val phoneCall = FOREGROUND_SERVICE_TYPE_PHONE_CALL
    private val microphone = FOREGROUND_SERVICE_TYPE_MICROPHONE

    @Test
    fun beforeApi29ThereAreNoTypes() {
        for (sdk in 26..28) for (telecom in listOf(true, false)) for (granted in listOf(true, false)) {
            assertEquals(0, foregroundServiceTypes(sdk, telecom, granted), "sdk $sdk telecom $telecom granted $granted")
        }
    }

    @Test
    fun api29IsAPhoneCallInBothModes() {
        for (telecom in listOf(true, false)) for (granted in listOf(true, false)) {
            assertEquals(phoneCall, foregroundServiceTypes(29, telecom, granted), "telecom $telecom granted $granted")
        }
    }

    @Test
    fun fromApi30TelecomIsAPhoneCallWithTheMicrophoneWhenGranted() {
        for (sdk in listOf(30, 33, 34, 36)) {
            assertEquals(phoneCall or microphone, foregroundServiceTypes(sdk, telecom = true, microphoneGranted = true))
            assertEquals(phoneCall, foregroundServiceTypes(sdk, telecom = true, microphoneGranted = false))
        }
    }

    @Test
    fun fromApi30DirectModeIsTheMicrophoneWhenGranted() {
        for (sdk in listOf(30, 33, 34, 36)) {
            assertEquals(microphone, foregroundServiceTypes(sdk, telecom = false, microphoneGranted = true))
            assertEquals(phoneCall, foregroundServiceTypes(sdk, telecom = false, microphoneGranted = false))
        }
    }

    @Test
    fun aRefusedStartIsRetriedAsAPhoneCallOnly() {
        assertEquals(phoneCall, foregroundServiceFallback(34, phoneCall or microphone))
        assertEquals(phoneCall, foregroundServiceFallback(36, microphone))
        assertEquals(phoneCall, foregroundServiceFallback(30, microphone))
    }

    @Test
    fun aRefusedPhoneCallOrUntypedStartIsNotRetried() {
        assertNull(foregroundServiceFallback(34, phoneCall))
        assertNull(foregroundServiceFallback(29, phoneCall))
        assertNull(foregroundServiceFallback(28, 0))
    }
}
