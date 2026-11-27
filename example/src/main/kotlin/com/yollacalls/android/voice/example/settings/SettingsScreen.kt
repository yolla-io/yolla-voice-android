//
// Copyright (c) 2026 Yollacalls OÜ
// SPDX-License-Identifier: MIT
//
// Part of the Yolla Voice example app; see the LICENSE file in the example's folder.
//

package com.yollacalls.android.voice.example.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yollacalls.android.voice.example.app.AppModel

private const val MODE_URL = 0
private const val MODE_PASTED = 1

@Composable
fun SettingsScreen(appModel: AppModel, modifier: Modifier = Modifier) {
    val saved by appModel.settings.collectAsStateWithLifecycle()
    // The form starts from what is saved and is reloaded after every save.
    var tokenMode by rememberSaveable(saved) { mutableIntStateOf(if (saved.tokenSource is TokenSource.Pasted) MODE_PASTED else MODE_URL) }
    var tokenURL by rememberSaveable(saved) { mutableStateOf((saved.tokenSource as? TokenSource.Url)?.url ?: "") }
    var pastedToken by rememberSaveable(saved) { mutableStateOf((saved.tokenSource as? TokenSource.Pasted)?.token ?: "") }
    var useTelecom by rememberSaveable(saved) { mutableStateOf(saved.useTelecom) }

    val draft = Settings(
        tokenSource = if (tokenMode == MODE_URL) TokenSource.Url(tokenURL.trim()) else TokenSource.Pasted(pastedToken),
        useTelecom = useTelecom,
    )
    val problems = draft.validate()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Call token")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Token URL", "Token").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = tokenMode == index,
                    onClick = { tokenMode = index },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    icon = {},
                ) { Text(label) }
            }
        }
        if (tokenMode == MODE_URL) {
            OutlinedTextField(
                value = tokenURL,
                onValueChange = { tokenURL = it },
                modifier = Modifier.fillMaxWidth().testTag("settings.tokenUrl"),
                placeholder = { Text(Settings.defaultTokenURL) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            Footnote("Before each call the app sends POST <url> with {\"to\": \"<number>\"} and expects {\"callToken\": \"…\"}")
        } else {
            OutlinedTextField(
                value = pastedToken,
                onValueChange = { pastedToken = it },
                modifier = Modifier.fillMaxWidth().testTag("settings.pastedToken"),
                placeholder = { Text("Call token (one per call)") },
                minLines = 3,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            )
            Footnote("The call goes to the number inside the token, not the one typed in the dialer.")
        }

        SectionTitle("Call")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Use Telecom", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = useTelecom, onCheckedChange = { useTelecom = it }, modifier = Modifier.testTag("settings.useTelecom"))
        }

        Button(onClick = { appModel.updateSettings(draft) }, modifier = Modifier.fillMaxWidth().testTag("settings.save")) {
            Text("Save")
        }
        if (problems.isNotEmpty()) {
            Text(problems.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Footnote(
            "The gateway, the SIP identity and the caller ID come from the platform and the call token. Settings are " +
                "stored in SharedPreferences for this example; keep tokens in the Keystore in production.",
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Footnote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
