//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.yollacalls.android.voice.Call
import com.yollacalls.android.voice.example.app.RootScreen
import com.yollacalls.android.voice.example.settings.Settings
import com.yollacalls.android.voice.example.settings.TokenSource
import com.yollacalls.android.voice.example.support.ScreenRig
import com.yollacalls.android.voice.example.telecom.AudioRoute
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ScreensTests {
    @get:Rule
    val rule = createComposeRule()

    private var rig = ScreenRig()

    @After
    fun tearDown() {
        rig.close()
    }

    private fun show(rig: ScreenRig = this.rig) {
        this.rig = rig
        rule.setContent { RootScreen(rig.model) }
    }

    /** Taps Call and waits for the in-call screen of the call placed on the fake voice. */
    private fun placeCall() {
        rule.onNodeWithTag("dialer.call").performClick()
        rule.waitUntil(5_000) { rig.voice.connects == 1 }
        rule.waitUntilExactlyOneExists(hasTestTag("incall.status"), 5_000)
    }

    private fun hasText(pattern: Regex) = SemanticsMatcher("text matches $pattern") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { pattern.matches(it.text) }
    }

    @Test
    fun dialerPlacesACall() {
        show()
        rule.onNodeWithTag("dialer.number").assert(hasText("1001"))
        placeCall()
        assertEquals(1, rig.voice.connects)
        rule.onNodeWithTag("incall.status").assertTextEquals("Connecting…")
    }

    @Test
    fun callButtonIsDisabledForAnEmptyNumberOrBadSettings() {
        show()
        rule.onNodeWithTag("dialer.call").assertIsEnabled()
        rule.onNodeWithTag("dialer.number").performTextClearance()
        rule.onNodeWithTag("dialer.call").assertIsNotEnabled()
        rule.onNodeWithText("5").performClick()
        rule.onNodeWithTag("dialer.call").assertIsEnabled()

        rule.runOnIdle { rig.model.updateSettings(Settings(TokenSource.Pasted(""), true)) }
        rule.onNodeWithText("Settings: Token URL or pasted token is required").assertIsDisplayed()
        rule.onNodeWithTag("dialer.call").assertIsNotEnabled()
    }

    @Test
    fun inCallControlsFollowThePhase() {
        show()
        placeCall()
        rule.onNodeWithTag("incall.mute").assertIsNotEnabled()
        rule.onNodeWithTag("incall.hold").assertIsNotEnabled()
        rule.onNodeWithTag("incall.keypad").assertIsNotEnabled()
        rule.onNodeWithTag("incall.audio").assertIsNotEnabled()
        rule.onNodeWithTag("incall.hangup").assertIsEnabled()

        rule.runOnIdle { rig.voice.connected() }
        rule.onNodeWithTag("incall.status").assert(hasText(Regex("""\d\d:\d\d""")))
        rule.onNodeWithTag("incall.mute").assertIsEnabled().performClick()
        rule.waitUntil(2_000) { rig.voice.mutes == listOf(true) }

        rule.runOnIdle { rig.voice.ended(Call.EndReason.RemoteHangup) }
        rule.onNodeWithTag("incall.status").assertTextEquals("Call ended by the other side")
        rule.onNodeWithTag("incall.hangup").assertIsNotEnabled()
        rule.waitUntilExactlyOneExists(hasTestTag("dialer.call"), 4_000)
    }

    @Test
    fun hangupIsSentToTheCall() {
        show()
        placeCall()
        rule.onNodeWithTag("incall.hangup").performClick()
        rule.waitUntil(2_000) { rig.voice.hangups == 1 }
    }

    @Test
    fun keypadSendsDigits() {
        show()
        placeCall()
        rule.runOnIdle { rig.voice.connected() }
        rule.onNodeWithTag("incall.keypad").performClick()
        rule.onNodeWithText("5").performClick()
        rule.waitUntil(2_000) { rig.voice.digits == listOf("5") }
    }

    @Test
    fun audioMenuListsRoutesAndSelects() {
        val earpiece = AudioRoute("earpiece", AudioRoute.Kind.Earpiece, "Earpiece")
        val speaker = AudioRoute("speaker", AudioRoute.Kind.Speaker, "Speaker")
        val routing = rig.telecom.session.routing
        routing.routes.value = listOf(earpiece, speaker)
        routing.current.value = earpiece
        show()
        placeCall()
        rule.runOnIdle { rig.voice.connected() }

        rule.onNodeWithTag("incall.audio").performClick()
        rule.onNodeWithText("Earpiece").assertIsDisplayed()
        rule.onNodeWithTag("incall.audio.earpiece.current", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Speaker").performClick()
        rule.waitUntil(2_000) { routing.selected == listOf(speaker) }
    }

    @Test
    fun settingsAreSavedAndShownAgain() {
        show()
        rule.onNodeWithTag("tab.settings").performClick()
        rule.onNodeWithTag("settings.tokenUrl").assert(hasText(Settings.defaultTokenURL))
        rule.onNodeWithText("Token").performClick()
        rule.onNodeWithTag("settings.pastedToken").performTextInput("abc")
        rule.onNodeWithTag("settings.useTelecom").performClick()
        rule.onNodeWithTag("settings.save").performClick()
        rule.runOnIdle { assertEquals(Settings(TokenSource.Pasted("abc"), false), rig.model.settings.value) }

        // Away and back: the screen shows what was saved.
        rule.onNodeWithTag("tab.dialer").performClick()
        rule.onNodeWithTag("tab.settings").performClick()
        rule.onNodeWithTag("settings.pastedToken").assert(hasText("abc"))
    }

    @Test
    fun logsShowEventsAndClear() {
        show()
        rule.onNodeWithTag("tab.logs").performClick()
        rule.onNodeWithText("No events yet").assertIsDisplayed()

        rule.runOnIdle { rig.logs.app("hello from the test") }
        rule.onNodeWithText("hello from the test").assertIsDisplayed()
        rule.onNodeWithTag("logs.list").assertIsDisplayed()

        rule.onNodeWithTag("logs.clear").performClick()
        rule.onNodeWithText("No events yet").assertIsDisplayed()
        rule.runOnIdle { assertEquals(emptyList<Any>(), rig.logs.entries.value) }
    }

    @Test
    fun anErrorIsShownAsADialog() {
        rig.tokens.error = IOException("offline")
        show()
        rule.onNodeWithTag("dialer.call").performClick()
        rule.waitUntilExactlyOneExists(hasText("Error"), 5_000)
        rule.onNodeWithText("Token request failed (demo.yolla.io): offline").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        rule.waitUntilDoesNotExist(hasText("Error"), 2_000)
        rule.runOnIdle { assertEquals(null, rig.model.alert.value) }
    }

    @Test
    fun theDialedNumberAndTheTabSurviveACall() {
        show()
        rule.onNodeWithTag("dialer.number").performTextClearance()
        rule.onNodeWithTag("dialer.number").performTextInput("4915")
        placeCall()
        rule.runOnIdle { rig.voice.ended(Call.EndReason.RemoteHangup) }
        rule.waitUntilExactlyOneExists(hasTestTag("dialer.call"), 4_000)
        rule.onNodeWithTag("dialer.number").assert(hasText("4915"))
        // The tab bar is back on the dialer tab, the one the call started from.
        rule.onNodeWithText("Yolla Voice").assertIsDisplayed()
    }

    @Test
    fun theInCallControlsFitASmallScreenWithTheKeypadOpen() {
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(360.dp, 640.dp).clipToBounds().testTag("screen")) { RootScreen(rig.model) }
            }
        }
        // The dialer scrolls on a small screen; the call is started on the model to keep this test about the call screen.
        rule.runOnIdle { rig.model.placeCall("1001") }
        rule.waitUntilExactlyOneExists(hasTestTag("incall.status"), 5_000)
        rule.runOnIdle { rig.voice.connected() }
        rule.onNodeWithTag("incall.keypad").performClick()

        val screen = rule.onNodeWithTag("screen").getUnclippedBoundsInRoot()
        for (tag in listOf("incall.hangup", "incall.mute", "incall.hold", "incall.audio", "incall.keypad")) {
            val node = rule.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(
                "$tag at $node is outside the 360×640 dp screen $screen",
                node.left >= screen.left && node.top >= screen.top && node.right <= screen.right && node.bottom <= screen.bottom,
            )
        }
        rule.onNodeWithText("5").assertIsDisplayed().performClick()
        rule.waitUntil(2_000) { rig.voice.digits == listOf("5") }
    }
}
