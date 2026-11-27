//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.logs

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yollacalls.android.voice.example.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)

@Composable
fun LogsScreen(logs: LogStore, modifier: Modifier = Modifier) {
    val entries by logs.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(entries.lastOrNull()?.id) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_list), contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("No events yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The app's own events appear here. The SDK's log is sent to the platform, not shown in the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    } else {
        SelectionContainer(modifier) {
            LazyColumn(Modifier.fillMaxSize().testTag("logs.list"), state = listState) {
                items(entries, key = { it.id }) { entry ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            timeFormatter.format(entry.date.atZone(ZoneId.systemDefault())),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(entry.message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Share (as text through `ACTION_SEND`) and clear; for the Logs tab's top bar. */
@Composable
fun LogsActions(logs: LogStore) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, logs.exportText)
            context.startActivity(Intent.createChooser(send, null))
        },
        modifier = Modifier.testTag("logs.share"),
    ) {
        Icon(painterResource(R.drawable.ic_share), contentDescription = "Share")
    }
    IconButton(onClick = logs::clear, modifier = Modifier.testTag("logs.clear")) {
        Icon(painterResource(R.drawable.ic_delete), contentDescription = "Clear")
    }
}
