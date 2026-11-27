# Yolla Voice example (Android)

Sample Android app for the Yolla Voice SDK: place a PSTN call over SIP/WebRTC with Telecom, mute/hold/DTMF, audio
routes, quality warnings and a log of the app's events. It is also the reference integration of the SDK.
The Android counterpart of the iOS `YollaVoiceExample`: the same screens and texts, with CallKit replaced by Telecom
(`androidx.core:core-telecom`).

The app runs the SDK in **platform mode** (`VoiceClient(context)`): it only obtains a call token from its backend and
hands it to `connect(callToken = ...)`. The SIP gateway, the SIP identity and the ICE servers come from the platform;
the destination and the caller ID are inside the token. The app has no gateway settings.

## Run

1. Open the project that holds this folder in Android Studio (JDK 21, Android SDK platform 37.0) and run the
   `example` configuration, or from the command line:

       ./gradlew :example:installDebug

   The app depends on the Yolla Voice SDK (`io.yolla:yolla-voice-android`); minSdk 26, debug signing only.
2. Run on a real phone for audio. The emulator is fine for the UI, signalling and Telecom: its microphone gives
   silence and it has only the speaker route.
3. **Settings** has a token source and a switch, nothing else to fill in:
   - **Token URL**: your backend endpoint, `https://demo.yolla.io/api/calls` by default; before each call the app
     sends `POST <url>` with the JSON body `{"to": "<dialed number>"}` and expects `{"callToken": "<jwt>"}`. The
     request carries `Authorization: Bearer <YOLLA_VOICE_DEMO_TOKEN>`. The URL must be `https`: the manifest
     sets `android:usesCleartextTraffic="false"`, so an `http` URL fails on every API level (without it API 26–27
     would send the bearer in clear).

     The default address is Yolla's demo backend, and the demo token is the credential it expects. The token is
     not in the code: the build takes it from `YOLLA_VOICE_DEMO_TOKEN`, either the environment variable (handy
     on a build server, from a secret variable) or a line in the git-ignored `local.properties` in the project root:

         YOLLA_VOICE_DEMO_TOKEN=<your demo token>

     The app reads it as `BuildConfig.YOLLA_VOICE_DEMO_TOKEN` (`TokenProvider.demoToken`). Built without it, the
     app does not ask the backend and says what to set; a pasted token still works. To use your own backend,
     change the address in Settings and put its credential in the same setting. In your own app this request is
     authorised with your user's session, not with a value built into the app.
   - **Token** (pasted): a call token for quick manual tests; the call goes to the number inside the token.
   - **Use Telecom** (on by default): the call is registered with the system as a self-managed call. Off, the app
     drives the audio itself (direct mode).
4. On first Call the app asks for the microphone, notifications (API 33+) and nearby devices (Bluetooth, API 31+).
   Denying them does not block the call: without the microphone the SDK fails with `microphone_denied` before any
   signalling; without notifications the call goes on without its notification (a warning goes to the log).
5. **Dialer** (prefilled with `1001`) → enter the number in the format the platform expects (it is sent to the
   backend as typed) → Call.

Settings are kept in `SharedPreferences` for simplicity. In a production app keep tokens in the Android Keystore.

Ringback, busy and end tones come from the SDK; `CallViewModel` holds the Telecom end report for 0.6 s so the end
tone is heard before Telecom takes the audio away.

## Layout

Sources are under `src/main/kotlin/com/yollacalls/android/voice/example/`:

- `app/` — `ExampleApplication` (builds the one `AppModel` of the process), `AppModel` (owns the `VoiceClient`,
  settings, logs, the active call), `MainActivity`, `RootScreen` (tabs and the in-call screen over them),
  `ActivityPermissions`, `SdkVoice` and `Seams` (the small interfaces that let `AppModel` be tested without the SDK
  and Android).
- `settings/` — `Settings` model, persistence (`SettingsStore`), `SettingsScreen`.
- `auth/TokenProvider.kt` — fetches or returns the call token.
- `call/` — `CallViewModel` (follows one call in `VoiceClient.call` and sends the screen's commands to the client),
  dialer and in-call screens, DTMF keypad, audio route button.
- `telecom/` — `TelecomCallManager` (`CallsManager.addCall` and its callbacks), `TelecomControlling.kt` (one call's
  handle to Telecom and the requests that come from the system), `AudioRouting.kt` (the audio routes of a call),
  `CallService` (the foreground service with the call notification), `DirectAudioRouter` (routes without Telecom,
  through `AudioManager`), `ProximityLock`.
- `logs/` — `LogStore` and the screen with the app's own events (Telecom's with a `[telecom]` prefix). The SDK's log
  is not available to the app.

Tests: `src/test` (JVM: settings, token provider, call states, the Telecom flow and `AppModel` on fakes),
`src/androidTest` (emulator: the app's launch and screens, the real `CallsManager`, the service, permissions) and
`src/sharedTest` (fakes shared by both). `LiveCallThroughTheAppTests` places real calls through the app's UI and runs
only when asked for: `./gradlew :example:connectedDebugAndroidTest -Pyolla.liveCalls=true` with `YOLLA_TEST_NUMBER`
set to the number to call.

License: MIT (see `LICENSE` in this folder).

## Token endpoint example (Node, pseudo-code)

The server-side helper library is not published yet; the shape below is what the app expects.

```js
// Your backend signs a short-lived JWT with the API key secret; the app never sees the secret.
app.post('/calls', requireUserSession, (req, res) => {
  const token = new AccessToken(ACCOUNT_ID, API_KEY_ID, API_KEY_SECRET, { identity: req.user.id, ttl: 60 });
  token.addGrant(new VoiceGrant({ outgoing: true, to: req.body.to, callerId: 'anonymous' }));
  res.json({ callToken: token.toJwt() });
});
```

## Device checklist

Run on a real Android phone with a working gateway and go through:

1. Call with Telecom on: the call notification appears (with Hang up), ringback and the end tone are heard, mute, hold
   and end from a headset, a watch or the car work, a regular incoming call during ours puts ours on hold (or ends it
   while it is still ringing).
2. Call with Telecom off (Settings → Use Telecom): audio starts directly.
3. Audio heard both ways; mute, hold/unhold, DTMF digits (check on the far end).
4. Earpiece, speaker, Bluetooth and a wired headset via the Audio button; the screen turns off at the ear on the
   earpiece.
5. Wi-Fi → LTE and LTE → Wi-Fi during a call: "Reconnecting…" then the timer continues.
6. Airplane mode for 10 s: reconnect; longer than 20 s: the call ends with `media_timeout`.
7. Background the app and turn the screen off during a call: audio keeps running (foreground service).
8. Deny the microphone permission: Call shows `microphone_denied` before any signalling.
9. Logs tab: the app's events (Telecom, token request, call states), export via the share button.
10. Worth a look: candidates in the first offer right after launch, the tail of the tones over
    Bluetooth, the audio mode and focus together with Telecom.
11. Telecom on, notifications denied, Android 14+: the call starts and goes on without a visible notification
    (check that Telecom does not end it).
12. Our call started while a SIM call is up: Telecom holds the SIM call or refuses ours (the refusal is shown as
    "Telecom refused the call (code …)").
13. A remote hang-up with the screen locked: the call screen goes away after 2 s without unlocking, and the app no
    longer shows over the lock screen.
14. Mute from a watch, a car or a headset, then un-mute in the app: see "Hold and mute" below (the system mute stays).
15. Home pressed during the token request: the call goes on in the background, or ends with an error in the log
    (the system may not let the service start from the background); the app does not crash.
16. Rotate, Back and swipe from Recents during a call: the call goes on; after Recents the notification brings the
    call screen back.
17. A direct-mode call on the speaker, then a second direct-mode call: the second one starts on the earpiece.

## Notes for integrators

- **Who owns the client and the call.** `AppModel` is created once per process by `ExampleApplication` and owns the
  `VoiceClient` (created at the first call and kept) and the active `CallViewModel`. A call is not tied to any
  coroutine scope: it ends with `hangup()`. The in-call screen is bound to `AppModel.activeCall` and comes back when the
  app returns to the foreground.
- **Following one call.** `CallViewModel` reads `VoiceClient.call` right after `connect`, which already holds the new
  call in `Connecting`, and follows that call id until its `Disconnected`: the previous call still in the flow is
  ignored, and the end is never missed because it stays in the flow until the next `connect`. The screen state, the
  Telecom reports (`reportConnected`, `reportEnded`) and the log lines all come from the snapshots.
- **Call audio.** The SDK turns the call audio on at `connect` and off after the end tone; the app does not switch
  it. The SDK takes the audio mode and focus for the call; the app requests no focus of its own in either mode.
- **The foreground service.** `CallService` keeps the process alive and shows the `CallStyle` notification (Hang up,
  tap opens the app) in both modes; it does not own the call. It is started before `connect` (the notification is
  posted at once: core-telecom expects it within 5 s of `addCall`) and stopped after the call. `AppModel` tells it
  the mode. Its type: none on API 26–28, `phoneCall` on API 29; on API 30+ with Telecom `phoneCall`, plus `microphone`
  when `RECORD_AUDIO` is granted; on API 30+ in direct mode `microphone` when granted, else `phoneCall` (the call then
  fails with `microphone_denied` anyway). `microphone` without the permission would make `startForeground` throw on
  API 34+, and `phoneCall` on API 34+ needs `MANAGE_OWN_CALLS`, which only a Telecom app has. When the system refuses
  the type (`SecurityException`, e.g. `microphone` after the user left the app between Call and the service start)
  the service retries with `phoneCall`; when it cannot enter the foreground at all it writes a line to the log, stops
  and ends the call.
- **Hold and mute with `core-telecom`.** Hold from the app is requested from Telecom (`setInactive` / `setActive`)
  first and applied to the call on success; if the SDK refuses, Telecom's state is set back. Hold from the system is
  applied to the call; if the SDK refuses it while connected, the app mutes the microphone instead and shows "On hold"
  (Telecom's callbacks must not throw: that tears the whole call down). A system hold before the call is connected
  ends our call. core-telecom has no mute request, so mute from the app goes straight to the call; mute from the
  system is applied when it differs. The two are not the same switch: Telecom's mute (from a watch, a car or a
  headset) is the system's global microphone mute, while the app's Mute mutes the SDK's track. After a mute from the
  system, un-muting in the app un-mutes the track but does not clear the system mute: the other side still hears
  nothing until it is cleared where it was set. Telecom gives the app 5 s to answer `onSetInactive` / `onSetActive`;
  a hold the SDK takes longer to apply (a slow re-INVITE) exceeds that and Telecom treats the request as failed.
- **Direct mode** (Telecom off) does not react to a regular incoming call: neither the SDK nor the app handles the
  loss of audio focus. Use Telecom in a real app.
- **Keypad.** On the in-call screen the keypad opens above the Mute / Hold / Audio controls (unlike the iOS example),
  so the controls and the hang-up button stay on screen on a small display.
- **Token URL.** It must be `https`: the manifest sets `android:usesCleartextTraffic="false"` (API 26–27 would
  allow cleartext by default).
- **The end of a call.** `AppModel` dismisses an ended call 2 s after its end itself, so a remote hang-up with the
  screen off or the app in the background does not leave the call screen and the show-when-locked flags behind. A
  hang-up before `connect` (while Telecom is still adding the call) places no call at all. A direct-mode call resets
  the route it chose (`clearCommunicationDevice` / `isSpeakerphoneOn = false`) when it ends.
- The exported app log contains phone numbers; treat it as sensitive.
- Reconnect timings in the checklist are expectations, not guarantees: the SDK gives up after 20 s (`media_timeout`).
