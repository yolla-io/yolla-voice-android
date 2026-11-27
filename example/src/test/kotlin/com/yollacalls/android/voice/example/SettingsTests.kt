//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import com.yollacalls.android.voice.example.settings.KeyValueStore
import com.yollacalls.android.voice.example.settings.Settings
import com.yollacalls.android.voice.example.settings.SettingsStore
import com.yollacalls.android.voice.example.settings.TokenSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class SettingsTests {
    private class MapStore(val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
        override fun get(key: String) = map[key]
        override fun set(key: String, value: String) { map[key] = value }
    }

    @Test
    fun roundTripsThroughStore() {
        val store = SettingsStore(MapStore())
        val settings = Settings(TokenSource.Pasted("abc"), useTelecom = false)
        store.save(settings)
        assertEquals(settings, store.load())
    }

    @Test
    fun defaultsAreReadyForACall() {
        assertTrue(Settings.default.validate().isEmpty())
        assertEquals(Settings.default, SettingsStore(MapStore()).load())
    }

    @Test
    fun validation() {
        val required = listOf("Token URL or pasted token is required")
        val invalid = listOf("Token URL is invalid")
        assertEquals(required, Settings(TokenSource.Url("")).validate())
        assertEquals(required, Settings(TokenSource.Pasted("  \n")).validate())
        assertEquals(invalid, Settings(TokenSource.Url("demo.yolla.io")).validate())
        assertEquals(invalid, Settings(TokenSource.Url("ftp://x")).validate())
        assertEquals(invalid, Settings(TokenSource.Url("https://")).validate())
        assertTrue(Settings(TokenSource.Url("http://10.0.2.2:8080/calls")).validate().isEmpty())
        assertTrue(Settings(TokenSource.Pasted("x")).validate().isEmpty())
    }

    @Test
    fun unreadableStoredValueGivesDefaults() {
        val store = SettingsStore(MapStore(mutableMapOf("settings" to "{not json")))
        assertEquals(Settings.default, store.load())
    }
}
