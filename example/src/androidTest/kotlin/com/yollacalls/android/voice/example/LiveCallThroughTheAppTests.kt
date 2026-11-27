//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.yollacalls.android.voice.example.app.AppModel
import com.yollacalls.android.voice.example.app.ExampleApplication
import com.yollacalls.android.voice.example.app.MainActivity
import com.yollacalls.android.voice.example.auth.TokenProvider
import com.yollacalls.android.voice.example.call.Phase
import com.yollacalls.android.voice.example.settings.Settings
import com.yollacalls.android.voice.example.settings.TokenSource
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

/**
 * Real calls through the app's own UI, the real [AppModel] and the whole chain: the token from the demo backend,
 * Telecom (or the direct mode), the call audio, the SDK and the gateway. Skipped unless the instrumentation argument
 * `yollaLiveNumber` is given, which the build passes only with `-Pyolla.liveCalls=true` (see the README). The app
 * obtains its call token itself; no token or bearer is ever put into a message or the log of this test.
 *
 * The emulator's microphone gives silence: "connected" is judged by the status line turning into a timer.
 */
@OptIn(ExperimentalTestApi::class)
class LiveCallThroughTheAppTests {
    private val permissions: GrantPermissionRule = GrantPermissionRule.grant(*callPermissions().toTypedArray())
    private val compose = createAndroidComposeRule<MainActivity>()

    /** The permissions first, so that tapping Call raises no system dialog. */
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(permissions).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var appModel: AppModel
    private lateinit var savedSettings: Settings
    private var number = ""
    private var firstLogId = 0L

    @Before
    fun setUp() {
        number = InstrumentationRegistry.getArguments().getString("yollaLiveNumber").orEmpty()
        assumeTrue("yollaLiveNumber is not set (needs -Pyolla.liveCalls=true and YOLLA_TEST_NUMBER)", number.isNotEmpty())
        // The app asks the demo backend for its call token with the token it was built with.
        assumeTrue("the app was built without YOLLA_VOICE_DEMO_TOKEN", TokenProvider.demoToken != null)
        appModel = (compose.activity.applicationContext as ExampleApplication).appModel
        savedSettings = appModel.settings.value
        firstLogId = appModel.logs.entries.value.lastOrNull()?.id?.plus(1) ?: 0L
    }

    @After
    fun tearDown() {
        if (number.isEmpty()) return
        // A failed assertion must not leave a real call up.
        val call = appModel.activeCall.value
        if (call != null && call.state.value.phase.isActive) {
            instrumentation.runOnMainSync { call.hangup() }
            val deadline = System.nanoTime() + 10_000_000_000L
            while (call.state.value.phase.isActive && System.nanoTime() < deadline) Thread.sleep(100)
        }
        // This test's part of the app's log, masked, for the run's record (passed or not).
        for (message in entries()) Log.i(TAG, "app log: ${mask(message)}")
        instrumentation.runOnMainSync { appModel.updateSettings(savedSettings) }
    }

    @Test
    fun aCallThroughTelecomConnectsAndEnds() = callConnectsAndEnds(useTelecom = true)

    @Test
    fun aCallWithoutTelecomConnectsAndEnds() = callConnectsAndEnds(useTelecom = false)

    private fun callConnectsAndEnds(useTelecom: Boolean) {
        instrumentation.runOnMainSync {
            appModel.updateSettings(Settings(TokenSource.Url(Settings.defaultTokenURL), useTelecom = useTelecom))
        }
        compose.onNodeWithTag("dialer.number").performTextClearance()
        compose.onNodeWithTag("dialer.number").performTextInput(number)
        compose.onNodeWithTag("dialer.call").assertIsEnabled().performClick()

        // Connected (the timer) or ended, whichever comes first.
        compose.waitUntil(CONNECT_TIMEOUT_MS) {
            status()?.let(TIMER::matches) == true || appModel.activeCall.value?.state?.value?.phase is Phase.Ended
        }
        val status = status()
        if (status == null || !TIMER.matches(status)) fail("The call did not connect: status=${mask(status.toString())}\n${logText()}")
        compose.onNodeWithTag("incall.mute").assertIsEnabled()

        // Talk for a few seconds so that the media path is exercised, then hang up from the screen.
        compose.waitUntil(TALK_TIMEOUT_MS) { status()?.let(::seconds)?.let { it >= TALK_SECONDS } == true }
        val duration = status()
        compose.onNodeWithTag("incall.hangup").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("dialer.call"), DISMISS_TIMEOUT_MS)
        Log.i(TAG, "${if (useTelecom) "Telecom" else "direct"}: connected, hung up at $duration")

        val messages = entries()
        val mode = if (useTelecom) "Telecom" else "direct"
        assertTrue("No \"call $number via $mode\" in the app's log\n${logText()}", "call $number via $mode" in messages)
        assertTrue("No \"connected\" in the app's log\n${logText()}", "connected" in messages)
        assertTrue("No \"ended: Call ended\" in the app's log\n${logText()}", "ended: Call ended" in messages)
        assertNoSecrets()
    }

    /** Checks the whole log, not only this test's entries; the message never quotes an entry. */
    private fun assertNoSecrets() {
        val all = appModel.logs.entries.value
        val bearer = TokenProvider.demoToken
        val withBearer = if (bearer == null) -1 else all.indexOfFirst { bearer in it.message }
        assertFalse("The app's log has the backend bearer (entry #$withBearer)", withBearer >= 0)
        val withJwt = all.indexOfFirst { JWT.containsMatchIn(it.message) }
        assertFalse("The app's log has a JWT (entry #$withJwt)", withJwt >= 0)
    }

    private fun status(): String? = compose.onAllNodesWithTag("incall.status").fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun entries(): List<String> = appModel.logs.entries.value.filter { it.id >= firstLogId }.map { it.message }

    /** This test's part of the app's log, masked, for a failure message. */
    private fun logText(): String = "The app's log:\n" + entries().joinToString("\n") { mask(it) }

    private companion object {
        const val TAG = "LiveCallThroughTheApp"
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val TALK_SECONDS = 5
        const val TALK_TIMEOUT_MS = 15_000L
        const val DISMISS_TIMEOUT_MS = 10_000L
        val TIMER = Regex("""\d\d:\d\d""")
        val JWT = Regex("""eyJ[A-Za-z0-9_.\-]+""")

        fun seconds(timer: String): Int? =
            if (TIMER.matches(timer)) timer.substring(0, 2).toInt() * 60 + timer.substring(3).toInt() else null

        fun mask(text: String): String {
            val bearer = TokenProvider.demoToken.orEmpty()
            val withoutBearer = if (bearer.isEmpty()) text else text.replace(bearer, "<bearer>")
            return JWT.replace(withoutBearer, "<jwt>")
        }

        /** What the app asks for before a call, where the permission exists on this API level. */
        fun callPermissions(): List<String> = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
}
