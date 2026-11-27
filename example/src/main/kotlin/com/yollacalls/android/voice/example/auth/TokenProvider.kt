//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.auth

import com.yollacalls.android.voice.example.BuildConfig
import com.yollacalls.android.voice.example.settings.Settings
import com.yollacalls.android.voice.example.settings.TokenSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

sealed class TokenError(message: String) : Exception(message) {
    object Missing : TokenError("No token: set a Token URL or paste a token in Settings")
    object DemoTokenNotSet : TokenError(
        "No demo token: set YOLLA_VOICE_DEMO_TOKEN in local.properties and build again, or paste a call token in Settings",
    )
    class Http(val status: Int) : TokenError("Token endpoint returned HTTP $status")
    object InvalidResponse : TokenError("Token endpoint did not return {\"callToken\": …}")
}

/** Supplies the call token for `VoiceClient`: from the app backend (`POST <url>` with `{"to": …}`) or a pasted JWT. */
class TokenProvider(
    private val settings: Settings,
    private val bearerToken: String? = demoToken,
    private val client: OkHttpClient = defaultClient,
) {
    /** `to` is the dialed number, sent to the backend as typed; a pasted token ignores it. */
    suspend fun token(to: String): String = when (val source = settings.tokenSource) {
        is TokenSource.Pasted -> source.token.trim().ifEmpty { throw TokenError.Missing }
        is TokenSource.Url -> fetch(source.url.trim(), to)
    }

    private suspend fun fetch(url: String, to: String): String {
        val bearerToken = bearerToken ?: throw TokenError.DemoTokenNotSet
        val body = buildJsonObject { put("to", to) }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = try {
            Request.Builder().url(url).post(body)
        } catch (_: IllegalArgumentException) {
            throw TokenError.Missing
        }.header("Accept", "application/json")
            .header("Authorization", "Bearer $bearerToken")
            .build()
        val (status, text) = execute(request)
        if (status !in 200..299) throw TokenError.Http(status)
        val callToken = try {
            ((Json.parseToJsonElement(text) as? JsonObject)?.get("callToken") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
        } catch (_: Exception) {
            null
        }
        return callToken?.takeIf { it.isNotEmpty() } ?: throw TokenError.InvalidResponse
    }

    private suspend fun execute(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { continuation.resume(it.code to (it.body?.string() ?: "")) }
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }

    companion object {
        /** What the build setting holds until a token is set. */
        const val demoTokenPlaceholder = "YOUR_DEMO_TOKEN"

        /**
         * The demo backend's credential: the bearer token its token endpoint expects. It is not in the code: the
         * build takes it from `YOLLA_VOICE_DEMO_TOKEN` (the environment variable, as in CI, or the same key in the
         * git-ignored `local.properties`) and hands it over as `BuildConfig.YOLLA_VOICE_DEMO_TOKEN`. `null` until it
         * is set. A production app authorises this request with the user's own session instead.
         */
        val demoToken: String? = configuredToken(BuildConfig.YOLLA_VOICE_DEMO_TOKEN)

        /** The build setting's value as a token: nothing when it is empty or still the placeholder. */
        fun configuredToken(raw: String?): String? =
            raw?.trim()?.takeIf { it.isNotEmpty() && it != demoTokenPlaceholder }

        val defaultClient: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
