# JARVIS Assistant Roadmap (v2) — the single source of truth for what to build now

Context: the project review judged the build below final-year level because the
"AI" was a regex parser with no language model in the loop. We keep the body
(Plan Engine, risk gating, Decision Trace, real Calendar/Contacts/Clock
integration) and replace the brain with an LLM agent. Reference architecture:
OpenJarvis (Intelligence, Engine, Agents, Tools & Memory, Learning), scaled to Android.

Branch: `phase6-llm-assistant` (create from the current branch).
Tags (two-stage, see AGENTS.md): `phase6-mN-built` when tests, build, and docs pass;
`phase6-mN-verified` only after the device checklist passes with recorded evidence.

## How to run a milestone
"Read AGENTS.md and docs/ASSISTANT-ROADMAP.md. Execute milestone Mx. Pass tests and the
debug build, update docs and docs/verification/VERIFY-MX.md, commit, tag `phase6-mX-built`. Run the
device checklist and regression; tag `phase6-mX-verified` only on recorded passing evidence."

## Milestone overview
| M | Scope | Gate (what must be demonstrably true) |
|---|---|---|
| M0 | Setup, branch, baseline | Git state reported; branch created; keys in local.properties |
| M1 | LLM agent loop | Incomplete requests get a clarifying question; follow-ups resolve |
| M2 | Phone tools | Timer, alarm, YouTube, music, media keys, call, navigate, flashlight work by text/voice command |
| M3 | Notifications + eval harness | "Any new messages?" answered; baseline vs Gemini accuracy table exists |
| M4 | Gmail | Unread count, today's emails, summary, drafted email via Gmail handoff |
| M5 | Personal memory + real context | Remembers people/favorites; travel origin = real device location |
| M6 | Daily briefing + voice | Scheduled briefing; speech in/out; final eval numbers |

Cut line if behind schedule: drop M6 voice first, then M5 geocoding/weather. Never drop M1 or the eval harness.

## M0 — Baseline
- Report: branch, git status, latest tag. Were the earlier "ask for missing time/venue"
  parser fix and the WhatsApp message-prefill fix committed? (M1 supersedes the first;
  WhatsApp prefill must still be fixed: ACTION_SEND text/plain + EXTRA_TEXT + setPackage.)
- Commit these docs, create the branch.
- USER ACTION: Gemini key + Google Cloud setup (see chat checklist). Stop and ask if keys are missing.

## M1 — LLM agent loop
- `GeminiReasoningProvider` behind the existing `ReasoningProvider` interface, using Gemini
  function calling on a current Flash-class model (confirm the model name in the official
  docs; do not guess). The API identified `gemini-2.5-flash` as unavailable to new users and
  recommended `gemini-3.8-flash`; verify current free-tier eligibility separately. Handle
  HTTP 429/5xx with bounded backoff, then fall back.
- Tool registry per docs/TRD.md. Re-register existing agents as tools: create_meeting,
  cancel_plan, list_plans, set_alarm, create_reminder, create_task, draft_message.
- Loop: user message + last ~10 turns -> model returns function call or text -> run the tool
  through the Permission Layer -> feed result back -> repeat (max 5 iterations; clear failure
  message after).
- System prompt: never assume time, date, location, or recipient; ask ONE short question when
  required arguments are missing.
- OfflineRuleProvider (the old parser) is the automatic fallback with no network/key. The UI
  labels which engine answered. Decision Trace records the engine.
- Verify: "I have a meeting tomorrow" -> asks for time/venue, no plan created; answering creates
  the real calendar event + linked reminder; "cancel it" resolves the right plan and shows the
  confirmation dialog; airplane mode falls back cleanly; WhatsApp message text arrives prefilled.

## M2 — Phone tools
- set_timer (AlarmClock.ACTION_SET_TIMER); set_alarm (exists).
- play_youtube(query): YouTube Data API search -> open the video in the YouTube app; fall back
  to a YouTube search URL if no key or quota.
- play_music(query): MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH to the user's music app.
  "My favorite song" comes from the personal-facts store (simple key-value now, full memory in M5).
  Document honestly where autoplay is not guaranteed.
- media_control (play/pause/next/previous via media key events), set_volume, flashlight
  (CameraManager torch).
- call_contact(name): resolve locally; MEDIUM risk with confirmation; ACTION_CALL, request
  CALL_PHONE on first use.
- navigate(destination): Maps navigation intent.
- Verify each tool on device with a real command; record the real result in CAPABILITIES.md.

## M3 — Notifications + eval harness
- NotificationListenerService (user grants Notification access in Settings). Store sender, app,
  timestamp, short text locally with a retention limit and a "clear data" button.
- Tools: count_new_messages, list_new_messages(app?, contact?). Answered from local data; LLM
  summarization only when explicitly requested.
- Eval harness per docs/EVAL.md: >=100 labelled utterances, tools STUBBED during eval. Compare
  OfflineRuleProvider vs Gemini. Write the results table into docs/EVAL.md (measured values only).

## M4 — Gmail
- Google authorization via the CURRENT recommended Android API (not deprecated GoogleSignIn),
  scope gmail.readonly only.
- Tools: email_unread_count (no LLM), email_today (sender, subject, snippet),
  summarize_emails (opt-in, UI-labelled), compose_email(recipient_name, intent, tone?).
- compose_email: resolve the email address from Contacts locally; the LLM drafts subject + body
  using personal memory (name, sign-off, tone); show an EDITABLE draft; hand off via a mailto:
  intent so the user makes the final send tap. No send scope.
- Do not persist email bodies.

## M5 — Personal memory + real context
- Memory per docs/SCHEMA.md: facts, relationships, preferences, favorites; embedding-based
  retrieval; visible list with edit/delete. Only relevant memories are sent per request.
- Device location + geocoding (Nominatim) + OSRM replace the fixed demo route; Open-Meteo
  weather. Label live vs estimated.
- Derive habits and buffer times from real Decision Trace history; label any seeded data as demo.

## M6 — Briefing + voice
- Scheduled morning briefing (WorkManager): calendar, weather, travel, unread email count,
  new-message count, tasks. Content flags opt-in.
- Voice in (SpeechRecognizer, push-to-talk) and out (TextToSpeech).
- Re-run the eval harness; update docs/EVAL.md with final numbers.
