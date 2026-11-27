//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.logs

import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LogEntry(val id: Long, val date: Instant, val message: String)

/** The app's own log: the last [capacity] entries. Safe to call from any thread. */
class LogStore(
    private val capacity: Int = 2000,
    private val now: () -> Instant = Instant::now,
) {
    private val lock = Any()
    private var nextId = 0L
    private val state = MutableStateFlow<List<LogEntry>>(emptyList())

    val entries: StateFlow<List<LogEntry>> = state

    fun app(message: String) {
        synchronized(lock) {
            val entry = LogEntry(nextId++, now(), message)
            state.value = (state.value + entry).takeLast(capacity)
        }
    }

    fun clear() {
        synchronized(lock) { state.value = emptyList() }
    }

    val exportText: String
        get() = state.value.joinToString("\n") { "${formatter.format(it.date)} ${it.message}" }

    private companion object {
        val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)
    }
}
