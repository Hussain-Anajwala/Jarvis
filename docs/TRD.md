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

## 4. Tool contract
```kotlin
interface Tool {
  val name: String
  val description: String            // shown to the model
  val parametersSchema: JsonObject
  val risk: RiskLevel                // LOW | MEDIUM | HIGH, static, never model-provided
  val requiredPermissions: List<String>
  suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}
sealed interface ToolResult {
  data class Success(val data: JsonObject, val userMessage: String) : ToolResult
  data class NeedsConfirmation(val summary: String, val onConfirm: suspend () -> ToolResult) : ToolResult
  data class NeedsPermission(val permission: String) : ToolResult
  data class Failure(val reason: String, val recoverable: Boolean) : ToolResult
}
```
Adding a capability = adding one Tool and registering it.

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
| Location | Fused location | ACCESS_FINE/COARSE_LOCATION | low |
| Voice | SpeechRecognizer / TextToSpeech | RECORD_AUDIO | low |

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
| Personal memories | local | only top-k relevant per request |
| Contacts | local | names only; never numbers or emails |
| Favorite song | local (SharedPreferences) | not sent for recognised local commands; the title goes only to the chosen music app |
| Notification text | local, 48 h / 500 rows, user-clearable | only on an explicit, confirmed summarize request (≤ 20 sender names + snippets; numbers masked) |
| Email content | not persisted (counts local) | sender names, subjects, snippets only on a confirmed summary; addresses/numbers masked; drafts send the recipient name and the user's request only |
| Location | local | not sent; coordinates go to OSRM / Nominatim / Open-Meteo |
Statement for docs and UI: "Storage is local-first; the model receives the minimum context for the
current request." Never state that nothing leaves the device.

## 7. Memory (M5)
Room table with embedding vectors; retrieval by cosine similarity, top-k. Embedding model chosen in
M5 (candidates: on-device text embedder such as MediaPipe or an ONNX MiniLM-class model); log the
choice in DECISION-LOG.md.

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
| Autoplay not guaranteed in music apps | Document in CAPABILITIES.md |

## 10. Stack
Kotlin, Jetpack Compose (Material 3), Hilt, Room, WorkManager, Retrofit/OkHttp, kotlinx.serialization,
Groq OpenAI-compatible API, Gemini API, YouTube Data API v3, Gmail API, OSRM, Nominatim, Open-Meteo,
Android SpeechRecognizer / TTS.
