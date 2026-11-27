//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.yollacalls.android.voice.example.app.TelecomSession
import com.yollacalls.android.voice.example.logs.LogStore
import com.yollacalls.android.voice.example.support.RecordingTelecomEvents
import com.yollacalls.android.voice.example.telecom.TelecomCallManager
import com.yollacalls.android.voice.example.telecom.TelecomEndCause
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A self-managed Telecom call through core-telecom on a real device; no SDK call, no network. */
class TelecomCallManagerTests {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*callPermissions())

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val logs = LogStore()
    private val manager = TelecomCallManager(context, scope, logs)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun aCallGoesThroughTelecom() = runBlocking {
        val events = RecordingTelecomEvents()
        val session = start(events)

        session.reportConnected()
        assertTrue("setInactive refused", session.setHeld(true))
        assertTrue("setActive refused", session.setHeld(false))
        val routes = withTimeout(5.seconds) { session.routes.first { it.isNotEmpty() } }
        assertTrue(routes.isNotEmpty())
        assertNotNull(withTimeout(5.seconds) { session.current.first { it != null } })

        session.reportEnded(TelecomEndCause.Remote)
        session.reportEnded(TelecomEndCause.Local)
        val log = logs.entries.value.map { it.message }
        assertTrue("$log", "[telecom] call accepted" in log)
        assertTrue("$log", log.none { it.contains("refused") || it.contains("timed out") || it.contains("failed") })
    }

    @Test
    fun aSecondCallCanStartAfterTheFirstEnded() = runBlocking {
        val first = start(RecordingTelecomEvents())
        first.reportEnded(TelecomEndCause.Local)

        val second = start(RecordingTelecomEvents())
        second.reportConnected()
        second.reportEnded(TelecomEndCause.Local)
    }

    private suspend fun start(events: RecordingTelecomEvents): TelecomSession =
        withTimeout(10.seconds) { manager.startCall(UUID.randomUUID(), "1001", events) }
}

/** The runtime permissions a call asks for, as far as this API level knows them. */
fun callPermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()
