# JARVIS Project State

## Progress (resume here)
- **Branch:** `feature/research-agent` (local only; nothing pushed). Goal: "Privacy-Aware, Adaptive AI Agent for Android
  with Persistent Task Execution and Verifiable Tool Use" (final-year master prompt, phases 0–9).
- **Build/test:** `gradlew.bat testDebugUnitTest lintDebug assembleDebug` (Gradle 8.9 wrapper, AGP 8.6, JDK 17+).
  Instrumented: `gradlew.bat connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`,
  then `adb uninstall com.jarvis.test` (without the flag AGP uninstalls the app and wipes its data).
  CI: `.github/workflows/android.yml` (unit tests, lint, debug build; no secrets).
- **Device:** `RZCY31G8ZFK` (SM-A266B, Android 16). Agent checks use adb UI input on the real app, labelled "agent".
- **Latest results (2026-10-21, after Phase 7):** 275 unit tests (274 pass, 1 live-eval test skipped by design);
  9/9 instrumented tests; lint 0 errors / 19 warnings; debug build OK. Earlier (after Phase 6): 268 unit tests (267 pass, 1 skipped);
  9/9 instrumented tests on the phone (incl. migrations 5→6, 6→7, and Room restart recovery of a plan); lint 0 errors /
  19 warnings (none new); debug APK `app/build/outputs/apk/debug/app-debug.apk` installed with data kept.
  Commands: `gradlew.bat testDebugUnitTest lintDebug assembleDebug`; `gradlew.bat connectedDebugAndroidTest
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` then `adb uninstall com.jarvis.test`; PEV eval only:
  `gradlew.bat testDebugUnitTest --tests com.jarvis.eval.PevEvalTest` (writes eval/reports/pev__2026-10-21.md).

### Phase checklist
- [x] Audit
- [x] Phase 0 defects (commits 42e0387 … e42597a)
  - [x] 3.1 wake word: detector built once + cheap `reset()`; adaptive quiet gate (120–250 RMS); echo guard 0.8 s;
        panel states detected / starting / listening / follow-up / thinking / speaking / stopped / error
  - [x] 3.2 contacts: phonetic + multi-word "Did you mean?" (never automatic); four-Yusuf and mishearing tests
  - [x] 3.3 half duplex: no automatic capture while JARVIS speaks; follow-up waits for any playing answer
  - [x] 3.4 email: asks for an address it can't look up; spoken-address parsing, validation, read-back yes/no
  - [x] 3.5 memory: "tell me about me" asks (answered on the phone); secrets refused locally and never sent to a model
  - [x] 3.6 timers: JARVIS-run timers (exact alarm, durable record, Stop notification, reboot recovery); verified cancel
  - [x] 3.7 alarms: set / cancel / dismiss / snooze separated; a stop request can never create an alarm or timer
  - [x] 3.8 meetings: venue never invented; "cancel" or a new request is never taken as the venue
  - [x] 3.9 eval items p0-001…p0-008 added (labelled before any run)
- [x] Stabilization gate (agent): see "Phase 0 device checks" below; speech checks still need you
- [x] Phase 1 eval baselines (2026-10-21): offline, fixed cloud, hybrid measured; 23 holdout items added (docs/EVAL.md)
- [x] Phase 2 adaptive router (`AdaptiveRouter`, wired into JarvisCore; replay + live holdout comparison)
- [x] Phase 4 privacy policy (2026-10-21): `PrivacyPolicy` + guard before every model call; orphaned reminder removed
- [x] Phase 4.1 storage privacy: `SafeStorage` on every stored copy of user text (0bbebc7)
- [x] Phase 3 feasibility: TRD §12 report + tested `LocalModelReasoningProvider` seam; no model installed (4e67dd9)
- [x] Phase 5 durable reminders + bounded goal plans, Room v6 (a3c0867, 439d99c)
- [x] Phase 6 plan–execute–verify, Room v7 (e72ae5e): approvals, verification, recovery, PEV eval (18 scenarios)
- [x] Phase 7 research dashboard + CSV/JSON export (evidence asset from eval artifacts; allow-listed export; validator)
- [x] Phase 8A contextual memory (5efeb0f) · 8B typed tool registry + observed events, Room v8 (602471f)
- [x] Phase 9A Hinglish/conversational answers · 9B efficiency measurements (9ea4bb7); evidence provenance fix (4f73e40)
- [x] Reliability fix (2026-10-21, d86ebd2 → see below): reminders/Hindi/Hinglish, timers, travel, confirmations, voice
- [ ] Next: the phone checks that need you (listed under "Reliability fix — not verified"), then a fresh held-out set
      written by someone who has not seen the rules  ← **next action**
- [ ] Phase 3 follow-up (needs approval): add LiteRT-LM behind an opt-in model download and measure on the phone
- [ ] Final experiments, docs, build

### Reliability fix (2026-10-21)
Commits: d86ebd2 (v2 eval set + baseline, frozen first) · 1e61fd4 (B) · 7093445 (C) · f039f8a (D+E) · b1d7d5f (A) ·
92dfb46 (eval results) · this docs commit.

| Issue | Root cause | Status | Evidence |
|---|---|---|---|
| B1 "Create a reminder for 8:30 PM" / "label it X" | Only "remind me" started the reminder dialogue; otherwise the parser put the whole sentence in the title | Fixed | v2 dev d02/d03 pass; `ReliabilityParsingTest` |
| B2 "parso", Devanagari, Hindi numbers, impossible dates, midnight | "parso" not a day word (→ today); no Devanagari handling; dates never parsed (silently today); "tonight at 12" = noon | Fixed | `LanguageNormalizer`; `ReminderTime.date` → asks "Which day?"; midnight tests |
| B3 listing | Listing read only `agent_tasks` (meeting reminders in `reminders` were missing); Hinglish/Hindi listing words fell through to *create* | Fixed | Scope all/today/tomorrow; seeded in-memory Room test on the phone (`ReminderListingTest`, read-only asserted) |
| B4 "akhri reminder hata do", "rok do", "dobara shuru" | No synonyms for latest; no contextual stop/resume | Fixed | `ContextualControl.resolve` acts on what runs or asks; resume never starts a timer |
| A4 "Cancel the timer" → "Nothing is waiting for a yes or no" | Bare-answer check was "starts with a no-word and ≤ 3 words" | Fixed | `ApprovalAnswer.isBare` requires only answer words |
| C timers | No countdown UI/notification; repeated requests made duplicates; vague replies | Fixed (unit + build); not seen on the phone | Card + ongoing notification with Cancel; 15 s duplicate guard; "Your 45-minute timer has been cancelled." |
| D 7,793-minute travel | "college" geocoded to a far match; no plausibility bound; reminder = start − travel − 15 → days in the past | Fixed | Generic place → "Which college?"; > 500 km / > 10 h / impossible speed discarded; never in the past (also in BackgroundEngine) |
| E1 memory deletion | `forget_memory` deleted at once | Fixed | Shows the memory, deletes on yes within 5 min, only if unchanged; instrumented `ConfirmationFlowTest` |
| E2 calendar creation | `meeting.create` wrote at once; sync never read back | Fixed | Proposal → yes → write → provider read-back ("synced" only if found); registry needs confirmation |
| A1 hands-free follow-up | Follow-up only after a question | Built; **not verified with real speech** | Settings → Voice "Keep listening after answers" (off by default, 4–20 s window, on screen only) |
| A3 truthful voice states | No confirm / keep-listening / mic-off states | Built; not seen on the phone | Panel: Still listening…, Say yes or no, Stopped listening, Microphone is off |
| A4 "yes"/"haan" → "I didn't catch that" | Partial results not requested; short words ending in error 6/7 discarded | Fixed in code; **not verified with real speech** | `VoicePolicy.salvage` unit-tested |
| A2 wake word unreliable | Found: the mic-busy claim was released only by the screen's ViewModel; closing it mid-speech left the service paused until restart | Partial | Released in onCleared + 120 s self-heal. **No real-speech attempts measured** (needs you; mic must be granted by you) |

Results after the last code change: 317 unit tests (315 pass, 2 skipped by design: live eval, efficiency benchmark);
13/13 instrumented on the SM-A266B (incl. the two new ones); lint 0 errors / 19 warnings (unchanged; 6 informational notes); debug build
installed with data kept (first-install time unchanged). Intent v2: dev 31/31, held-out 30/30 (Hindi 6/6) — the
held-out items were visible while the rules were written, so this is not an independent measure. v1 set 52/55 (Phase 9:
50/55). Offline 157-item benchmark: 0 of 157 tool/argument outputs changed.

**Reliability fix — not verified on the phone** (it locked with a credential during the checks; adb cannot unlock it):
timer card and ongoing notification with Cancel; the new voice panel states; hands-free window with real speech;
"yes"/"haan" by voice; wake-word attempt counts; a real meeting proposal → yes → calendar read-back; listing in the app UI.

### Phase 0 device checks (agent, 2026-10-21, typed requests through the real UI)
| Check | Result |
|---|---|
| "set a timer for 3 minutes" → "cancel the timer" | Exact alarm scheduled; reply "Cancelled the 3-minute timer. It won't ring."; record CANCELLED; `dumpsys alarm` shows `Reason=alarm_cancelled`, nothing pending |
| "schedule a meeting tomorrow at 3 pm" → "cancel" | Asked where; no event created. Groq asked in plain text (no tool call), so "cancel" went to plan cancellation ("no active plans") |
| Email to "my manager" | Asked for the address; spoken form read back as an address; "no" asked again; "cancel" stopped. Nothing sent |
| "tell me about me" | Before fix: the model said nothing was saved (false; 4 items exist). After fix: "I have 4 saved items (3 facts, 1 preference). What would you like to know?…" |
| "my bank pin is 4821, remember it" | Before fix: sent to Groq (model refused). After fix: refused on the phone, no model call |
| App launch after the voice-state change | Crashed (init order); fixed in e913769 and re-verified |
Not run on the phone, deliberately: a ringing JARVIS timer (audible at 1 AM), Clock dismiss/snooze (could touch your
real alarms), contacts (personal names), anything spoken.

### Phase 4 device checks (agent, 2026-10-21)
- "my atm pin is 739204 is that a strong pin" (synthetic): reply "…stayed on your phone and wasn't sent to any AI
  service", engine label unchanged (no model call), Activity "Privacy decision: Local only: request contains a
  labelled credential (value not recorded)".
- Orphan cleanup on app start: reminders 8 → 7, none without a plan item; Activity records the removal.
- Not checked on the phone: a confirmed message summary containing an OTP (it would send message text to Groq);
  the redaction is covered by `PrivacyPolicyTest` against mock servers.

### Phase 8–9 device checks (agent, 2026-10-21)
- Provenance: the APK installed after Phase 7 carried evidence labelled 6a7ec56 (built before the final regeneration);
  the Phase 9 build first carried 602471f (inputs not yet committed). Generator fixed to mark uncommitted inputs;
  the installed APK now carries evidence from 9ea4bb7, which contains every input (verified by unzipping it).
- Room v7→v8 on the real database: every count unchanged (plans 10, reminders 7, memories 4, events 7, tasks 7, goals 1,
  plan runs 2); no crash on launch.
- 11/11 instrumented tests on the phone (incl. migration 7→8 and the event store in an in-memory Room database).
- **Not tested on the phone:** the Hinglish reminder/cancel, the bare "haan", and the plan correction. The phone locked
  during the check; none of the typed requests reached the app (no new task, plan run, or event), so nothing needed
  cleaning up. Spoken replies off, microphone denied, wake word off (checked through adb).

### Phase 7 device checks (agent, 2026-10-21)
- Settings → Open research dashboard: opened with no crash, offline; legend of evidence kinds, routing bars (69.4 / 90.3 /
  94.8 / 97.8 replay), every section, and this phone's counts (plan runs 1 succeeded / 1 cancelled, steps 2/2, privacy
  "local only secret" 1, routing cloud 1 / local parser 2, records since 2026-10-18).
- Export CSV and Export JSON through the system save screen (Downloads): "Saved the CSV export", "Saved the JSON
  export"; files pulled and validated with `eval/research/validate_export.py` (17 experiments, 107 metrics, 7 device-count
  rows, values equal to the bundled evidence, no email-like strings); both files deleted from Downloads afterwards.

### Phase 6 device checks (agent, 2026-10-21, typed requests)
- Real upgrade v6→v7 with every count unchanged (plans 10, reminders 7, memories 4, events 7, Phase 5 tasks 5, goals 1);
  it ran when the app-update broadcast started the reminder receiver.
- "remind me to stretch tomorrow at 10 am and remind me to drink water tomorrow at 11 am" → a 2-step plan shown →
  "yes" → "Done — every step was checked" with both steps "✓ verified"; Room: run SUCCEEDED, each step 1 attempt,
  evidence "reminder record scheduled and its alarm is set with Android"; 2 task alarms pending in `dumpsys alarm`.
- "remind me to read tomorrow at 9 pm and start a 25 minute timer" → plan → "no" → "Okay, I didn't run any of it.";
  Room: run CANCELLED, both steps cancelled with 0 attempts; no reminder or timer created.
- Force-stop + relaunch: both runs still listed in Plans with their per-step evidence; the 2 reminder alarms (cleared by
  the force-stop) were re-armed by recovery. Cleanup: both test reminders cancelled from Plans; 0 active reminders,
  0 task alarms. "Speak replies automatically" turned off at the end (it was on); microphone denied, wake word off.
- Not done on the phone: a plan with a timer (it would ring), "cancel all my reminders" with approval (destructive on
  real data), a real crash mid-step (covered by the instrumented Room recovery test and unit tests).

### Phase 5 device checks (agent, 2026-10-21, typed requests)
- Real upgrade: the app's v5 database became v6 with every count unchanged (plans 10, plan items 14, reminders 7,
  memories 4, events 7; 0 plan-less reminders). The migration ran when the app-update broadcast started the new
  receiver, before the app was opened.
- "Remind me to stretch in 30 minutes" → "…at 2:58 AM today" (alarm set); "…tomorrow at 9 AM to review my project";
  "List my pending reminders" → both; "Cancel the reminder I just created" → the newest; "cancel my stretch reminder";
  list → none; no task alarm left pending.
- Goal: "Help me prepare for my exam next Sunday" → topics → "at 7 pm" → proposal (3 sessions) → "yes" → "3 of 3
  scheduled"; "pause my plan" → 3 paused; "resume my study plan" → 3 re-armed; Plans → Cancel plan → all 5 test tasks
  and the goal cancelled, no alarms pending (records kept as cancelled history).
- Not checked on the phone (would ring at night or needs you): a reminder firing, the Done action, reboot recovery,
  a time-zone change, notifications turned off.

### Found during Phase 2 device check
- Standalone "remind me to …" was never scheduled (nothing displays or fires such reminders) yet JARVIS said
  "Reminder scheduled."; fixed to say so (9a32d63). The inert test record left by the old code was removed by the
  Phase 4 orphan cleanup.

### Needs you (speech or real alarms)
0. Phase 5: "remind me to stretch in 2 minutes" and wait for the notification; restart the phone with a reminder due
   in 5 minutes (it should ring after boot, or show "Missed reminder" if it passed long ago); on a plan step, tap Done.
1. Ringing timer: "set a timer for 10 seconds", let it ring, say or tap Stop. Then "set a timer for 1 minute" and
   restart the phone before it ends (expect it to ring, or a "Timer missed" notice).
2. While a Clock alarm rings: "turn the alarm off", "alarm band karo", "snooze the alarm for 5 minutes" (none may set an alarm).
3. Wake word on: "Hey JARVIS" right after JARVIS finishes speaking (the echo guard is now 0.8 s), and in a quiet room
   at normal volume. Watch the panel: "Heard 'Hey JARVIS'" → "Listening…" → "Thinking…" → "Speaking".
4. A question by voice ("write an email to my manager"): the follow-up should start only after the question has been spoken.
5. "call Yousuf" (misheard spelling): expect a "Did you mean?" list; cancel.

### Earlier rounds
- **Round 3 (2026-10-19):** contact matching + pick lists, alarm/timer cancellation, conversational email drafting,
  consistent voice + spoken-text normalisation, follow-up listening + listening panel, background "tap to continue",
  working permission buttons. Record: docs/verification/VERIFY-POST-M6.md (R-1…R-11).
- **Follow-up fixes (2026-10-18):** "Hey JARVIS" now captures and runs the next spoken request; ElevenLabs voices
  curated by name (keys only from `local.properties`); launcher icon + splash; theme choice; Activity rebuilt.
  Record: docs/verification/VERIFY-POST-M6.md (F-1…F-10).
- **Post-M6 upgrade (2026-10-18):** speech-output fix + optional ElevenLabs, truthful music playback, traffic
  provider chain, opt-in "Hey JARVIS", UI redesign. Records: docs/verification/VERIFY-POST-M6.md, README.md (capability summary),
  docs/OPEN-SOURCE-RESEARCH.md, docs/EVAL.md (126 items). No milestone tags were added or moved.

## Milestones
| Milestone | Status | Tags | Evidence |
|---|---|---|---|
| M1 LLM agent loop + provider chain | Verified (agent) | `phase6-m1-built`, `phase6-m1-verified` | verification/VERIFY-M1.md |
| M2 phone tools + favorite song | Built; most checks verified (agent) | `phase6-m2-built` | verification/VERIFY-M2.md — real call placement and music playback need you |
| M3 notifications + eval harness | Built; notification checks verified (agent) | `phase6-m3-built` | verification/VERIFY-M3.md, EVAL.md — Gemini comparison blocked by quota |
| M4 Gmail read-only + compose | Built; unread/today/disconnect/offline verified (agent) | `phase6-m4-built` | verification/VERIFY-M4.md — compose handoff needs a contact with an email |
| M5 memory + location + weather | Built; all M5 checks verified (agent) except M5-8 (unit-tested) | `phase6-m5-built` | verification/VERIFY-M5.md |
| M6 briefing + voice | Built; briefing + TTS verified (agent) | `phase6-m6-built` | verification/VERIFY-M6.md — spoken input needs you |

Verified tags were created only where the milestone's whole checklist passed (M1). Other milestones have one or
more human-only or blocked checks and stay at `built`.

## Phone state left by testing
Notification access ON (user approved), Gmail read-only consent granted, Location "while using the app", Calendar,
Contacts, and Notifications granted (re-granted after the accidental uninstall), Microphone denied (granted for
wake-word testing, then revoked again), Phone (call) never granted. Daily briefing OFF (07:30). Speak replies ON and
voice George on ElevenLabs (as you set them; the key you saved in the older Settings screen is still used). Wake word
OFF. Wi-Fi off / mobile data on (as found). No traffic key. YouTube Music and Spotify were
paused after the playback tests; dark mode left as found. Test memories, notification records, alarms, timers,
calendar events, and composer drafts were cleaned up. Media volume restored to 11.

## Next user actions (only things that need a human)
1. M2-9: `call <a consenting contact>` → **Call** → allow Phone → confirm the call starts, hang up.
2. M2-5: with your music app unlocked (Spotify has an app lock), `play music <artist>`; confirm it searches/plays.
3. M4-6: `write an email to <contact with a saved email> asking …` → edit the draft → **Open mail app** → discard it.
4. M6-7: allow the microphone, tap **Speak**, say "set a timer for one minute".
5. Gemini evaluation: both keys return HTTP 429 RESOURCE_EXHAUSTED. After quota resets (no billing needed), run
   `JARVIS_EVAL_ENGINE=gemini JARVIS_EVAL_PER_CATEGORY=2` (docs/EVAL.md).
6. Optional: exclude JARVIS from AppLock PRO while testing; it asks for a fingerprint after each reinstall.
7. Wake word (F-1…F-3): Settings → turn on "Listen for “Hey JARVIS”" (allow microphone). Say "Hey JARVIS",
   wait for the beep, then a request — once with JARVIS open on another tab, once in the background ("what's the
   weather"), and once in the background with "set a timer for one minute" (expect a "tap to open Clock"
   notification, not a timer). Also say "Hey JARVIS, write a leave application email" and answer the questions by
   voice without tapping (R-10), and "Hey JARVIS, call Maa" in the background (expect "tap to continue" to confirm).
   Turn it off afterwards.
8. When an alarm is ringing, open JARVIS and say or type "dismiss the ringing alarm" (R-6).
9. Optional: to move the ElevenLabs key into the build, add `ELEVENLABS_API_KEY=<key>` to `local.properties`,
   rebuild, then Settings → "Forget the saved key". Not needed today: the saved key works.
10. Optional live traffic: `GOOGLE_ROUTES_API_KEY` (Google Cloud project with billing and the Routes API enabled)
   or `MAPBOX_ACCESS_TOKEN` in `local.properties`, then rebuild. Without one, travel times say "no traffic data".
11. Optional Spotify autoplay would need Spotify Premium plus registering an app for the App Remote SDK.
Report results; then `phase6-m2-verified`, `-m4-verified`, `-m6-verified` (and m3 once Gemini is measured) can be tagged.

## Known limitations
- Live traffic needs a Google Routes or Mapbox key; without one, OSRM road-network time is labelled "no traffic data".
- The wake-word model is CC BY-NC-SA 4.0 (non-commercial only); "Hey JARVIS" was verified with a synthetic voice, not a person.
- From the background JARVIS can answer but cannot open its own screen or other apps (Android rule); it posts a notification to tap. Weather from the background may say no recent location (location is "while using the app"). Nominatim/OSRM/Open-Meteo are public free services.
- Messages arriving while Android rebinds the notification listener after an app update are not captured.
- Memory retrieval is lexical plus a small synonym list (guide→mentor, university→college…); learned buffer times are not implemented.
- Groq's free tier rate-limits `gpt-oss-120b`; the app then answers with `gpt-oss-20b` (labelled).
- SQLCipher is not used (plain Room). Force-stop cancels WorkManager jobs until the app is opened again.
- Historical per-session detail: docs/DECISION-LOG.md.
