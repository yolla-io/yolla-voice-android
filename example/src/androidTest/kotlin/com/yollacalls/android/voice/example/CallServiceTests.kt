//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.yollacalls.android.voice.example.telecom.CallService
import com.yollacalls.android.voice.example.telecom.CallServiceController
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** The foreground call service and its notification on a real device. */
class CallServiceTests {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*callPermissions())

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val hangups = AtomicInteger()
    private val controller = CallServiceController(context, { log += it }) { hangups.incrementAndGet() }
    private val realStartForeground = CallService.startForeground

    @After
    fun tearDown() {
        onMain {
            controller.stop()
            CallService.startForeground = realStartForeground
        }
    }

    @Test
    fun theServiceShowsTheCallNotificationAndStops() {
        onMain { controller.start("1001", true, true) }
        waitFor("the call notification") { hasNotification() }

        onMain { controller.stop() }
        waitFor("the notification to go") { !hasNotification() }
    }

    @Test
    fun theServiceStartsWithoutTheMicrophonePermission() {
        onMain { controller.start("1001", false, true) }
        waitFor("the call notification") { hasNotification() }
    }

    @Test
    fun aStopRightAfterTheStartDoesNotCrashAndLeavesNothing() {
        // Before onStartCommand reached startForeground: a plain stopService here kills the process.
        onMain {
            controller.start("1001", true, true)
            controller.stop()
        }
        Thread.sleep(2_000)
        waitFor("no notification") { !hasNotification() }
    }

    @Test
    fun startStopStartLeavesTheNotificationUp() {
        onMain {
            controller.start("1001", true, true)
            controller.stop()
            controller.start("1001", true, true)
        }
        waitFor("the call notification") { hasNotification() }
        Thread.sleep(1_000)
        waitFor("the notification to stay") { hasNotification() }
    }

    @Test
    fun theServiceStartsInDirectMode() {
        onMain { controller.start("1001", true, false) }
        waitFor("the call notification") { hasNotification() }
        assertEquals(0, hangups.get())
    }

    @Test
    fun aRefusedMicrophoneTypeIsRetriedAsAPhoneCall() {
        assumeTrue("no microphone type before API 30", Build.VERSION.SDK_INT >= 30)
        val attempts: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        onMain {
            // What API 34+ does to `microphone` while the app is not in the foreground.
            CallService.startForeground = { service, notification, types ->
                attempts += types
                if (types and FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) throw SecurityException("test: microphone refused")
                realStartForeground(service, notification, types)
            }
            controller.start("1001", true, true)
        }
        waitFor("the call notification") { hasNotification() }
        assertEquals(listOf(FOREGROUND_SERVICE_TYPE_PHONE_CALL or FOREGROUND_SERVICE_TYPE_MICROPHONE, FOREGROUND_SERVICE_TYPE_PHONE_CALL), attempts.toList())
        assertEquals(0, hangups.get())
        assertTrue("${log.toList()}", log.any { it.contains("retrying with $FOREGROUND_SERVICE_TYPE_PHONE_CALL") })
    }

    @Test
    fun aServiceThatCannotEnterTheForegroundEndsTheCallAndStops() {
        onMain {
            // The system took the start and then refused it, as ForegroundServiceStartNotAllowedException does.
            CallService.startForeground = { service, notification, types ->
                realStartForeground(service, notification, types)
                throw IllegalStateException("test: not allowed")
            }
            controller.start("1001", true, true)
        }
        waitFor("the call to be ended") { hangups.get() == 1 }
        waitFor("no notification") { !hasNotification() }
        // Still alive: neither the throw nor the stop took the process down.
        Thread.sleep(2_000)
        assertEquals(1, hangups.get())
        assertTrue("${log.toList()}", log.any { it.contains("could not start in the foreground") })
    }

    private fun hasNotification(): Boolean =
        notifications.activeNotifications.any { it.packageName == context.packageName }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}

/** Polls [condition] for up to [timeoutMs]. */
fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
        Thread.sleep(50)
    }
}
