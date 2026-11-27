# Yolla Voice SDK for Android

Outgoing PSTN calls from your Android app through the Yolla voice platform, over SIP on WebSocket and WebRTC.
Version 0.1.0. The iOS SDK is at [yolla-io/yolla-voice-ios](https://github.com/yolla-io/yolla-voice-ios).

The SDK may be used only under a commercial agreement with Yolla Calls; see [LICENSE](LICENSE). The example app in
[`example/`](example/README.md) is under the MIT License.

## Installation

The SDK is a Maven artifact in this repository. Add the repository to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://raw.githubusercontent.com/yolla-io/yolla-voice-android/main/maven") }
    }
}
```

and the SDK to your app module:

```kotlin
dependencies {
    implementation("io.yolla:yolla-voice-android:0.1.0")
}
```

Its dependencies come from Maven Central with it: WebRTC (`io.github.webrtc-sdk:android`), OkHttp 4.12,
kotlinx-coroutines 1.8.1 and kotlinx-serialization 1.7.3. These are the oldest versions the SDK supports: an app
with newer ones keeps its own.

Requirements: `minSdk` 26, Java 11 or newer, Kotlin 1.9 or newer (the SDK is built with Kotlin 2.0). The SDK's
manifest adds `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `INTERNET` and `ACCESS_NETWORK_STATE` to your app. Its R8 rules
come inside the AAR: nothing to add to your `proguard-rules.pro`.

## Using the SDK

An app places a call with a call token from its backend. There is nothing to configure: where to connect and which
media relays to use, the SDK learns from the platform with that token.

    val client = VoiceClient(applicationContext)   // cheap on the main thread, WebRTC starts at the first call

    // the call screen, the notification, the service
    client.call.collect { call ->
        when (call?.state) {
            Call.State.Connected -> ...
            Call.State.Disconnected -> showEnded(call.endReason, call.error)
            else -> ...
        }
    }

    // place a call
    try {
        client.connect(tokenFromYourBackend)
    } catch (e: VoiceError) {
        // the call did not start: no microphone permission, the token cannot be read, a call is already in progress
    }

    // during the call
    client.setMuted(true)
    client.hangup()

- **`client.call`** is a `StateFlow<Call?>`: `null` until the client's first call, then the call as it is now, a new
  value every time something about it changes. `Call` is an immutable snapshot (state, end reason, error,
  `wasConnected`, mute, hold, early media, quality warnings): a `Call` kept in a variable does not change, read the
  current one from `client.call`. A `StateFlow` keeps only the latest value, so a short intermediate state (a quick
  `Reconnecting`) may be skipped; the final `Disconnected` is never lost. A subscriber must not block its thread: it
  may run inside the `connect` call that changed the value. Apps build a `Call` for their tests and previews with
  `Call { callID = "call_1"; state = Call.State.Connected }`.
- **The outcome of a call.** An ended call stays in `client.call` until the app calls `connect` again; the SDK never
  replaces or clears it. The only way to lose a call's outcome is to start the next call before the subscriber has
  handled the end of the previous one, so collect call history and analytics in a subscriber that lives as long as the
  app, not a screen. `wasConnected` tells "never got through" from "dropped after the answer" when the end reason is
  `Failed`.
- **`connect`** returns at once and is safe from any thread. It throws `VoiceError` only when no call can start, and
  then `client.call` does not change: `call_in_progress` (the client's call has not ended), `invalid_token` (the token
  cannot be read), `microphone_denied`. Otherwise `client.call` holds the new call in `Connecting` right away, and
  everything else (the platform request, signalling, media, the answer or the failure) comes in the flow; a set-up
  that fails ends the call as `Disconnected` with `endReason = Failed` and the error.
- **The token is single-use.** Ask your backend for a new one before every call. An expired or already used token is
  refused by the platform and the call ends in the flow with `invalid_token`.
- **`hangup()`** is a plain function, safe from any thread and from a scope that is already cancelled: it starts
  ending the call on the client's own scope and returns at once, never throws, and a second call is harmless. The
  end comes in `client.call`. It ends the call at any moment, also while it is still being set up (it then ends
  `Cancelled`), and does nothing without a call. There is no `disconnect()`: every call has its own signalling
  connection, closed when the call ends, so there is nothing to close between calls.
- **The other commands** (`setMuted`, `setOnHold`, `sendDigits`, `stats`) are `suspend` and act on the call in
  `client.call`. `setMuted` during the set-up is kept and applied when the call starts. `Call.isMuted` is the
  user's wish and changes at once; the microphone follows it (and stays closed while the call is on hold). `setMuted`
  throws only `invalid_call_state`, when there is no call: a media failure is logged and the call goes on. `setOnHold` and `sendDigits` need an answered call and throw `invalid_call_state` otherwise.
  `stats()` gives the latest audio statistics, the last ones taken once the call has ended, `null` before the first
  call.
- **End a call and call again at once:** wait for the end in the flow, then place the next call.

      client.hangup()
      client.call.first { it == null || it.state == Call.State.Disconnected }
      client.connect(newTokenFromYourBackend)

- New values may be added to the SDK's enums later, so keep an `else` branch.

### Integrating on Android

What the SDK leaves to the app (the example app in [`example/`](example/README.md) does all of it and is the
reference integration):

- **Microphone permission.** The SDK only checks `RECORD_AUDIO` (`connect` fails with `microphone_denied`); the app asks
  the user for it before the first call.
- **Call audio.** The SDK turns the call audio on at `connect`, so that ringback and early media are heard, and off
  once the call has ended and its end tone has played; the app does not switch it. If the audio controller cannot be
  created, the call ends with `media_failed` before anything is dialed. Audio routing (speaker, Bluetooth, wired) is
  the app's, through Telecom or `AudioManager`.
- **Background calls.** A call that must go on while the app is in the background needs a foreground service that the
  app starts while it is visible: type `phoneCall` with Telecom, otherwise `microphone`, with
  `FOREGROUND_SERVICE_PHONE_CALL` / `FOREGROUND_SERVICE_MICROPHONE` on API 34+ (and `POST_NOTIFICATIONS` for its
  notification on API 33+). The SDK starts none. Without it the microphone records silence in the background and the
  system may cut the signalling connection.
- **Who owns the client.** Keep the client, and the subscriber that records each call's outcome, in a process-scoped
  owner: the `Application` or the call's `Service`. Screens subscribe to `client.call` for as long as they are shown.
  A call is not tied to any coroutine scope of the app: it ends with `hangup()`.
- **Data safety.** The SDK derives a stable device id from `ANDROID_ID` (sent as `+sip.instance` and as `deviceId` to
  the platform) and, when the platform asks for it, uploads its own log after a call (it contains dialed numbers,
  network addresses and the device model). Declaring this in the app's Play Data safety form is the app's job.

## Example app

[`example/`](example/README.md) is a complete integration: Compose screens, Telecom, the call's foreground service and
a call token from a backend. Open this repository in Android Studio and run the `example` configuration, or:

    ./gradlew :example:installDebug

It builds against the SDK in this repository's `maven/` folder. Before the first call put the token of your backend
(or the demo backend's token you were given) into the git-ignored `local.properties`:

    YOLLA_VOICE_DEMO_TOKEN=<token>
