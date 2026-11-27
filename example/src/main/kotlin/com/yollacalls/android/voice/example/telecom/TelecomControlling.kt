//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.telecom

enum class TelecomEndCause { Local, Remote, Rejected, Error }

/** One call's handle to Telecom. */
interface TelecomControlling {
    /** Asks Telecom to put the call on hold (`setInactive`) or resume it (`setActive`); false means it refused. */
    suspend fun setHeld(held: Boolean): Boolean
    suspend fun reportConnected()
    /** A no-op once Telecom has ended the call itself. */
    suspend fun reportEnded(cause: TelecomEndCause)
}

/** Requests that come from the system. */
interface TelecomCallEvents {
    suspend fun hangupFromTelecom()
    /** Never throws. */
    suspend fun holdFromTelecom(hold: Boolean)
    suspend fun muteFromTelecom(muted: Boolean)
}
