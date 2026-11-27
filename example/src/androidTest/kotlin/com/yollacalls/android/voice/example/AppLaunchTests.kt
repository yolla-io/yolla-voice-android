//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yollacalls.android.voice.example.app.ExampleApplication
import com.yollacalls.android.voice.example.app.MainActivity
import com.yollacalls.android.voice.example.support.ScreenRig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The real Activity. Nothing here taps Call on the app's real model: no network, no Telecom, no SDK. */
@OptIn(ExperimentalTestApi::class)
class AppLaunchTests {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun theAppStartsOnTheDialerAndTabsSwitch() {
        rule.onNodeWithText("Yolla Voice").assertIsDisplayed()
        rule.onNodeWithTag("dialer.number").assertIsDisplayed()
        rule.onNodeWithTag("dialer.call").assertIsDisplayed()

        rule.onNodeWithTag("tab.logs").performClick()
        rule.onNodeWithTag("logs.share").assertIsDisplayed()
        rule.onNodeWithTag("tab.settings").performClick()
        rule.onNodeWithTag("settings.save").assertIsDisplayed()
        rule.onNodeWithTag("tab.dialer").performClick()
        rule.onNodeWithTag("dialer.call").assertIsDisplayed()
    }

    @Test
    fun inCallScreenComesBackAfterRecreation() {
        val app = rule.activity.application as ExampleApplication
        val real = app.appModel
        val rig = ScreenRig()
        try {
            // The test hook: the Activity reads the model in onCreate, so it shows the fake one from the next creation on.
            app.appModel = rig.model
            rule.activityRule.scenario.recreate()
            rule.runOnIdle { rig.model.placeCall("1001") }
            rule.waitUntil(5_000) { rig.voice.connects == 1 }
            rule.waitUntilExactlyOneExists(hasTestTag("incall.status"), 5_000)

            rule.activityRule.scenario.recreate()
            rule.onNodeWithTag("incall.status").assertIsDisplayed().assertTextEquals("Connecting…")
            rule.onNodeWithTag("incall.hangup").assertIsDisplayed()
            // Recreating the Activity is not a hang-up.
            rule.runOnIdle { assertEquals(0, rig.voice.hangups) }
        } finally {
            app.appModel = real
            rig.close()
        }
    }
}
