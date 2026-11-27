//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.call

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yollacalls.android.voice.CallStats
import com.yollacalls.android.voice.example.R
import com.yollacalls.android.voice.example.app.AppModel
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

private val Orange = Color(0xFFFF9500)
private val HangupRed = Color(0xFFFF3B30)

/** The active call; shown instead of the tabs while [AppModel.activeCall] is set (AppModel dismisses an ended call). */
@Composable
fun InCallScreen(appModel: AppModel, call: CallViewModel) {
    val state by call.state.collectAsStateWithLifecycle()
    val routing by appModel.audioRouting.collectAsStateWithLifecycle()
    var showKeypad by rememberSaveable(call.uuid) { mutableStateOf(false) }
    val phase = state.phase
    val connected = phase == Phase.Connected
    val colors = MaterialTheme.colorScheme
    // The clock of the duration: ticks once a second while the call is connected.
    val now by produceState(Instant.now(), connected) {
        while (connected) {
            value = Instant.now()
            delay(1.seconds)
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The call's details take the room the keypad and the controls leave and scroll on a small screen, so the
            // controls and the hang-up button always stay on screen.
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Text(call.number, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    statusText(state, now),
                    modifier = Modifier.testTag("incall.status"),
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor(phase, colors),
                )
                state.callID?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant) }
                if (state.warnings.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (warning in state.warnings.map { it.rawValue }.sorted()) {
                            Text(
                                warning,
                                modifier = Modifier.clip(CircleShape).background(Orange.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                state.stats?.let {
                    Text(statsLine(it), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                state.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }

            if (showKeypad && connected) DTMFKeypad(showsBackspace = false, keySize = 52.dp) { key -> call.perform { send(key) } }

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ControlButton(if (state.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic, "Mute", state.isMuted, connected, "incall.mute") {
                    call.perform { toggleMute() }
                }
                ControlButton(R.drawable.ic_dialpad, "Keypad", showKeypad, connected, "incall.keypad") { showKeypad = !showKeypad }
                ControlButton(R.drawable.ic_pause, "Hold", state.isOnHold, connected, "incall.hold") { call.perform { toggleHold() } }
                AudioRouteButton(routing, connected)
            }

            Box(
                Modifier
                    .padding(bottom = 8.dp)
                    .size(72.dp)
                    .alpha(if (phase.isActive) 1f else 0.4f)
                    .clip(CircleShape)
                    .background(HangupRed)
                    .clickable(enabled = phase.isActive, role = Role.Button, onClickLabel = "Hang up") { call.hangup() }
                    .testTag("incall.hangup"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_call_end), contentDescription = "Hang up", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
    }
}

/** A round icon with a caption under it, as the iOS controls. */
@Composable
internal fun ControlButton(
    @DrawableRes icon: Int,
    title: String,
    active: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val background = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant
        Box(Modifier.size(52.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(26.dp))
        }
        Text(title, style = MaterialTheme.typography.labelMedium)
    }
}

private fun statusColor(phase: Phase, colors: ColorScheme): Color = when (phase) {
    Phase.Connected -> CallGreen
    is Phase.Reconnecting -> Orange
    is Phase.Ended -> colors.onSurfaceVariant
    else -> colors.onSurface
}

internal fun statusText(state: CallState, now: Instant): String = when (val phase = state.phase) {
    Phase.Connecting -> "Connecting…"
    is Phase.Ringing -> if (phase.earlyMedia) "Ringing (early media)" else "Ringing…"
    Phase.Connected -> if (state.isOnHold) "On hold" else duration(state.connectedAt, now)
    is Phase.Reconnecting -> "Reconnecting (${phase.reason})…"
    is Phase.Ended -> CallViewModel.describe(phase.reason, phase.error)
}

/** `mm:ss` from [start] to [now]. */
private fun duration(start: Instant?, now: Instant): String {
    if (start == null) return "Connected"
    val seconds = Duration.between(start, now).seconds.coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}

internal fun statsLine(stats: CallStats): String {
    val sample = stats.sample
    val rtt = sample.rttMs?.let { String.format(Locale.US, "RTT %.0f ms", it) } ?: "RTT –"
    val jitter = sample.jitterMs?.let { String.format(Locale.US, "jitter %.0f ms", it) } ?: "jitter –"
    val loss = sample.lossPercent?.let { String.format(Locale.US, "loss %.1f%%", it) } ?: "loss –"
    val mos = sample.mos?.let { String.format(Locale.US, "MOS %.1f", it) } ?: "MOS –"
    val route = stats.media.candidateType?.let { " · $it" } ?: ""
    return "$rtt · $jitter · $loss · $mos$route"
}
