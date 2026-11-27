//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.call

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yollacalls.android.voice.example.R
import com.yollacalls.android.voice.example.telecom.AudioRoute
import com.yollacalls.android.voice.example.telecom.AudioRouting
import kotlinx.coroutines.launch

/** The Audio control: a menu of the call's audio outputs with the current one checked. */
@Composable
fun AudioRouteButton(routing: AudioRouting?, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box {
        ControlButton(
            icon = R.drawable.ic_volume_up,
            title = "Audio",
            active = expanded,
            enabled = enabled && routing != null,
            tag = "incall.audio",
        ) { expanded = true }
        if (routing != null) {
            val routes by routing.routes.collectAsStateWithLifecycle()
            val current by routing.current.collectAsStateWithLifecycle()
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                for (route in routes) {
                    DropdownMenuItem(
                        text = { Text(route.name) },
                        onClick = {
                            expanded = false
                            scope.launch { routing.select(route) }
                        },
                        leadingIcon = { RouteMark(route, current) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteMark(route: AudioRoute, current: AudioRoute?) {
    if (route.id == current?.id) {
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = "Current",
            modifier = Modifier.size(20.dp).testTag("incall.audio.${route.id}.current"),
        )
    } else {
        Spacer(Modifier.size(20.dp))
    }
}
