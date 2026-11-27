//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import com.yollacalls.android.voice.example.logs.LogStore
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class LogStoreTests {
    @Test
    fun keepsTheLastEntriesUpToCapacity() {
        val log = LogStore(capacity = 3)
        (1..5).forEach { log.app("$it") }
        assertEquals(listOf("3", "4", "5"), log.entries.value.map { it.message })
    }

    @Test
    fun exportTextHasIsoTimestampsWithMilliseconds() {
        val log = LogStore(now = { Instant.parse("2026-11-18T10:00:00.123Z") })
        log.app("hello")
        assertEquals("2026-11-18T10:00:00.123Z hello", log.exportText)
    }

    @Test
    fun clearEmptiesTheStore() {
        val log = LogStore()
        log.app("x")
        log.clear()
        assertTrue(log.entries.value.isEmpty())
        assertEquals("", log.exportText)
    }
}
