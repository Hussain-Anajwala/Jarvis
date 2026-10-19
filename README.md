# JARVIS — personal AI assistant for Android

JARVIS is a Kotlin / Jetpack Compose assistant for Android phones. A language model (Groq first, Google Gemini as
fallback, an on-device rule parser when offline) turns requests into tool calls, and a safety-gated tool layer
carries them out with the phone's real capabilities: calendar, reminders, Clock, calls, messages, music, maps,
notifications, read-only Gmail, memory, weather, and travel time. It answers by text and voice and can listen for
an opt-in on-device "Hey JARVIS" wake word.

The project favours honesty over polish: anything JARVIS cannot confirm (a call, playback, a cancelled alarm, live
traffic) is reported as unconfirmed, and messages, emails, and calls always need the user's own tap.

## Features

| Area | What it does |
|---|---|
| Conversation | Typed or spoken requests; asks for missing details (time, place, dates, recipient) and keeps multi-step tasks going across turns |
| Plans | Meetings with a linked departure reminder; cancelling a plan cancels its calendar event and reminder |
| Phone tools | Alarm and timer (Clock handoff), cancelling/dismissing them where Clock allows, flashlight, volume, media keys, navigation, YouTube, music (YouTube Music playback confirmed from its media session), calls with confirmation |
| Contacts | Unicode-aware name matching (emoji, punctuation, case); several matches or numbers become a pick list |
| Messages | Prefilled SMS / WhatsApp drafts (you press send); counts, lists, and opt-in summaries of new message notifications |
| Email | Read-only Gmail (unread count, today's mail, opt-in summary); conversational drafting of professional emails handed to your mail app |
| Memory | "Remember that …"; a Memory tab to view, edit, and delete; up to 3 relevant memories are sent with a cloud request |
| Context | Weather (Open-Meteo), travel time from your location (OSRM, or live traffic with a Google Routes / Mapbox key), daily briefing |
| Voice | Spoken answers with the phone voice or ElevenLabs (curated voices), speech-only normalisation of dates and units, follow-up listening after a question, opt-in "Hey JARVIS" |
| App | Assistant, Plans, Memory, Activity (decision history), and Settings tabs; light/dark/system theme |

The honest per-feature status, including what has and has not been verified on a physical phone, is in
[docs/CAPABILITIES.md](docs/CAPABILITIES.md).

## Architecture

- **UI:** Jetpack Compose (Material 3), a single `MainActivity` with a five-tab shell; one `JarvisViewModel`.
- **Reasoning:** `ProviderChain` with Groq (OpenAI-compatible chat completions) → Gemini (two keys, daily/minute
  budgets) → `OfflineRuleProvider`. Recognised phone commands are parsed locally and never sent to a model.
- **Tools:** model output is validated against `ReasoningToolSchema`; `ToolRouter` and `ToolRiskGate` enforce a
  static risk level per tool, and medium/high-risk tools always stop at a confirmation dialog.
- **Data:** Room (exported schemas in `app/schemas/`), WorkManager for background plan checks and the daily
  briefing, SharedPreferences for settings; secrets entered on older builds are encrypted with the Android Keystore.
- **Networking:** OkHttp + `org.json` for Groq, Gemini, Gmail REST, YouTube Data, ElevenLabs, Open-Meteo,
  Nominatim, OSRM, Google Routes, and Mapbox.
- **Wake word:** openWakeWord ONNX models run with ONNX Runtime Android in a microphone foreground service.

Stack: Kotlin 2.0.21, Android Gradle Plugin 8.6.0, Gradle 8.9 (wrapper), compileSdk/targetSdk 35, minSdk 26,
Compose BOM 2024.12.01, Room 2.6.1 (KSP), WorkManager 2.10.0, OkHttp 4.12.0, Play services auth 21.3.0,
ONNX Runtime Android 1.22.0. Details: [docs/TRD.md](docs/TRD.md).

## Setup on Windows

Prerequisites:
- Android Studio (or the Android SDK command-line tools) with **Android SDK Platform 35** installed.
- **JDK 17 or newer** (the project is built with JDK 22).
- An Android phone with **USB debugging** enabled, or an x86_64 emulator (the APK includes arm64-v8a and x86_64
  native libraries only).
- Git.

```powershell
git clone https://github.com/Hussain-Anajwala/Jarvis.git
cd Jarvis
```

Create `local.properties` in the repository root (it is git-ignored). Android Studio writes `sdk.dir`
automatically; otherwise add it yourself:

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
GROQ_API_KEY=...
GEMINI_API_KEY=...
```

### Credentials (`local.properties`)

All keys are read at build time through `BuildConfig` (see `app/build.gradle.kts`). Every key is optional; without
any provider key JARVIS runs on the offline rule parser.

| Name | Used for | Notes |
|---|---|---|
| `GROQ_API_KEY` | Primary language model | `GROQ_MODEL` optional (default `llama-3.3-70b-versatile`; the app discovers available models) |
| `GEMINI_API_KEY`, `GEMINI_API_KEY_2` | Fallback language model | `GEMINI_MODEL` (default `gemini-flash-latest`), `GEMINI_DAILY_BUDGET` (18), `GEMINI_MINUTE_BUDGET` (4) |
| `YOUTUBE_API_KEY` | Exact YouTube / YouTube Music results | Without it, JARVIS opens a search instead |
| `ELEVENLABS_API_KEY` | ElevenLabs voice | Optional; also read from an environment variable of the same name |
| `GOOGLE_ROUTES_API_KEY` | Live-traffic travel time | Optional; billed by Google; environment variable also works |
| `MAPBOX_ACCESS_TOKEN` | Live-traffic travel time (alternative) | Optional; environment variable also works |

A key compiled into an APK can be extracted from it. Use restricted, revocable keys for this personal build; a
distributed build should call these services through a server that holds the keys.

### Gmail (optional, read-only)

Gmail uses Google Identity Services `AuthorizationClient` with the `gmail.readonly` scope only. In Google Cloud:
1. Enable the **Gmail API**.
2. Configure the OAuth consent screen (Testing is fine) and add your account as a test user.
3. Create an **Android** OAuth client for package `com.jarvis` with your signing certificate's SHA-1
   (`.\gradlew.bat :app:signingReport` prints the debug SHA-1).

No `google-services.json` is needed. Google shows its consent screen the first time you ask about email.

## Build, install, and test

```powershell
.\gradlew.bat :app:assembleDebug          # APK: app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :app:installDebug           # install on the connected phone (keeps app data)
.\gradlew.bat :app:testDebugUnitTest      # JVM unit tests
.\gradlew.bat :app:lintDebug              # lint (report in app\build\reports\)
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true"
```

Always pass `leaveApksInstalledAfterRun=true` to instrumented tests: without it, the Android Gradle Plugin uninstalls
JARVIS afterwards, which deletes its data.

**Test coverage (as of 2026-10-19):** 182 JVM unit tests (one live-evaluation test is skipped unless an engine is
selected) covering parsing, tool routing and risk gates, contacts, clock commands, email dialogue, speech output and
normalisation, wake-word streaming, notifications, Gmail, memory, travel, and the evaluation harness; 4 instrumented
tests on a device (3 Room migrations, the wake-word models on real audio clips). There are no Compose UI tests.

**Evaluation:** a 126-utterance labelled dataset in `eval/` measures tool choice and arguments per engine. Latest
results ([docs/EVAL.md](docs/EVAL.md)): the app's hybrid routing 97.6% tool / 97.8% argument accuracy, Groq alone
93.7% / 95.5%, offline parser 69.0% / 60.7%; Gemini is unmeasured (quota).

```bash
# Git Bash; engine = offline | groq | hybrid | gemini
JARVIS_EVAL_ENGINE=offline ./gradlew :app:testDebugUnitTest --tests com.jarvis.eval.LiveEvalTest --rerun
```

## Permissions and privacy

| Permission | Why | When it is requested |
|---|---|---|
| Calendar | Create and cancel events | Before the first meeting request |
| Contacts | Resolve names to numbers and email addresses on the phone | Before the first call/message request |
| Phone (`CALL_PHONE`) | Place a call after you confirm it | At the confirmation |
| Microphone | Push-to-talk, follow-up answers, "Hey JARVIS" | When you first use voice |
| Location | Weather and travel time ("while using the app"; approximate is enough) | Settings → Connections and permissions |
| Notifications | Reminders, briefing, wake-word and "tap to continue" notifications | Android 13+ |
| Notification access (special) | Count and list incoming message notifications | Opened from Settings; optional |

Alarms and timers use the Clock app's public intents (`SET_ALARM`, no runtime permission).

**Wake word ("Hey JARVIS")** is **off by default**. When you turn it on, a microphone foreground service listens
with a visible notification (Stop / Cancel). The wake phrase is matched on the phone by the openWakeWord model; audio
is not stored or uploaded. After the phrase, your request is transcribed by Android's speech recogniser and handled
like a typed one. Android only lets the service start while JARVIS is open, and does not let JARVIS open itself or
other apps from the background, so steps that need the screen arrive as a "tap to continue" notification. The
pre-trained wake-word model is licensed **CC BY-NC-SA 4.0** (non-commercial); see
`app/src/main/assets/wakeword/NOTICE.txt`.

What leaves the phone: the current request and recent turns go to the configured model provider (Groq, then
Gemini); message and email text only after you confirm a summary; answer text to ElevenLabs when that voice is
selected (message/email content only if you allow it); coordinates to Open-Meteo, Nominatim, and the routing
provider. Details: [docs/TRD.md](docs/TRD.md) §6.

## Known limitations

- Placing a real call, a real spoken "Hey JARVIS" with follow-up answers, background wake-word requests, and
  dismissing a ringing alarm are **not yet verified on a physical phone** (see
  [docs/verification/VERIFY-POST-M6.md](docs/verification/VERIFY-POST-M6.md)).
- Android offers no API to list or cancel another app's alarms or running timers. On Samsung Clock, a request to turn
  off an upcoming alarm only opens the alarm list; JARVIS says so and never claims the cancellation.
- Live traffic needs a Google Routes or Mapbox key; otherwise travel time is OSRM road-network time, labelled so.
- Spotify (Free) can only open search results; Spotify autoplay would need Premium and the App Remote SDK.
- Memory retrieval is word-overlap based; paraphrases without a shared word are missed.
- Groq's free tier rate-limits larger models; answers then come from a smaller model (labelled).
- The wake-word model is non-commercial (CC BY-NC-SA 4.0).

## Repository structure

```
app/
  src/main/java/com/jarvis/   UI (MainActivity, Shell, Settings, Activity screens)
    core/                      reasoning, tools, contacts, clock, email, speech, wake word, travel, Gmail
    data/                      Room entities, DAOs, database
    ui/                        ViewModel, theme, voice I/O
  src/main/assets/wakeword/    openWakeWord ONNX models + NOTICE.txt
  src/test/                    JVM unit tests        src/androidTest/   instrumented tests and test audio
  schemas/                     exported Room schemas (used by migration tests)
eval/                          labelled utterances, raw results, per-run reports
docs/                          specifications, status, decisions, evaluation, research, verification records
gradle/, gradlew, gradlew.bat  Gradle wrapper (8.9)
AGENTS.md, CLAUDE.md, .github/ instructions for coding agents
```

Documentation:
- Status and decisions: [PROJECT-STATE](docs/PROJECT-STATE.md), [DECISION-LOG](docs/DECISION-LOG.md),
  [CAPABILITIES](docs/CAPABILITIES.md)
- Specifications: [PRD](docs/PRD.md), [TRD](docs/TRD.md), [SCHEMA](docs/SCHEMA.md),
  [ASSISTANT-ROADMAP](docs/ASSISTANT-ROADMAP.md), [IMPLEMENTATION-PLAN](docs/IMPLEMENTATION-PLAN.md)
- Original (v1) design: [APP-FLOW](docs/APP-FLOW.md), [UI-UX](docs/UI-UX.md),
  [design tokens](docs/design/stitch-design-system.md)
- Evaluation and research: [EVAL](docs/EVAL.md), [OPEN-SOURCE-RESEARCH](docs/OPEN-SOURCE-RESEARCH.md)
- Verification records: [docs/verification/](docs/verification/) (per-milestone checklists, results, and the
  original Phase 1 UI dumps)
- Presentation: [DEMO-SCRIPT](docs/DEMO-SCRIPT.md), [CROSS-QUESTIONING](docs/CROSS-QUESTIONING.md)

## Security

- Never commit `local.properties`, keystores, or `.env` files (all git-ignored).
- Gmail access is read-only; JARVIS never sends email or messages and never places a call without confirmation.
- Logs record only lengths, IDs, HTTP status codes, and timings, never message text, transcripts, or keys.
- Report a suspected exposed key by revoking it at the provider first.

## Troubleshooting

| Problem | Fix |
|---|---|
| "SDK location not found" | Add `sdk.dir` to `local.properties` |
| Build fails with an unsupported Java version | Use JDK 17+ and point `JAVA_HOME` at it |
| App data disappeared after instrumented tests | Run them with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` |
| Engine label says "Offline parser" | No provider key in `local.properties`, or no network |
| "Hey JARVIS" switch shows "Not running" | Open JARVIS once (Android only starts the microphone service while JARVIS is visible) |
| A permission button opens Settings instead of asking | Android stops asking after repeated denials; allow it in the app's permission page |
| ElevenLabs answers use the phone voice | Settings → Voice replies shows the reason (no key, network, quota, or message/email content) |
| Gmail consent never appears | Check the Android OAuth client's package name and SHA-1 and that your account is a test user |

## License

No license has been chosen for this repository yet. Third-party components keep their own licences, notably the
openWakeWord models (CC BY-NC-SA 4.0, see the NOTICE file above) and ONNX Runtime (MIT).
