//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.telecom

import android.content.Context
import android.os.PowerManager

/**
 * Turns the screen off at the ear: holds `PROXIMITY_SCREEN_OFF_WAKE_LOCK` only while a call is active and the audio
 * goes to the earpiece (or the route is not known yet). Main thread.
 */
class ProximityLock(context: Context) {
    private val lock: PowerManager.WakeLock? = context.applicationContext.getSystemService(PowerManager::class.java)
        .takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
        ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "YollaVoice:proximity")
        ?.apply { setReferenceCounted(false) }

    val isHeld: Boolean get() = lock?.isHeld == true

    fun update(callActive: Boolean, route: AudioRoute?) {
        val lock = lock ?: return
        val wanted = callActive && (route == null || route.kind == AudioRoute.Kind.Earpiece)
        if (wanted && !lock.isHeld) {
            lock.acquire()
        } else if (!wanted && lock.isHeld) {
            lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        }
    }
}
