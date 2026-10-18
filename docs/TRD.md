# Technical Requirements Document (TRD) — v2.0
Supersedes v1.0 (v1 remains in git history). Direction: LLM tool-calling agent over a safety-gated tool layer.

## 1. Architecture
```
UI (Compose)  ->  ConversationManager (last N turns)
                        |
                  AgentLoop  <->  ReasoningProvider  { Groq | Gemini key 1 | Gemini key 2 | OfflineRuleProvider }
                        |
                  ToolRegistry  ->  PermissionLayer + RiskGate  ->  Tool.execute()
                        |
   Calendar | Alarm/Timer | Media | Call | Navigate | Message/Email handoff |
   Notifications | Gmail | Memory | Location/Weather | Plan Engine
                        |
          Room (local) + DecisionTrace (every tool call)
```
Layers kept from v1: Plan Engine (dependency graph + cascade), Decision Trace, Permission Center,
Background Engine (WorkManager). New in v2: AgentLoop, ToolRegistry, Groq/Gemini provider chain, personal
memory, notification store, Gmail client, evaluation harness.

## 2. Reasoning
- `ReasoningProvider` interface (kept). Runtime order: Groq OpenAI-compatible chat completions,
  Gemini key 1, Gemini key 2 (each with configured/discovered Flash models), then
  `OfflineRuleProvider`.
- Groq models are discovered from `/openai/v1/models`; Groq currently documents that all hosted
  models support local tool use. Selection prefers active instruct-tuned/larger models, with
  `GROQ_MODEL` as the configurable preference and a persisted successful-model cache.
- Gemini requests are budgeted locally by key slot + model: defaults are 18/day and 4/minute,
  configurable by `GEMINI_DAILY_BUDGET` and `GEMINI_MINUTE_BUDGET`. Daily counters roll at
  midnight America/Los_Angeles. Permanent quota exhaustion skips further calls until that reset.
- Providers honor bounded transient 429 `Retry-After`, retry transient 429/503 within limits,
  record status and model, and cool down after provider-level failure. Fallback is automatic when
  providers are unavailable or no key is configured.
- One shared tool registry/schema supplies each provider's native function-call schema. Tool
  results provide their user-facing success message locally; a second model call is not made just
  to paraphrase a completed action.
- The UI and Decision Trace record provider, model, and HTTP status for attempts.
- All cloud providers share one system prompt (`JarvisSystemPrompt`) that includes today's date and time zone so relative
  dates resolve without asking, names the event as the title, and asks one short question for a missing time, place, or recipient.
- Tool arguments are parsed by one shared parser: unknown keys are rejected, optional keys may be `null`
  (declared nullable in both providers' schemas), numbers/booleans become strings before validation.
- A provider that throws is treated like a provider outage by `ResilientReasoningProvider` (fail over, never crash).
- When a cloud tool call is rejected (missing/placeholder arguments), JarvisCore answers with the offline parser's
  clarifying question when it has one.
- Routing (`ToolRouter`) refuses unregistered/unavailable tools, schema-invalid arguments, and medium/high-risk tools that
  lack a confirmation UI. Cancellation phrasing (cancel/delete/remove/"call off") is never parsed as a phone action.

## 3. Agent loop
```
loop (max 5 iterations):
  response = provider.generate(system_prompt, history, tools_schema)
  if response is text: return it
  call = validate(response.functionCall, tool.schema)      // reject malformed
  result = registry.dispatch(call)                         // RiskGate inside
  if result is NeedsConfirmation: surface dialog, resume after the user's choice
  history += (call, result)
```
System prompt rule: never assume time, date, location, or recipient; ask one short question when
required arguments are missing.

## 4. Tool contract (as implemented)
Each tool is a `ReasoningToolDefinition` in `ReasoningToolSchema` (ProviderChain.kt): name, model-facing
description, required and optional string fields, a **static** risk level, and required permissions. The same
definitions generate the Groq (OpenAI-compatible) and Gemini function schemas, so both providers see identical tools.
```
model/local parser → PlanStep(toolName, args)
  → ReasoningToolSchema.parseArguments / normalize   (unknown keys rejected, null optionals dropped)
  → ToolRouter.route(step, availableTools)           (availability, validate(), risk gate)
      Reject | ConfirmCancel | DraftCommunication | AlarmHandoff | PhoneTool | FavoriteSong
      | Messages | Email | Memory | Weather | Briefing | MeetingCreate | Agent
  → handler in JarvisCore (local data) or the UI layer (needs an Activity: permissions, consent, intents)
```
Medium/high-risk tools are refused unless they have a confirmation UI (`calendar.cancel`, `communication.draft`,
`call_contact`, `summarize_new_messages`, `summarize_emails`, `compose_email`). Recognised commands are parsed on
the device by `PhoneCommandParser` and never sent to a cloud model; the M1 agents (Calendar, Reminder, Task) are
called through the same router. Adding a capability = one definition + one route + one handler + tests.

## 5. Android integration matrix
| Capability | Mechanism | Permission / access | Risk |
|---|---|---|---|
| Calendar create / cancel | CalendarContract provider | READ/WRITE_CALENDAR | low / medium |
| Alarm, timer | AlarmClock intents | SET_ALARM | low |
| YouTube | YouTube Data API v3 search + view intent, or YouTube search URL when no key/quota | INTERNET, optional `YOUTUBE_API_KEY` | low |
| Music | MediaStore PLAY_FROM_SEARCH intent | none | low |
| Media keys, volume | AudioManager media-key dispatch / stream volume | none | low |
| Flashlight | CameraManager torch (`setTorchMode` does not open the camera) | none | low |
| Call | local Contacts Provider resolution + ACTION_CALL after explicit confirmation | READ_CONTACTS, CALL_PHONE runtime permission | medium |
| Navigate | `google.navigation:` intent, `geo:` search fallback | none | low |
| Favorite song | local SharedPreferences key-value (M2); M5 memory store | none | low |
| Message handoff | ACTION_SEND / SENDTO | none | medium |
| Notification reading | NotificationListenerService → local `notification_records` | special access via Settings | low (count/list), medium (summary: sends text after confirmation) |
| Gmail read | Gmail REST API over OkHttp; token from Identity `AuthorizationClient` (play-services-auth 21.3.0), memory-only | OAuth consent (gmail.readonly only), INTERNET | low (count/list), medium (summary) |
| Email compose | address from Contacts on device; model drafts from name + request; editable; `mailto:` ACTION_SENDTO (user sends) | READ_CONTACTS | medium |
| Location | LocationManagerCompat current fix (≤10 s) or last known (≤30 min) | ACCESS_FINE/COARSE_LOCATION (runtime, from Permission Center) | low |
| Geocoding / routing / weather | Nominatim (identifying User-Agent) / `TrafficRouter` (Google Routes TRAFFIC_AWARE → Mapbox driving-traffic → OSRM, keys optional and Keystore-encrypted) / Open-Meteo over OkHttp | INTERNET | low |
| Music playback | YouTube Data API song search → `music.youtube.com` link in YouTube Music; `MEDIA_PLAY_FROM_SEARCH` for Spotify; playback confirmed via `MediaSessionManager.getActiveSessions` | notification-listener access (for session reads), INTERNET | low |
| Voice | SpeechRecognizer; output via `SpeechOutputController` (phone TextToSpeech, or ElevenLabs streaming PCM with a user key) | RECORD_AUDIO, INTERNET (ElevenLabs) | low |
| Wake word | openWakeWord ONNX models (ONNX Runtime 1.22.0, XNNPACK) in a `microphone` foreground service, OFF by default; `MicArbiter` gives the mic to TTS/recognition first. After the phrase, `WakeCommandCoordinator` captures the next request with SpeechRecognizer inside the service and hands it to the live ViewModel (answer read aloud); otherwise a private "tap to run" notification. Activity launches requested while no JARVIS screen is visible are parked behind a notification (`BackgroundHandoff`) because Android blocks them | RECORD_AUDIO, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS | low (wake audio never leaves the phone; the spoken request goes through Android speech recognition) |

Out of scope: arbitrary cross-app control via Accessibility Service (fragile, restricted, may
violate third-party terms); direct WhatsApp sending; any financial action.

## 6. Privacy data flow
Conversation prompts (the current utterance and the last ten conversation turns) may be sent to
Groq first, then to either configured Gemini API key if the earlier provider is unavailable. The
offline parser receives the same request locally. Recognized M2 phone commands are parsed locally
before provider selection; in particular, call-contact lookup and phone numbers remain local.
YouTube video search sends the requested search query to Google's YouTube Data API only when
`YOUTUBE_API_KEY` is configured; without a key or when search is unavailable, JARVIS opens a
YouTube search URL instead. Provider failover means a prompt may reach more than one provider
during one request; do not describe prompts as device-only. Groq receives requests at
`api.groq.com`; Gemini requests go to Google's Generative Language API.

| Data | Stored | Sent to LLM |
|---|---|---|
| Conversation turns | local (short term) | last N turns each request |
| Plan summaries | local | titles and times only |
| Personal memories | local (Room) | at most 3 relevant entries per cloud request, masked; trace records the count |
| Contacts | local | names only; never numbers or emails |
| Favorite song | local (SharedPreferences) | not sent for recognised local commands; the title goes only to the chosen music app |
| Notification text | local, 48 h / 500 rows, user-clearable | only on an explicit, confirmed summarize request (≤ 20 sender names + snippets; numbers masked) |
| Email content | not persisted (counts local) | sender names, subjects, snippets only on a confirmed summary; addresses/numbers masked; drafts send the recipient name and the user's request only |
| Location | local | not sent; coordinates go to the routing provider (OSRM, or Google Routes / Mapbox if the user adds a key) / Nominatim / Open-Meteo |
| Spoken answers | not stored | not an LLM: with ElevenLabs chosen, the text of non-private answers goes to ElevenLabs; private answers (messages, memories, email) always use the phone voice |
| Wake-word audio | not stored | never sent; processed in memory on the phone |
| Request spoken after "Hey JARVIS" | shown in the conversation; never logged (logs keep its length only) | handled like a typed request; transcribed by Android's speech recognizer |
Statement for docs and UI: "Storage is local-first; the model receives the minimum context for the
current request." Never state that nothing leaves the device.

## 7. Memory (M5)
Room `memories` (v5): type (fact, relationship, preference, favorite), memoryKey, contentText, source, created/updated,
lastUsedAt. Retrieval is **lexical**, not embedding-based (decision 2026-10-18): stop-word-filtered token overlap with
a key boost, top-3, only memories with at least one shared word, only for cloud requests. The store holds tens of short,
user-written entries; an on-device embedding model (tens of MB plus a runtime) would add size and latency for little
gain at this scale. Trade-off: paraphrases with no shared word ("mentor" vs "guide") are missed. The unused
`embeddingVector` column is kept so a semantic index can be added later without another migration.

## 8. Evaluation
See docs/EVAL.md. Tools are stubbed during evaluation so the model's tool choice and arguments are
scored without side effects.

## 9. Risks and mitigations
| Risk | Mitigation |
|---|---|
| Free-tier rate limits / outages | Backoff, then OfflineRuleProvider fallback, labelled in UI |
| OAuth Testing mode token expiry | Re-sign-in flow; note in demo prep |
| OEM clock-app variance (Samsung) | Treat the handoff as success only when the alarm/timer exists; document |
| Notification listener killed by OEM battery management | Battery "unrestricted", status indicator in Permission Center |
| Autoplay not guaranteed in music apps | Claim playback only from an observed PLAYING media session; say "search opened" otherwise |
| Wake-word battery cost / false triggers | Opt-in; quiet gate + XNNPACK; 2 s cooldown; measured in docs/CAPABILITIES.md |

## 10. Stack (as built)
Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01, Material 3), Room 2.6.1 via KSP (schemas exported to
`app/schemas/`), WorkManager 2.10.0, OkHttp 4.12.0 for every HTTP API (Groq, Gemini, Gmail REST, YouTube Data,
Nominatim, OSRM, Open-Meteo), `org.json` for JSON, Google Play services auth 21.3.0 (Identity AuthorizationClient),
Android SpeechRecognizer / TextToSpeech, ONNX Runtime Android 1.22.0 (wake word), LocationManagerCompat. Dependencies are wired by hand in
`JarvisViewModel`; Hilt, Retrofit, and kotlinx.serialization were planned in v1 docs but are not used (no need at
this size; reconciled 2026-10-18). Tests: JUnit 4, OkHttp MockWebServer, Room MigrationTestHelper (instrumented).

## 11. Credentials
Keys live only in the git-ignored `local.properties` (or an environment variable of the same name) and reach the
app through `BuildConfig`: `GROQ_API_KEY`, `GEMINI_API_KEY`, `GEMINI_API_KEY_2`, `YOUTUBE_API_KEY`, and the optional
`ELEVENLABS_API_KEY`, `GOOGLE_ROUTES_API_KEY`, `MAPBOX_ACCESS_TOKEN` (read through `ApiCredentials`). Users never type
keys or voice IDs in the app; Settings offers a curated list of ElevenLabs voices by name, each confirmed on the
account with `GET /v1/voices/{id}` (metadata only, no speech credits). A key saved on the phone by the older Settings
screen (Keystore-encrypted) is still honoured, and the build's key wins. Any key compiled into an APK can be extracted
from it: fine for this personal build with restricted, revocable keys; a distributed build should call these services
through a server that holds the keys.
