//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))

/** 12-key pad plus backspace; [onKey] receives "0"–"9", "*", "#" or "⌫". [keySize] is the diameter of a key. */
@Composable
fun DTMFKeypad(
    modifier: Modifier = Modifier,
    showsBackspace: Boolean = true,
    keySize: Dp = 64.dp,
    onKey: (String) -> Unit,
) {
    val rowGap = keySize / 6
    val keyGap = keySize / 3
    Column(modifier, verticalArrangement = Arrangement.spacedBy(rowGap), horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(keyGap)) {
                for (key in row) Key(key, keySize, onKey)
            }
        }
        if (showsBackspace) {
            Row(horizontalArrangement = Arrangement.spacedBy(keyGap)) {
                Spacer(Modifier.width(keySize))
                Key("⌫", keySize, onKey)
                Spacer(Modifier.width(keySize))
            }
        }
    }
}

@Composable
private fun Key(key: String, size: Dp, onKey: (String) -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button) { onKey(key) },
        contentAlignment = Alignment.Center,
    ) {
        Text(key, style = MaterialTheme.typography.headlineSmall)
    }
}
