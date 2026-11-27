//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The launcher activity (`singleTask`: the call notification brings this one back). It hosts [RootScreen] for the
 * process's [AppModel], is where [AppModel] asks for permissions, and shows over the lock screen while a call is up.
 * The proximity lock is held by [ExampleApplication], independently of the Activity.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as ExampleApplication
        // Detaches itself when this Activity is destroyed.
        app.permissions.attach(this)
        val appModel = app.appModel
        lifecycleScope.launch {
            appModel.activeCall.map { it != null }.distinctUntilChanged().collect(::showOverLockScreen)
        }
        setContent { RootScreen(appModel) }
    }

    private fun showOverLockScreen(show: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(show)
            setTurnScreenOn(show)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (show) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
}
