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

### 6.1 Privacy policy (Phase 4, 2026-10-21)
Every model call in `JarvisCore` goes through `PrivacyGuardProvider`, which runs `PrivacyPolicy` once **before** the
provider chain, so before Groq, its retries, Gemini, and their fallbacks; the chain only ever sees the approved
request. The rules are deterministic (no model, no new dependency):

| Category | Example | Decision |
|---|---|---|
| Secret | "my PIN is …", "OTP 552…", password/API key, Luhn-valid card, Aadhaar, PAN | In the user's own request: **local only** (the router keeps it on the phone; nothing is sent). In confirmed summaries or dictated emails: **only the value is redacted** ("[redacted] is your OTP"). In history or memories: **dropped**. Never stored as a memory, never written to Activity, never read by ElevenLabs. |
| Private content | message notifications, email snippets | Cloud only for a summary the user confirmed (purpose + consent on the request); otherwise local only. Phone numbers (7+ digits) and email addresses are masked first. |
| Contact / calendar | names in "call Asha", meeting titles | Allowed (needed for the task); numbers and addresses stay on the phone. |
| Preference | saved memories | Up to 3 relevant ones per cloud request, secrets excluded. |
| General | everything else | Allowed, unless **Settings → Privacy → Use cloud AI** is off, which keeps every request on the phone. |

A label alone ("what is an OTP?", "pin this note", "the password is wrong") is not a secret, and ordinary numbers
(times, dates, flight numbers, prices) are never redacted. If anything outbound still looks secret after
minimising, nothing is sent (fail closed). Each decision is recorded in Activity ("Privacy decision") with the
kind of data and the rule, never the value; all JarvisCore trace text is redacted on write, the in-memory
conversation keeps a redacted copy of what the user said, and evaluation results store redacted replies.
Storage (Phase 4.1): `SafeStorage` applies the same redaction to every stored copy of user text — all trace rows
(`TraceDao.insertRedacted`, used by JarvisCore, PlanEngine, BackgroundEngine, and the briefing; a source test fails on
any direct `traceDao().insert`), plan and reminder titles, calendar event titles and places (the calendar app may sync
them to the cloud), task titles, captured notification snippets (an OTP in a bank message is stored as
"[redacted] is your OTP"), memories (including edits), and Phase 5 task objectives, inputs, and results.
Limits: detection is pattern-based (labelled values, card/Aadhaar/PAN/key formats), so an unlabelled secret
("door 4821") is not recognised and is stored as written; Android log lines carry status codes and provider error
text, not request content.

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
| Spoken answers | not stored | not an LLM: with ElevenLabs chosen, answer text goes to ElevenLabs; message/email answers use the phone voice unless Settings allows otherwise; an answer containing a secret always uses the phone voice |
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

## 12. On-device language model feasibility (Phase 3, 2026-10-21)
**Question.** Can JARVIS run a real language model on the phone, so that open requests still work with no network or
with cloud AI turned off? (Today that case falls back to `OfflineRuleProvider`, a rule parser — not a model.)

**Target device (read with adb, no changes made).** Galaxy A26 5G (SM-A266B), Exynos 1380 (s5e8835): 4× Cortex-A78
2.4 GHz + 4× A55 2.0 GHz, Mali GPU with OpenCL, Samsung ENN NPU libraries (vendor-only, no public app API), 7.3 GB RAM
with ~2.7 GB available at the time of reading, Android 16 (API 36), 114 GB free. JARVIS debug APK: 55.8 MB.

| Option | Android/Kotlin | Model, size | Hardware | Licence | Verdict |
|---|---|---|---|---|---|
| **LiteRT-LM** (Google; successor of the MediaPipe LLM Inference API, which is now maintenance-only) | Maven package, Kotlin API, official Android sample | Gemma 3 1B-IT int4 `.litertlm`, ≈ 0.55 GB (approximate; check the official repo) | CPU, GPU (OpenCL) | Runtime Apache-2.0; Gemma under the Gemma Terms of Use (redistribution allowed with its use restrictions passed on) | **Recommended** |
| **llama.cpp** via JNI | NDK/CMake build inside Gradle; community Kotlin wrappers | Qwen2.5-0.5B/1.5B-Instruct GGUF Q4_K_M ≈ 0.4 / 1.0 GB | CPU (NEON, Arm KleidiAI kernels); OpenCL backend has known problems with Qwen2.5-0.5B | MIT; Qwen2.5 0.5B/1.5B Apache-2.0 | **Fallback** (most permissive licences, more build work) |
| ONNX Runtime GenAI | AAR; JARVIS already ships ONNX Runtime 1.22 for the wake word | Qwen2.5-0.5B / Phi-3.5-mini int4 (Phi ≈ 2 GB, too large) | CPU; NNAPI is deprecated on new Android | MIT | Possible, larger runtime, weaker mobile LLM tooling |
| Gemini Nano (ML Kit GenAI / AICore) | Simple API | System-provided | Only on supported flagship devices (Pixel 10 is the documented best case) | Google terms | **Not feasible** on the A26 (no evidence of support; check at runtime with `getBaseModelName()`) |

**Expected performance (estimates, not measured on this phone).** A 0.5–1B int4 model on 4× A78 cores decodes on
the order of 10–30 tokens/s (one published 0.5B int4 llama.cpp figure is 32 tokens/s on an older flagship CPU).
Prefill dominates: the full cloud prompt plus tool schema (several thousand tokens) would take many seconds, so a
local model can only be used with a **compact prompt** (≈ 12 short-argument tools, < 1,200 characters) and a ≈ 64-token
JSON answer — roughly 1–4 s per request after a model load of a few seconds. RAM: ≈ 0.6–1.2 GB while loaded, so the
model must be loaded on demand and released after idle, never kept resident alongside the wake-word service.
Battery: sustained CPU at full load during each request; acceptable for occasional offline requests, not for always-on
use. The wake-word service is unaffected unless both run at once (the half-duplex design already serialises them).

**Recommendation.** If a local model is added, use LiteRT-LM with Gemma 3 1B-IT int4 as an **opt-in download** (Wi-Fi,
≈ 0.55 GB, stored in app files, not in the APK), loaded only when the router would otherwise fall back to the rule
parser (cloud AI off, offline, or cloud cooling down) and the rule parser found nothing. Fallback: llama.cpp with
Qwen2.5-0.5B-Instruct Q4 if the Gemma terms are unacceptable. Failure handling: a missing file, a load error, or an
out-of-memory error falls back to the rule parser with the reason in Activity.

**What is built.** Only the seam: `LocalTextModel` (interface) and `LocalModelReasoningProvider` (compact prompt, strict
JSON parsing, the same schema validation as cloud output, failures reported), tested with a scripted fake
(`LocalModelProviderTest`). **No runtime, no model, no dependency was added, and no on-device neural inference has been
run or measured.** Next step, if approved: add the LiteRT-LM dependency behind the opt-in download, measure load time,
latency, peak RAM, and battery on this phone, and evaluate it on the labelled set before wiring it into the router.

Sources: [LiteRT-LM for Android](https://ai.google.dev/edge/litert-lm/android), [MediaPipe LLM Inference guide (maintenance-only notice)](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference),
[Gemma 3 1B-IT LiteRT models](https://huggingface.co/litert-community/Gemma3-1B-IT), [ML Kit GenAI overview](https://developers.google.com/ml-kit/genai),
[ML Kit Prompt API post](https://developer.android.com/blog/posts/ml-kit-s-prompt-api-unlock-custom-on-device-gemini-nano-experiences),
[on-device latency dataset (unverified provenance)](https://huggingface.co/datasets/dispatchAI/on-device-latency),
[llama.cpp OpenCL notes](https://huggingface.co/spaces/Steven10429/apply_lora_and_quantize/blame/999165ea0520b95c7138d1319941f2402f8d5b51/llama.cpp/docs/backend/OPENCL.md).

## 13. Durable reminders and goals (Phase 5, 2026-10-21)
**Data (Room v6, additive migration 5→6).** `agent_tasks` (id, kind reminder|goal_step, objective, inputsJson,
scheduledAt epoch ms, timeZone, status, attempts/maxAttempts, timeoutMs, result, originRequest, approval, goalId,
createdAt/updatedAt; indexed by status and goalId) and `goals` (id, title, status active|paused|completed|cancelled,
dueAt, originRequest). The migration SQL is copied from the exported schema (`app/schemas/.../6.json`); no existing
table or row is changed. Instrumented tests prove a v5 database with plans, reminders, and memories keeps all of
them, no plan-less reminder reappears, and both the upgraded and a fresh database run the task queries.

**States.** pending → scheduled → running → completed; also failed, cancelled, paused, missed. Only the transitions in
`TaskStates.TRANSITIONS` happen, each as a conditional SQL update (`… WHERE id = ? AND status IN (…)`), which makes
delivery idempotent: a second alarm, a recovery pass, or a restart finds the task no longer "scheduled" and does
nothing. A task is **scheduled only after it is stored and its alarm is set**; if Android refuses the alarm it is
stored as failed and the user is told.

**Timing.** Exact `AlarmManager.setExactAndAllowWhileIdle` alarms (allowed by `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`
up to API 32), one PendingIntent per task (task id in the intent data, so re-arming replaces rather than duplicates).
WorkManager was not used for firing: it may defer one-time work by many minutes under Doze, which is wrong for "in 30
minutes". If exact alarms are not allowed, an inexact alarm is used and the reply says it may be late. In deep Doze
Android still rate-limits exact alarms to about one per ~9 minutes per app. Times are stored as absolute instants with
the zone the user meant.

**Delivery and failure.** Due → running → a notification with a stable id per task (re-posting replaces, never
duplicates; goal steps get a **Done** action) → completed ("delivered"). Notifications off → failed with that reason
(and the creation reply already warned). Android refusing the post → retried after 1 minute, at most 3 attempts, then
failed. "Delivered" is never "done": a goal step counts as done only when the user taps Done.

**Recovery.** On app start and on `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, and `TIMEZONE_CHANGED`: future
tasks are re-armed; tasks due within the last 10 minutes are delivered late; older ones are marked **missed** and the
user gets one "Missed reminder" notification; tasks interrupted mid-delivery (running longer than their timeout) are
retried; tasks stored but never armed are armed or marked missed.

**Conversation.** `ReminderDialogue` asks only for what is missing (what, when, AM or PM); "list my reminders", "cancel
the reminder I just created" (or by words, or a pick list), "pause/resume my … plan". `GoalDialogue` ("help me
prepare for my exam next Sunday") asks for the date, topics, and a daily time, proposes at most 14 sessions (one a
day until the day before), accepts "another time", and schedules **nothing until the user says yes**. All handled on
the phone (router rule 2). The Plans tab lists reminders (Cancel) and goals (progress, Done, Pause, Resume, Cancel).

**Safety.** Only notification tasks exist; `TaskEngine.create` refuses any other kind unless `approval = approved`,
so a persistent task can never send, call, or email on its own. Objectives, inputs, and results pass through
`SafeStorage`.

**Limits.** Delivery (an alarm actually ringing and the notification appearing), reboot recovery, and time-zone
changes are unit-tested with fakes but not yet observed on the phone. A reboot or force-stop clears alarms until
the receiver or the app runs again. Rescheduling an existing reminder is not supported (cancel and create a new one).

## 14. Plan → approve → execute → verify (Phase 6, 2026-10-21)
**Data (Room v7, additive 6→7).** `plan_executions` (id, sanitized objective, status, stepsHash, approvalHash, approvedAt,
summary, failureReason, timestamps) and `plan_steps` (stable id `<execution>#<n>`, position, tool, argsJson, postcondition,
requiresApproval, retrySafe, status, attempts, effectRef, result, evidence, error, startedAt). Free-text arguments are
sanitized; structured ones (epoch times, durations, zone ids) are stored exactly — a 13-digit epoch can pass the
card-number Luhn check, which corrupted times before this was fixed.

**Lifecycle.** Execution: PROPOSED / WAITING_APPROVAL → RUNNING → (per step VERIFYING) → SUCCEEDED | FAILED |
NEEDS_ATTENTION | CANCELLED. Step: PENDING → RUNNING → VERIFYING → SUCCEEDED | UNVERIFIED | FAILED | NEEDS_ATTENTION,
or SKIPPED / CANCELLED. SUCCEEDED means every step's postcondition was observed; any UNVERIFIED step makes the run
NEEDS_ATTENTION ("done, but I couldn't confirm it"); a failed step stops the run and the report says "Partly done".

**Rules (in `PlanExecutor`, not in any prompt).** Validation before storage (known tool, tool-specific argument
checks, ≤ 8 steps, no repeated identical step). A multi-step plan or any approval-required step needs approval; the
approval is bound to the hash of the exact steps shown, expires after 30 minutes, and is re-checked before every step
(a changed plan or an expired approval returns to WAITING_APPROVAL). Each step is claimed — persisted as RUNNING with
its attempt counted — **before** acting; the tool's own "done" is not success, `verify` decides. Retries only for
retry-safe (idempotent) steps, at most 3 attempts, 10 s timeout per call; a timeout is "uncertain". An uncertain
non-idempotent step is never repeated: NEEDS_ATTENTION. Cancel stops future steps and undoes scheduled work created by
finished steps; reject and revoke stop without undoing.

**Recovery.** On app start, runs left RUNNING are reconciled step by step: a recorded or findable effect (reminders use
the step id as their task id) is verified instead of repeated; a retry-safe step is re-run; a non-idempotent step
(timer) becomes NEEDS_ATTENTION. The rest continues only while the approval is still valid; otherwise it waits.

**Tools.** `reminder.schedule` (idempotent; verified by the stored task being scheduled at the requested time *and*
its alarm being set), `timer.start` (not retry-safe; verified by the timer record running with the right length and its
alarm set), `reminders.cancel_all` (removes user data → always needs approval; verified by no pending reminders or
alarms remaining). No messaging, calling, email, purchase, or account tool exists in the plan registry.

**Parsing.** `PlanParser` turns "remind me … and start a 25 minute timer, then …" into steps on the phone (no model);
it applies only when every clause is a supported action and asks for a missing reminder time before planning.

**Verification limits.** Verification checks JARVIS's own records and Android's alarm registry; it does not prove the
notification will be seen later (that is Phase 5's delivery path), and a force-stop clears alarms until JARVIS runs
again (recovery re-arms them on launch).

## 15. Research dashboard and export (Phase 7, 2026-10-21)
**Navigation.** Settings → "Research dashboard" opens a full-screen Research view (Back or Close returns). It works
offline and never calls a model.

**Data flow (three separate layers).**
1. *Evidence* — `app/src/main/assets/research/evidence.json` (schema `jarvis.research.evidence` v1), generated by
   `ResearchEvidenceTest` from the repository's artifacts: routing metrics **computed** from the per-item JSONL results in
   `eval/results` with the same scorer as the eval reports; plan–execute–verify metrics **computed** from
   `eval/results/pev__2026-10-21.jsonl`; and hand-written entries (test runs, phone observations, estimates, Gemini
   not run, PEV timing) from `eval/research/static_evidence.json`, each with source and date. The test fails if the
   committed asset no longer matches the artifacts. Regenerate with `JARVIS_EVIDENCE_WRITE=1 gradlew.bat
   testDebugUnitTest --tests com.jarvis.eval.ResearchEvidenceTest`.
2. *Model and loading* — `ResearchEvidence.kt`: `Evidence`/`Experiment`/`ResearchMetric`, `SourceType`
   (RECORDED_BENCHMARK, REPLAY, SIMULATED, TEST_RUN, DEVICE_OBSERVATION, ESTIMATE, HISTORICAL, ON_DEVICE_RECORD), and a
   strict `EvidenceParser` (unknown schema/version/source type/category/status, missing fields, a percent that
   disagrees with its numerator/denominator, or duplicate ids → `EvidenceFormatException`; an unmeasured value must be
   an explicit null). `DeviceRecords` counts this phone's plan-run and plan-step statuses and privacy/routing decisions
   by fixed category from the trace outcome prefix; unrecognised text becomes "other" and is never copied.
3. *UI* — `ResearchScreen.kt`: sections (routing, plan–execute–verify, privacy and safety, tests, phone checks,
   feasibility), one card per experiment with source-type, date, sample size, and status chips, every rate shown with
   its numerator/denominator, a bar comparison only for the runs on the same 134 items, this phone's counts, and export.

**Export (`ResearchExport`).** Export CSV / Export JSON open Android's "save as" screen (`ACTION_CREATE_DOCUMENT`); the
file goes only where the user chooses, UTF-8, no storage permission, nothing in the background; the screen states
what the file contains. The builder reads only the evidence model and `DeviceRecords` (a test scans its source for
DAO/entity references). Allow-listed fields:
- JSON (`jarvis.research.export` v1): `schema, schema_version, generated_at, app_version, evidence_schema_version,
  evidence_commit, evidence_generated_on, contents, source_types[], experiments[], device_records|null`; each experiment
  `id, title, description, category, source_type, source_type_label, sources[], run_date, sample_size, status, notes,
  metrics[]`; each metric `id, label, value|null, unit, numerator, denominator, denominator_label, definition, text`;
  `device_records`: `source_type, since (epoch ms), plan_runs_by_status, plan_steps_by_status, privacy_decisions,
  routing_targets` (category → count).
- CSV: header `schema_version, generated_at, evidence_commit, experiment_id, category, source_type, run_date,
  sample_size, status, metric_id, metric_label, value, unit, numerator, denominator, denominator_label, definition, text,
  sources`; one row per metric plus one per device count (`experiment_id = device_records`). RFC 4180 quoting; CRLF.
- Text fields pass `SafeStorage` and, in CSV, a formula guard (a leading `= + - @`, tab, or CR gets an apostrophe unless
  the cell is a number). Numbers are never run through text redaction (a 13-digit epoch can look like a card number).
  Unmeasured values are JSON null / empty CSV cells, never 0.
- Never exported: Activity text, requests, messages, emails, contacts, memories, reminder or plan text, plan arguments,
  keys, tokens, secrets.

**Validation.** `python eval/research/validate_export.py <file.json> [<file.csv>]` (standard library only) checks the
schema, allow-listed fields, value types, percent consistency, CSV shape and formula guarding, and that every value equals
the bundled evidence.

**Not measurable yet.** Live routing accuracy on the phone (requests are unlabelled), Gemini (no quota), on-device model
latency/RAM/battery (no model installed), real reminder delivery rate, and wake-word accuracy with real speech.

## 16. Contextual memory, tool registry, events, Hinglish, efficiency (Phases 8–9, 2026-10-21)
**Memory retrieval (8A).** `MemoryRetriever.lookup`: deterministic scoring over the saved memories (no vector store):
each query word shared with a memory counts ln(1 + N/df) — rarer words count more — and double when it matches the
memory's key; "who" questions prefer relationship memories; results under half the best score are dropped; secrets are
never candidates. Two memories with the same key and different values are a **Conflict**: JARVIS lists them and asks
which is right (`MemoryReplies.conflict`). A few Hinglish words map onto the same tokens (behen → sister, naam → name).
The same lookup decides which memories a cloud request may carry (none on a conflict). `ContextAssembler.goalLines` adds
an *active* goal's title only when the request shares a meaningful word with it. The memories table is read on every
lookup, so edits and deletions apply immediately; legacy rows (type fact, no key, updatedAt 0) are scored like any other.
No schema change.

**Typed tool registry (8B).** `ToolRegistry` lists every model tool (from `ReasoningToolSchema`) and every plan step
(`PlanTools`) with: typed arguments (text, integer range, boolean, enum, ISO date, epoch ms, zone), permissions, approval
(`NONE`, `CONFIRM_ON_SCREEN`, `PLAN_APPROVAL`), side effect (`NONE`, `LOCAL`, `HANDOFF`, `ACCOUNT`, `COMMUNICATION`),
idempotency, verification method, and known limitations. `JarvisCore` runs `ToolRegistry.check` on every proposed call
before `ToolRouter`; an unknown tool or a badly typed argument is rejected with a reason and takes the existing
"couldn't safely process" path. The approval flows themselves are unchanged; tests assert the metadata agrees with them
(every `COMMUNICATION` tool needs `CONFIRM_ON_SCREEN`; every medium/high-risk tool needs a confirmation or has no side
effect; plan-step flags equal the PlanTool classes) and that every correct tool call recorded in the 2026-10-21 runs
passes the check. No arbitrary code or shell tool exists.

**Observed events (8B, Room v8, additive 7→8).** `app_events(id, type, subjectId, status, createdAt, handledAt, outcome)`.
Types: `plan.finished`, `task.failed`, `task.missed`, `goal.completed`, emitted by `PlanExecutor` (final state) and
`TaskEngine` (failed delivery, missed after restart, all goal steps done) — never by a model. The id is
`type:subject:status`, so a repeated delivery is a no-op; the handler runs once and marks the row handled; rows a crash
left unhandled are handled on the next app start. Handlers receive only `EventEffects` (a local notification under a
stable key, or an Activity record) — no tools, network, messaging, or event emission, so no loops or external side
effects — and re-read current state (a cancelled plan or goal gets no reaction). Unknown types and payloads that are not
a plain id/status are rejected and not stored. `task.missed` only records, because the reminder engine already posted
one missed-reminder notification.

**Hinglish and conversational answers (9A).** On-device rules (no model): "… ki yaad dilana / yaad dila do" starts a
reminder; "N baje" / "N:MM baje" is a clock time (subah = morning; shaam, raat, dopahar = afternoon/evening; otherwise
JARVIS asks AM or PM); kal / aaj / parson are days; reversed word order for cancel/list/pause/resume ("reminder cancel
karo", "plan ko pause kar do", "reminders dikhao"); "N minute ka timer". Yes/no (`ApprovalAnswer`, incl. haan, ji, theek
hai, nahi, mat) is read only against a waiting proposal; a bare yes/no with nothing pending approves nothing ("Nothing
is waiting for a yes or no"). During plan approval a time correction ("no, make it 8 pm", "nahi, raat 9 baje") changes
the plan's single reminder and shows the plan again (`PlanCorrection`); without AM/PM JARVIS asks; it never adds a
second action. `OnDeviceIntents.classify` mirrors the app's on-device order and is what the intent evaluation runs.

**Efficiency (9B).** Measured, not assumed: see docs/EVAL.md. The repeated parser checks cost microseconds, so no cache
was added; memory lookup is linear in the number of memories (≈ 3 ms for 514 on the PC). Phone end-to-end latency was not
measured.

