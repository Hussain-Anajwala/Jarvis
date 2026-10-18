# Open-source and platform research (post-M6 upgrade, 2026-10-18)

What was studied, what was adopted, and why. "Checked" means the page was read during this work; "not
re-verified" marks statements from general platform knowledge that should be confirmed before relying on them.

## Wake word

### openWakeWord — adopted (models + reference pipeline)
- https://github.com/dscripka/openWakeWord (checked)
- Solves: on-device wake-phrase detection with small ONNX models; includes a pre-trained **"hey jarvis"** model.
- Pipeline (checked against `openwakeword/utils.py`): 16 kHz int16 audio in 80 ms (1280-sample) frames → mel
  spectrogram (with 480 samples of context, `x/10 + 2` transform) → 96-value embedding per 76-frame window →
  classifier over the last 16 embeddings. Default threshold 0.5.
- Licence: code Apache-2.0; **pre-trained models CC BY-NC-SA 4.0** (non-commercial, share-alike, attribution)
  because of their training data. The project's stated target is < 5% false rejects and < 0.5 false accepts per
  hour; no measured numbers are published for "hey jarvis".
- Adopted: `melspectrogram.onnx`, `embedding_model.onnx`, `hey_jarvis_v0.1.onnx` from release v0.5.1 in
  `app/src/main/assets/wakeword/` with attribution in `NOTICE.txt`. JARVIS is a non-commercial personal project,
  so the model licence is compatible; **a commercial release would need a different or retrained model.**
- Our port (`core/WakeWord.kt`) was checked against the Python reference on synthetic clips (positives
  0.998/0.999, negatives 0.001/0.000) and the same clips run on the phone in `WakeWordModelTest`.

### msnilsen/openwakeword-android — studied, not adopted
- https://github.com/msnilsen/openwakeword-android (checked)
- A Kotlin library running the same three ONNX stages with AudioRecord. Apache-2.0 code, CC BY-NC-SA models.
- Rejected as a dependency: 3 commits, no releases, no foreground-service or mic-sharing story, ONNX Runtime
  version not stated. The pipeline is small enough to own (about 150 lines) and test directly.

### Suxsem/AndroidWakeWord — studied, idea adopted in simplified form
- https://github.com/Suxsem/AndroidWakeWord (checked)
- Kotlin library for openWakeWord-compatible ONNX models with an always-on cheap voice-activity front end
  (Speex VAD, Silero VAD on demand), optional verifier model, XNNPACK/NNAPI acceleration. Apache-2.0 code;
  you bring your own models. 8 commits, v0.0.1 on JitPack.
- Not adopted as a dependency (very early, extra native components). Adopted ideas: **XNNPACK** execution
  provider and **gating the expensive models on audio level**. Our gate is an RMS threshold with a 1.28 s
  hangover; skipped chunks are replayed when sound returns, so detection output is unchanged (unit-tested and
  checked on the phone with the real models).

### Android VoiceInteractionService / AlwaysOnHotwordDetector — not used
- https://developer.android.com/reference/android/service/voice/VoiceInteractionService (page too large to
  quote; the following is not re-verified)
- System hotword APIs require JARVIS to be the user's default digital-assistant app, and DSP hotword capture
  (`CAPTURE_AUDIO_HOTWORD`) is limited to privileged apps. Replacing the default assistant is a large
  behavioural change for a personal app, so JARVIS uses a normal **microphone foreground service** instead:
  OFF by default, visible notification with Stop, started only from the open app (Android's background
  foreground-service start limits), pauses while JARVIS speaks or recognises a command.

### Porcupine (Picovoice), Vosk — alternatives considered (not re-verified)
- Porcupine: efficient and accurate, but needs a Picovoice account AccessKey and custom phrases from their
  console — an account/registration the mandate did not authorise.
- Vosk (Apache-2.0): full offline speech recogniser; keyword spotting with it is heavier (larger model, more
  CPU) than a dedicated wake-word model.

### ONNX Runtime Android — adopted
- `com.microsoft.onnxruntime:onnxruntime-android:1.22.0` (MIT). 1.19.2 shipped native libraries that were not
  16 KB page-aligned (Android 15+ requirement); 1.22.0 is aligned (checked by parsing the ELF program headers).
  `abiFilters` arm64-v8a + x86_64 keep the APK at ~55 MB.

## Voice output — ElevenLabs (adopted, optional, user key)
- https://elevenlabs.io/docs/api-reference/text-to-speech/stream (checked)
- `POST /v1/text-to-speech/{voice_id}/stream`, header `xi-api-key`, model `eleven_flash_v2_5` (low latency),
  `output_format=pcm_22050` played through AudioTrack. Default voice "George" (`JBFqnCBsd6RMkjVDRZzb`).
- The key is entered by the user in Settings and stored encrypted with an Android Keystore AES-GCM key; it is
  not in source, BuildConfig, or logs. Private answers (messages, memories, email) always use the phone voice.
  Any ElevenLabs failure falls back to the phone voice for that answer. Usage is billed per character by
  ElevenLabs; no key is configured, so no quota has been used.

## Music
- Spotify App Remote SDK (https://developer.spotify.com/documentation/android, not re-verified in detail):
  needs a registered Spotify client ID with the app fingerprint and, for on-demand track playback, Spotify
  Premium. **Not adopted** (registration not authorised; the test account is Free). Spotify requests use the
  documented `MEDIA_PLAY_FROM_SEARCH` intent and are reported as "opened search, playback not confirmed" unless
  a media session shows the new track playing.
- YouTube Music: a YouTube Data API search (music category) followed by a `music.youtube.com/watch?v=` link
  opened in the YouTube Music app actually starts playback. Playback is confirmed through
  `MediaSessionManager.getActiveSessions` (allowed because JARVIS holds notification-listener access).
- No unofficial extractors, private APIs, or accessibility automation were used.

## Live traffic
- Google Routes API (https://developers.google.com/maps/documentation/routes/config_trade_offs, checked):
  `routingPreference=TRAFFIC_AWARE`; `duration` is the traffic-aware ETA, `staticDuration` historical only.
  Traffic-aware requests "are billed at a higher rate". Needs a key with billing enabled.
- Mapbox Directions (https://docs.mapbox.com/api/navigation/directions/, checked): `driving-traffic` profile
  "factors in current and historic traffic"; `duration` vs `duration_typical`. Needs an access token.
- Adopted as an abstraction (`core/TrafficRouting.kt`): Google Routes → Mapbox → OSRM. **No key is configured**,
  so the app uses OSRM and says "no traffic data: road-network time via OSRM". Traffic delay is reported only
  when a provider returns both a live and a typical duration.

## UI references
- Now in Android (https://github.com/android/nowinandroid), compose-samples / Jetchat
  (https://github.com/android/compose-samples) — Apache-2.0. Studied for patterns, no code or assets copied:
  a single `Scaffold` + `NavigationBar` shell with short tab labels, Material 3 colour roles instead of
  hard-coded colours (so light and dark both work), a chat list with user/assistant bubbles and a docked
  composer (Jetchat), `FilterChip` for filters, live regions for status changes.
