//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.settings

import android.content.Context
import kotlinx.serialization.json.Json

interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

class SharedPreferencesStore(
    context: Context,
    name: String = "com.yollacalls.android.voice.example.settings",
) : KeyValueStore {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = preferences.getString(key, null)

    override fun set(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
}

class SettingsStore(private val store: KeyValueStore) {
    /** The saved settings; the defaults when nothing is saved or the saved value is unreadable. */
    fun load(): Settings {
        val text = store.get(KEY) ?: return Settings.default
        return try {
            json.decodeFromString(Settings.serializer(), text)
        } catch (_: Exception) {
            Settings.default
        }
    }

    fun save(settings: Settings) {
        store.set(KEY, json.encodeToString(Settings.serializer(), settings))
    }

    private companion object {
        const val KEY = "settings"
        val json = Json { encodeDefaults = true }
    }
}
