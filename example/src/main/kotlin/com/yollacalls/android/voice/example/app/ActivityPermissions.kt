//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred

/**
 * Asks for the call's runtime permissions from the attached Activity, all missing ones in one request. Never throws:
 * with no Activity, with a request already in flight, or when the Activity goes away mid-request it returns the current
 * grants. Main thread.
 */
class ActivityPermissions internal constructor(
    context: Context,
    /** What a call asks for; tests name a permission that is never granted. */
    private val wanted: () -> List<String>,
) : PermissionRequesting {
    constructor(context: Context) : this(context, ::callPermissions)

    private val context = context.applicationContext
    private val key = "com.yollacalls.android.voice.example.permissions#${System.identityHashCode(this)}"
    private var owner: LifecycleOwner? = null
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var pending: CompletableDeferred<Unit>? = null

    private val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = detach()
    }

    /** Any time in the Activity's life; detached again when it is destroyed. */
    fun attach(activity: ComponentActivity) = attach(activity, activity.activityResultRegistry)

    /** The registry's own register (not registerForActivityResult) works after the owner has started. */
    internal fun attach(owner: LifecycleOwner, registry: ActivityResultRegistry) {
        detach()
        this.owner = owner
        launcher = registry.register(key, ActivityResultContracts.RequestMultiplePermissions()) { finishRequest() }
        owner.lifecycle.addObserver(observer)
    }

    fun detach() {
        owner?.lifecycle?.removeObserver(observer)
        owner = null
        launcher?.unregister()
        launcher = null
        finishRequest()
    }

    override suspend fun requestCallPermissions(): CallPermissions {
        val launcher = launcher
        val missing = wanted().filterNot(::granted)
        if (launcher == null || missing.isEmpty() || pending != null) return current()
        val request = CompletableDeferred<Unit>()
        pending = request
        try {
            launcher.launch(missing.toTypedArray())
        } catch (e: Exception) {
            Log.w(TAG, "permission request failed: $e")
            finishRequest()
        }
        request.await()
        return current()
    }

    private fun finishRequest() {
        pending?.complete(Unit)
        pending = null
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun current() = CallPermissions(
        microphone = granted(Manifest.permission.RECORD_AUDIO),
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        bluetooth = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT),
    )

    private companion object {
        const val TAG = "ActivityPermissions"

        fun callPermissions(): List<String> = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
}
