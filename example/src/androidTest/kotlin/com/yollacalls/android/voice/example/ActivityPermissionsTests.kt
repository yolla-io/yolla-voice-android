//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import com.yollacalls.android.voice.example.app.ActivityPermissions
import com.yollacalls.android.voice.example.app.CallPermissions
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** `requestCallPermissions` never throws: AppModel does not catch around it. */
class ActivityPermissionsTests {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun withoutAnActivityTheCurrentGrantsAreReturned() = runBlocking {
        val permissions = ActivityPermissions(context)
        val grants = withContext(Dispatchers.Main) { permissions.requestCallPermissions() }
        assertEquals(currentGrants(), grants)
    }

    @Test
    fun aRequestInFlightAndAnActivityDestroyedMidRequestReturnTheCurrentGrants() = runBlocking {
        // A permission the app never declares is never granted: there is always something to ask for. The registry is
        // a fake, so no system dialog is raised and none can be left behind.
        val permissions = ActivityPermissions(context) { listOf(NEVER_GRANTED) }
        val registry = RecordingRegistry()
        val owner = withContext(Dispatchers.Main) { TestOwner().also { permissions.attach(it, registry) } }

        val first = scope.async { permissions.requestCallPermissions() }
        withTimeout(5.seconds) { while (registry.launches.isEmpty()) delay(10) }
        assertEquals(listOf(listOf(NEVER_GRANTED)), registry.launches)

        // The request is in flight: a second one does not wait for it and does not ask again.
        val second = withTimeout(5.seconds) { withContext(Dispatchers.Main) { permissions.requestCallPermissions() } }
        assertEquals(currentGrants(), second)
        assertEquals(1, registry.launches.size)

        withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.DESTROYED }
        assertEquals(currentGrants(), withTimeout(5.seconds) { first.await() })
    }

    @Test
    fun theResultCompletesTheRequest() = runBlocking {
        val permissions = ActivityPermissions(context) { listOf(NEVER_GRANTED) }
        val registry = RecordingRegistry()
        withContext(Dispatchers.Main) { permissions.attach(TestOwner(), registry) }

        val request = scope.async { permissions.requestCallPermissions() }
        withTimeout(5.seconds) { while (registry.launches.isEmpty()) delay(10) }
        withContext(Dispatchers.Main) { registry.deny() }
        assertEquals(currentGrants(), withTimeout(5.seconds) { request.await() })
    }

    /** Records what would have been asked; [deny] answers the last request as the user refusing everything. */
    private class RecordingRegistry : ActivityResultRegistry() {
        val launches: MutableList<List<String>> = java.util.Collections.synchronizedList(mutableListOf())
        private var lastRequest: Pair<Int, List<String>>? = null

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            @Suppress("UNCHECKED_CAST")
            val asked = (input as Array<String>).toList()
            launches += asked
            lastRequest = requestCode to asked
        }

        fun deny() {
            val (code, asked) = lastRequest ?: return
            val result = Intent()
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, asked.toTypedArray())
                .putExtra(
                    ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                    IntArray(asked.size) { PackageManager.PERMISSION_DENIED },
                )
            dispatchResult(code, Activity.RESULT_OK, result)
        }
    }

    /** A lifecycle the test moves itself; created on the main thread. */
    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        /** Not in the app's manifest, so never granted. */
        const val NEVER_GRANTED = Manifest.permission.CAMERA
    }

    private fun currentGrants() = CallPermissions(
        microphone = granted(Manifest.permission.RECORD_AUDIO),
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        bluetooth = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT),
    )
}
