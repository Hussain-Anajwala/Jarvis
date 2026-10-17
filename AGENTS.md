# AGENTS.md — JARVIS Android assistant (read first, every session, any coding agent)

## What this project is
A Kotlin / Jetpack Compose Android personal assistant. Direction (v2): an LLM
tool-calling agent (Gemini) orchestrates the phone's real capabilities through
a safety-gated tool layer. Existing agents (Calendar, Alarm, Task,
Communication, Plan Engine) are TOOLS. The old rule-based parser stays only as
the OFFLINE FALLBACK. Current work is defined in docs/ASSISTANT-ROADMAP.md.

## Read order (every session)
1. docs/PROJECT-STATE.md
2. docs/DECISION-LOG.md
3. docs/ASSISTANT-ROADMAP.md  (work ONLY on the current milestone)
4. docs/TRD.md and docs/SCHEMA.md when touching architecture or data

## Non-negotiable rules
1. Risk gating lives in CODE. Each Tool declares a static risk level; medium/high
   risk always stops at a confirmation dialog. Model output can never bypass it.
2. Validate every model function call against its JSON schema before executing.
   Malformed output = graceful retry or error, never a crash.
3. Secrets only in local.properties via BuildConfig. Never hardcode or commit keys.
4. Privacy: email/message CONTENT reaches the LLM only on an explicit user request
   (summarize/draft) and the UI says so. Counts are computed locally. Never put
   phone numbers, email addresses, or the contact list in prompts; resolve names
   locally. Never claim "nothing leaves the device".
5. Honesty: no UI text, docs, or telemetry the code does not back (no invented
   model names, stats, or statuses). Keep docs/CAPABILITIES.md accurate:
   Verified / Partial / Unsupported / Planned.
6. Room: never destructive-migrate. Write real migrations and bump the version.
7. Do not change UI theming unless the milestone says so.

## Verification protocol (two-stage: Built, then Verified)
- **Built:** implementation, unit tests, and `:app:assembleDebug` pass and the docs
  (CAPABILITIES, PROJECT-STATE, DECISION-LOG, docs/VERIFY-MN.md) are updated. Commit
  and tag `phase6-mN-built`; capabilities read "Built (unverified)".
- **Verified:** the docs/VERIFY-MN.md checklist and the regression checklist in
  docs/IMPLEMENTATION-PLAN.md pass on the physical device, driven through the
  app's own UI (real taps/text input; agent-driven UI input over adb counts only
  when labelled as such and recorded with screenshots/UI dumps). Shortcuts that
  bypass the UI (direct function calls, `am start` of internal code paths,
  simulated permission results) are never evidence. Only then tag
  `phase6-mN-verified` and mark capabilities Verified.
- Unit tests, a successful build, an app launch, or an API smoke call are never
  device verification. If verification fails, do not tag Verified; record the
  exact failure in DECISION-LOG.md.
- Human-only steps (real calls/messages to people, account sign-in, consent
  screens) stay open until the user performs them; never mark them passed.
- If blocked on something only the user can do (credentials, a Settings permission,
  unlocking the phone), STOP and say exactly what to do.

## Session end
Rewrite docs/PROJECT-STATE.md, append to docs/DECISION-LOG.md, update
docs/CAPABILITIES.md, commit.
