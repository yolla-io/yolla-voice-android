//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yollacalls.android.voice.example.R
import com.yollacalls.android.voice.example.app.AppModel

/** Callers' green, as the iOS example's tinted Call button. */
internal val CallGreen = Color(0xFF34C759)

@Composable
fun DialerScreen(appModel: AppModel, modifier: Modifier = Modifier) {
    // Prefilled with the test number so a call is one tap away; edit or clear it to dial another.
    var number by rememberSaveable { mutableStateOf("1001") }
    val settings by appModel.settings.collectAsStateWithLifecycle()
    val placing by appModel.isPlacing.collectAsStateWithLifecycle()
    val problems = settings.validate()
    val canCall = problems.isEmpty() && number.isNotBlank() && !placing

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextField(
            value = number,
            onValueChange = { number = it },
            modifier = Modifier.fillMaxWidth().testTag("dialer.number"),
            textStyle = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light, textAlign = TextAlign.Center),
            placeholder = {
                Text(
                    "4915112345678",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light),
                    textAlign = TextAlign.Center,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        DTMFKeypad { key -> number = if (key == "⌫") number.dropLast(1) else number + key }

        problems.firstOrNull()?.let { first ->
            Text("Settings: $first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = { appModel.placeCall(number.trim()) },
            enabled = canCall,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("dialer.call"),
            colors = ButtonDefaults.buttonColors(containerColor = CallGreen, contentColor = Color.White),
        ) {
            Icon(painterResource(R.drawable.ic_call), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(8.dp))
            Text("Call", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}
