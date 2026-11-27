//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.settings

import java.net.URI
import kotlinx.serialization.Serializable

/** Where the call token comes from: a backend URL or a token pasted by hand. */
@Serializable
sealed class TokenSource {
    @Serializable
    data class Url(val url: String) : TokenSource()

    @Serializable
    data class Pasted(val token: String) : TokenSource()
}

@Serializable
data class Settings(
    val tokenSource: TokenSource = TokenSource.Url(defaultTokenURL),
    val useTelecom: Boolean = true,
) {
    /** The problems that prevent a call; empty when the settings are usable. */
    fun validate(): List<String> = when (val source = tokenSource) {
        is TokenSource.Url -> {
            val url = source.url.trim()
            if (url.isEmpty()) listOf(REQUIRED) else if (!isValidUrl(url)) listOf(INVALID) else emptyList()
        }
        is TokenSource.Pasted -> if (source.token.isBlank()) listOf(REQUIRED) else emptyList()
    }

    companion object {
        const val defaultTokenURL = "https://demo.yolla.io/api/calls"
        val default: Settings = Settings()

        private const val REQUIRED = "Token URL or pasted token is required"
        private const val INVALID = "Token URL is invalid"

        private fun isValidUrl(text: String): Boolean {
            val uri = try {
                URI(text)
            } catch (_: Exception) {
                return false
            }
            val scheme = uri.scheme?.lowercase()
            return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
        }
    }
}
