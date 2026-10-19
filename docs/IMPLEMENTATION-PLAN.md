# Implementation Plan — v2.0
Supersedes v1.0. Window: about 7 days. Detailed scope per milestone: docs/ASSISTANT-ROADMAP.md.

## Schedule
| Day | Milestone | Output |
|---|---|---|
| 0 | M0 baseline, branch, keys | Branch `phase6-llm-assistant`, keys in local.properties |
| 1 | M1 LLM agent loop | Clarifying questions, follow-ups, engine label |
| 2 | M2 phone tools | Timer, YouTube, music, media, call, navigate, flashlight |
| 3 | M3 notifications + eval harness | Message counts; baseline vs Gemini table |
| 4 | M4 Gmail | Unread count, summaries, drafted email |
| 5 | M5 memory + real context | Remembered people/favorites; real travel origin |
| 6 | M6 briefing + voice | Morning briefing, speech in/out, final eval |
| 7 | Hardening and docs | CAPABILITIES.md final, demo script, stable tag |
If behind: drop M6 voice, then M5 geocoding/weather. Never drop M1 or the eval harness.

## Definition of done (per milestone, two stages — see AGENTS.md)
Built:
1. Unit tests and `:app:assembleDebug` pass; docs/verification/VERIFY-MN.md written.
2. docs/CAPABILITIES.md (Built (unverified)), PROJECT-STATE.md, DECISION-LOG.md updated.
3. Committed and tagged `phase6-mN-built`.
Verified:
4. docs/verification/VERIFY-MN.md checks pass on the physical device with recorded evidence.
5. Regression checklist below passes.
6. CAPABILITIES.md promoted to Verified; tagged `phase6-mN-verified`.
Pushing to the remote requires the user's explicit permission.

## Regression checklist (run after every milestone)
R1 Create meeting -> event visible in the real Calendar + linked reminder.
R2 Cancel from Home -> cascade, calendar event removed.
R3 Cancel from the Plans tab -> cascade.
R4 Alarm request -> alarm exists in the Clock app.
R5 Message draft -> edit -> handoff opens Messages and WhatsApp with the text prefilled; nothing auto-sends.
R6 Ambiguous contact and unknown contact show clear dialogs.
R7 Automations approve flow.
R8 Travel re-check on the same plan.
R9 Airplane mode -> offline fallback, labelled, no crash.

## Working agreement across agents
Start every session from AGENTS.md. One milestone at a time. Failed verification is logged, not tagged.
Docs describe evidenced reality: CAPABILITIES.md may say Built (unverified) after a built tag; it says Verified only after device verification.

## User-only tasks (agent must stop and ask)
API keys and Google Cloud setup, granting Notification access, Google sign-in, unlocking the phone, installing/logging into Spotify or a music app.
