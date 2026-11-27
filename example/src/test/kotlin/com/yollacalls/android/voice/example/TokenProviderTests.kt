//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import com.yollacalls.android.voice.example.auth.TokenError
import com.yollacalls.android.voice.example.auth.TokenProvider
import com.yollacalls.android.voice.example.settings.Settings
import com.yollacalls.android.voice.example.settings.TokenSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenProviderTests {
    private fun reply(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    private fun withServer(vararg responses: MockResponse, block: (MockWebServer, Settings) -> Unit) {
        val server = MockWebServer()
        try {
            responses.forEach { server.enqueue(it) }
            server.start()
            block(server, Settings(tokenSource = TokenSource.Url(server.url("/api/calls").toString())))
        } finally {
            server.close()
        }
    }

    private fun failure(provider: TokenProvider, to: String = "1"): Throwable =
        try {
            runBlocking { provider.token(to) }
            throw AssertionError("expected a failure")
        } catch (e: TokenError) {
            e
        }

    @Test
    fun postsDialedNumberAndReadsCallToken() = withServer(reply(200, """{"callToken":"jwt-1"}""")) { server, settings ->
        val token = runBlocking { TokenProvider(settings, bearerToken = "demo-1").token("4915112345678") }
        assertEquals("jwt-1", token)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("""{"to":"4915112345678"}""", request.body.readUtf8())
        assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(';'))
        assertEquals("application/json", request.headers["Accept"])
        assertEquals("Bearer demo-1", request.headers["Authorization"])
    }

    @Test
    fun httpErrorAndBadBodyAreReported() {
        withServer(reply(401, "no")) { _, settings ->
            assertEquals(401, (failure(TokenProvider(settings, bearerToken = "demo-1")) as TokenError.Http).status)
        }
        withServer(reply(200, "{}")) { _, settings ->
            assertTrue(failure(TokenProvider(settings, bearerToken = "demo-1")) === TokenError.InvalidResponse)
        }
        withServer(reply(200, """{"callToken":""}""")) { _, settings ->
            assertTrue(failure(TokenProvider(settings, bearerToken = "demo-1")) === TokenError.InvalidResponse)
        }
    }

    @Test
    fun pastedTokenIsReturnedTrimmed() = withServer { server, _ ->
        val token = runBlocking { TokenProvider(Settings(tokenSource = TokenSource.Pasted("  abc\n"))).token("1") }
        assertEquals("abc", token)
        assertTrue(failure(TokenProvider(Settings(tokenSource = TokenSource.Pasted(" ")))) === TokenError.Missing)
        assertEquals(0, server.requestCount)
        assertNull(server.takeRequest(1, TimeUnit.MILLISECONDS))
    }

    @Test
    fun nonJsonBodyIsInvalidResponse() {
        withServer(reply(200, "<html>oops</html>")) { _, settings ->
            assertTrue(failure(TokenProvider(settings, bearerToken = "demo-1")) === TokenError.InvalidResponse)
        }
        withServer(reply(200, "")) { _, settings ->
            assertTrue(failure(TokenProvider(settings, bearerToken = "demo-1")) === TokenError.InvalidResponse)
        }
    }

    @Test
    fun theDefaultClientGivesUpAfterFifteenSeconds() {
        assertEquals(15_000, TokenProvider.defaultClient.callTimeoutMillis)
    }

    @Test
    fun aSilentServerTimesOut() {
        val silent = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        withServer(silent) { _, settings ->
            val client = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build()
            val started = System.nanoTime()
            try {
                runBlocking { TokenProvider(settings, bearerToken = "demo-1", client = client).token("1") }
                throw AssertionError("expected an IOException")
            } catch (_: IOException) {
            }
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
        }
    }

    /** The build setting's value is a token only when something was set: not empty, not the placeholder. */
    @Test
    fun theBuildSettingIsATokenOnlyWhenItWasSet() {
        assertEquals("abc", TokenProvider.configuredToken(" abc\n"))
        assertNull(TokenProvider.configuredToken(null))
        assertNull(TokenProvider.configuredToken("  "))
        assertNull(TokenProvider.configuredToken(TokenProvider.demoTokenPlaceholder))
        assertEquals("YOUR_DEMO_TOKEN", TokenProvider.demoTokenPlaceholder)
    }

    /** Without the demo token the backend is not asked at all: the app says what to set. */
    @Test
    fun withoutTheDemoTokenTheBackendIsNotAsked() = withServer { server, settings ->
        val error = failure(TokenProvider(settings, bearerToken = null))
        assertTrue(error === TokenError.DemoTokenNotSet)
        assertTrue("YOLLA_VOICE_DEMO_TOKEN" in error.message!!)
        assertEquals(0, server.requestCount)
    }

    /** A pasted call token needs no demo token. */
    @Test
    fun aPastedTokenWorksWithoutTheDemoToken() {
        val provider = TokenProvider(Settings(tokenSource = TokenSource.Pasted("abc")), bearerToken = null)
        assertEquals("abc", runBlocking { provider.token("1") })
    }

    /** The token is not in the sources: the build takes it from YOLLA_VOICE_DEMO_TOKEN, a placeholder without it. */
    @Test
    fun theDemoTokenComesFromTheBuild() {
        assertEquals(TokenProvider.configuredToken(BuildConfig.YOLLA_VOICE_DEMO_TOKEN), TokenProvider.demoToken)
    }
}
