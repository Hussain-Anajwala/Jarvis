# JARVIS Agent Handoff

> Historical audit snapshot (2026-10-17, before M2 completion). For current state see docs/PROJECT-STATE.md.

## 1. Date and purpose

**Audit date:** 2026-10-17 (Asia/Calcutta)

This is an evidence-based handoff for continuing the local Android repository
after Copilot's M2 session ended. The local checkout is the source of truth;
the GitHub remote may be older. This file records observed repository facts,
repository-authored prior reports, and items still unknown. It does not promote
any milestone to device-verified.

## 2. Repository and Git state

- Path: `C:\Users\husai\Desktop\jarvis`
- Branch: `phase6-llm-assistant`
- HEAD: `da46709` — `Add resilient LLM provider failover`
- HEAD is tagged `phase6-m1-built`.
- `git tag --list "phase6-*"` currently returns only `phase6-m1-built`; no
  `phase6-m1-verified` or M2 tag exists.
- Recent relevant history: `da46709` (M1b failover), `9ccd1f9` (Gemini
  fallback/M1 interaction hardening), `52e2932` (WIP M1 Gemini provider), and
  `d860e4d` (v2 docs/instructions). `origin/main` points at older commit
  `048c3c6`; do not use it as the local implementation baseline.

### Worktree observed at audit

There is no staged diff. `git diff --stat` reports 12 modified tracked files,
371 insertions and 77 deletions. `git diff --cached --stat` is empty. `git diff
--check` reports no whitespace errors, only LF/CRLF conversion warnings for
three Markdown files.

Modified, unstaged tracked files:

- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/jarvis/MainActivity.kt`
- `app/src/main/java/com/jarvis/core/JarvisCore.kt`
- `app/src/main/java/com/jarvis/core/PermissionLayer.kt`
- `app/src/main/java/com/jarvis/core/ProviderChain.kt`
- `app/src/main/java/com/jarvis/core/ReasoningProvider.kt`
- `app/src/main/java/com/jarvis/ui/JarvisViewModel.kt`
- `docs/CAPABILITIES.md`
- `docs/DECISION-LOG.md`
- `docs/PROJECT-STATE.md`
- `docs/TRD.md`

Untracked files:

- `app/src/main/java/com/jarvis/core/AndroidPhoneToolRuntime.kt`
- `app/src/main/java/com/jarvis/core/PhoneCommandParser.kt`
- `app/src/main/java/com/jarvis/core/PhoneToolActions.kt`
- `app/src/test/java/com/jarvis/core/PhoneToolsTest.kt`
- `docs/VERIFY-M2.md`

This handoff is the only file created by this audit. Existing Copilot changes
above must be preserved and reviewed before staging or committing.

## 3. Project architecture and key locations

- Kotlin/Jetpack Compose Android app, application ID `com.jarvis`, module
  `app`; compile/target SDK 35 and min SDK 26 are configured in
  `app/build.gradle.kts`.
- UI and permission entry points: `MainActivity.kt`, `JarvisViewModel.kt`,
  `JarvisTheme.kt`, and `PermissionLayer.kt`.
- Core orchestration: `JarvisCore.kt`, `Protocol.kt`, `ReasoningProvider.kt`,
  `ProviderChain.kt`, agent implementations under `agents/`, and plan/travel
  code in `PlanEngine.kt` and `TravelContext.kt`.
- Persistence: `AppDatabase.kt`, `Daos.kt`, and `Entities.kt`; no M2 Room
  migration or schema change is present.
- M2 phone implementation is in the five untracked/modified files listed
  above. `AndroidPhoneToolRuntime` receives `BuildConfig.YOUTUBE_API_KEY`.
- Existing Android integration is declared in
  `app/src/main/AndroidManifest.xml`. M2 adds Camera and Phone permissions and
  runtime handling; it does not add a new dependency.
- Unit tests are under `app/src/test/java/com/jarvis/core/`. The new M2 test
  class is `PhoneToolsTest.kt`; there is no checked-in test-results report.

## 4. Canonical documentation reading order

Follow `AGENTS.md`: `docs/PROJECT-STATE.md`, `docs/DECISION-LOG.md`,
`docs/ASSISTANT-ROADMAP.md`, then `docs/TRD.md` and `docs/SCHEMA.md` when
architecture/data are touched. `CLAUDE.md` repeats the first three reads.
For milestone work also read `docs/IMPLEMENTATION-PLAN.md`, the applicable
`docs/VERIFY-MN.md`, and `docs/CAPABILITIES.md`; use `docs/EVAL.md` for M3/M6
evaluation. The full authored Markdown in the repository root and `docs/` was
read for this audit, including `APP-FLOW.md`, `PRD.md`, `UI-UX.md`, and
`CROSS-QUESTIONING.md`. `.github/` exists but contains no files in this
checkout. `local.properties` exists; its values were deliberately not read.

## 5. M1/M1b: observed versus historical

### Observed locally

- The checked-out M1b commit is `da46709`, and the `phase6-m1-built` tag points
  to HEAD.
- The provider chain code and shared tool-schema code are present. The documented
  runtime order is Groq, Gemini key 1, Gemini key 2, then the offline parser.
- Gemini quota/cooldown code and provider/model/status trace fields are present
  in the source. The offline parser remains the fallback.
- M1 behavior is not currently device-verified after this baseline. There is no
  `phase6-m1-verified` tag.

### Previously reported by repository documents, not independently re-run here

`docs/PROJECT-STATE.md` and `docs/DECISION-LOG.md` report 26 unit tests and a
successful `:app:assembleDebug` for M1b. They report sanitized smoke statuses:
Groq HTTP 403, Gemini key 1 HTTP 200, and Gemini key 2 HTTP 503. These are
historical reports, not new audit evidence; no keys or response contents were
read. They do not prove in-app failover or physical-device behavior. The Groq
credential/access issue and Gemini key 2 HTTP 503 remain unresolved in the
documents.

`docs/VERIFY-M1.md` is the required real-tap guide. It requires provider status,
clarifying/follow-up meeting flow, cancellation, airplane-mode fallback,
language coverage, and R1–R9 regression checks. No new user-reported result for
that guide is present in this checkout.

## 6. M2 exact status

### Observed implementation

The unstaged changes register and route `set_timer`, `set_alarm`,
`play_youtube`, `play_music`, `media_control`, `set_volume`, `flashlight`,
`call_contact`, and `navigate`. `JarvisCore` validates the tool name and
arguments before dispatch. `ReasoningToolDefinition` carries static risk and
required-permission metadata; medium/high risk is gated in code, and
`call_contact` is medium risk. The OpenAI-compatible and Gemini JSON schemas are
generated from the same definitions, with required fields and extra arguments
rejected.

Specific implementation present:

- Timer and alarm use Android Clock intents. Alarm handoff is the existing
  special UI path; timer is new M2 dispatch.
- YouTube uses the Data API only when `YOUTUBE_API_KEY` is locally configured;
  no key, quota/access failure, network failure, invalid result, or unavailable
  app falls back to a YouTube search/video web handoff.
- Music uses `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`; autoplay is not
  guaranteed. Favorite-song lookup is not implemented and belongs to M5.
- Media play/pause/next/previous dispatches Android media key events; volume
  accepts 0–100%; flashlight uses `CameraManager.setTorchMode`.
- Calling resolves names through the local Contacts Provider, stops on unknown
  or ambiguous results, shows the resolved contact for explicit confirmation,
  then requests `CALL_PHONE` only as needed before `ACTION_CALL`. Contact names
  and numbers are not sent to the LLM.
- Navigation uses a `google.navigation` intent. Missing app handlers and
  hardware/permission failures return recoverable messages.
- Recognized phone commands are parsed locally before provider selection. This
  means those commands do not require a live LLM, while other requests still use
  the provider chain.

### M2 evidence and limits

The repository-authored M2 state/decision entries report `:app:testDebugUnitTest`
and `:app:assembleDebug` passing with 35 tests total, including nine new M2
tests. The new `PhoneToolsTest.kt` visibly covers registration, risk/permission
metadata, argument validation, local parsing, intent construction, contact
safety decisions, recoverable hardware failures, YouTube fallback/API policy,
and malformed request decoding. This audit did not run those commands and no
test report is checked in, so the reported results are **previously reported,
not independently confirmed here**.

`docs/VERIFY-M2.md` is present but untracked. It defines M2-1 through M2-10 and
R1–R9 real-device checks. No physical-device result is recorded. Therefore every
M2 feature is **Built (unverified)**, not Verified. Known user/device
dependencies are an unlocked phone, installed Clock/Maps/YouTube or browser,
an installed/sign-in music app, Camera permission/torch hardware, Contacts and
Phone permissions, a trusted non-emergency contact, and optionally a usable
YouTube API key configured locally. Do not expose any such secret or private
contact data.

## 7. M3–M6 and remaining work

- **M3 — Planned.** Notification access, a `NotificationListenerService`, local
  sender/app/timestamp/short-text records with retention and clear-data behavior,
  `count_new_messages`/`list_new_messages`, and explicit-only LLM summaries are
  required. The existing `NotificationPublisher` is reminder notification code,
  not M3 notification access. The eval harness is also not present: `docs/EVAL.md`
  has a plan and blank “not yet measured” results, but no `eval/utterances.json`
  was found. User-only dependencies are granting Notification access in Settings
  and later reporting actual measured results.
- **M4 — Planned.** Gmail read-only authorization using the current recommended
  Android API, unread count, today's mail, opt-in/UI-labelled summaries, local
  recipient resolution, editable drafts, and user-controlled `mailto:` handoff;
  no send scope and no persisted email bodies. No Gmail implementation or
  measured result is present. Google authorization is user-dependent.
- **M5 — Planned.** Personal facts/relationships/preferences/favorites with
  visible edit/delete and relevant-only retrieval; real device location,
  geocoding, weather, and route context; history-derived habits/buffers. Current
  travel code remains a fixed/demo route adapter and `CAPABILITIES.md` calls
  travel Partial. Favorite-song lookup and real location are not implemented.
- **M6 — Planned.** WorkManager daily briefing, calendar/weather/travel/email/
  message/task inputs, content opt-ins, SpeechRecognizer push-to-talk, and
  TextToSpeech, followed by final eval results. No voice or briefing result is
  evidenced.
- **Hardening/release.** Re-run the full regression after each milestone,
  reconcile documentation, preserve the no-destructive-Room-migration rule,
  complete privacy/error-path coverage, and only then use the approved release
  convention after physical verification.

## 8. Conflicts and reconciliation decisions

The newer user-approved workflow for this handoff says that passing
implementation tests plus a successful build may produce a
`phase6-mN-built` tag, with `Built (unverified)` status and a `docs/VERIFY-MN.md`
guide; `phase6-mN-verified` requires user-reported physical-device tests and
regressions. This is consistent with the existing `CAPABILITIES.md` wording
that built tags do not imply device verification and explains the existing
`phase6-m1-built` tag.

Older project instructions conflict in sequencing: `AGENTS.md` says “Only then:
commit and tag (`phase6-mN-verified`)” after physical verification, while
`docs/ASSISTANT-ROADMAP.md` says execute device verification, regression, docs,
commit, and tag; `docs/IMPLEMENTATION-PLAN.md` defines done as physical
verification plus a commit/tag of `phase6-mN-verified` and push. None of those
documents defines the newer intermediate `*-built` step. Claude must follow the
newer user-approved two-tag workflow for continuation, but should record the
reconciliation in the decision log when milestone work is resumed; this audit
does not rewrite those rules.

Other inconsistencies to resolve explicitly:

- Historical M1 documents describe Gemini model/availability questions and
  smoke statuses, but no current in-app physical proof exists; do not infer a
  live provider from a 200 smoke response.
- The roadmap describes M2 music “favorite song” support, while the current
  implementation and capabilities table correctly defer favorite lookup to M5.
- The roadmap/EVAL plan requires at least 100 labelled utterances and measured
  Offline-vs-Gemini results, but the dataset/results are absent/blank.
- Existing M2 docs say “35 tests pass” while this audit intentionally did not
  execute tests; treat that as an unverified report until a fresh run is made.

## 9. Precise continuation sequence

1. Preserve the current worktree. Read this file, `AGENTS.md`,
   `CLAUDE.md`, then the canonical state/decision/roadmap docs and M2 guide.
2. Review the M2 diff against the M1b baseline, including all five untracked
   files. Check that every tool has a schema, validation, static risk level,
   permission path, failure path, and test.
3. Run the pre-change command set below and record the result without exposing
   `local.properties` values.
4. Only after that, run the required unit tests and debug build. If they pass,
   fix documentation/status inconsistencies, retain `docs/VERIFY-M2.md`, and
   use the user-approved `phase6-m2-built` convention if committing is
   authorized by the continuation task.
5. Stop for the user to install the exact build and perform M2-1–M2-10 and
   R1–R9 with real taps. Record pass/fail/not-testable and observed UI results.
6. Only after those user-reported results pass, update capabilities/state/log,
   create `phase6-m2-verified`, and stop. If a check fails, do not create the
   verified tag; record the exact failure.
7. Start M3 only as a separate milestone: implement notification access and
   retention/clear-data first, then the stubbed eval harness and measured table.

## 10. Commands to run before modifying files

Run from `C:\Users\husai\Desktop\jarvis`:

```powershell
git status --short --branch
git branch --show-current
git log -10 --oneline --decorate
git tag --list "phase6-*"
git diff --stat
git diff --cached --stat
git diff --check
git diff -- app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java app/src/test/java docs
git diff --cached
git ls-files --others --exclude-standard
rg --files -g "*.md" -g "*.kt" -g "*.gradle*" -g "AndroidManifest.xml" -g "!local.properties" -g "!**/build/**"
Get-Content AGENTS.md -Raw
Get-Content CLAUDE.md -Raw
Get-Content docs/PROJECT-STATE.md -Raw
Get-Content docs/DECISION-LOG.md -Raw
Get-Content docs/ASSISTANT-ROADMAP.md -Raw
Get-Content docs/TRD.md -Raw
Get-Content docs/SCHEMA.md -Raw
Get-ChildItem local.properties | Select-Object Name,Length
Get-Content local.properties | ForEach-Object { ($_ -split '=',2)[0] }
```

The final command prints only configuration key names, not values. Do not run a
command that dumps `local.properties`, generated secrets, device contacts,
phone numbers, message/email content, or private signing material.
