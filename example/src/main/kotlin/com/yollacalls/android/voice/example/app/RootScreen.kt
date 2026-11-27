//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.app

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yollacalls.android.voice.example.R
import com.yollacalls.android.voice.example.call.DialerScreen
import com.yollacalls.android.voice.example.call.InCallScreen
import com.yollacalls.android.voice.example.logs.LogsActions
import com.yollacalls.android.voice.example.logs.LogsScreen
import com.yollacalls.android.voice.example.settings.SettingsScreen

private enum class Tab(val title: String, val bar: String, @param:DrawableRes val icon: Int, val tag: String) {
    Dialer("Dialer", "Yolla Voice", R.drawable.ic_call, "tab.dialer"),
    Logs("Logs", "Logs", R.drawable.ic_list, "tab.logs"),
    Settings("Settings", "Settings", R.drawable.ic_tune, "tab.settings"),
}

/**
 * The tabs, and the in-call screen over them while [AppModel.activeCall] is set: the screen follows the model, so it
 * is back after the Activity is recreated or the app is reopened. Start errors come up as a dialog.
 */
@Composable
fun RootScreen(appModel: AppModel) {
    val call by appModel.activeCall.collectAsStateWithLifecycle()
    val alert by appModel.alert.collectAsStateWithLifecycle()

    AppTheme {
        val active = call
        // The tabs leave the composition during a call; their saveable state (the selected tab, the dialed number)
        // is kept here so they come back as they were, as the iOS TabView under its full-screen cover.
        val tabsState = rememberSaveableStateHolder()
        if (active != null) InCallScreen(appModel, active) else tabsState.SaveableStateProvider("tabs") { Tabs(appModel) }

        alert?.let { text ->
            AlertDialog(
                onDismissRequest = appModel::dismissAlert,
                title = { Text("Error") },
                text = { Text(text) },
                confirmButton = { TextButton(onClick = appModel::dismissAlert) { Text("OK") } },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tabs(appModel: AppModel) {
    var selected by rememberSaveable { mutableIntStateOf(Tab.Dialer.ordinal) }
    val tab = Tab.entries[selected]
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.bar) },
                actions = { if (tab == Tab.Logs) LogsActions(appModel.logs) },
            )
        },
        bottomBar = {
            NavigationBar {
                for (item in Tab.entries) {
                    NavigationBarItem(
                        selected = item == tab,
                        onClick = { selected = item.ordinal },
                        icon = { Icon(painterResource(item.icon), contentDescription = null) },
                        label = { Text(item.title) },
                        modifier = Modifier.testTag(item.tag),
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.Dialer -> DialerScreen(appModel, modifier)
            Tab.Logs -> LogsScreen(appModel.logs, modifier)
            Tab.Settings -> SettingsScreen(appModel, modifier)
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
