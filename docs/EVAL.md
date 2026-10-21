# Evaluation Plan and Results

## Goal
Measure how well each reasoning engine chooses tools and arguments, and asks for missing information,
on the same labelled utterances.

Engines compared (reconciled 2026-10-17 with the provider chain in docs/TRD.md):
- **OfflineRuleProvider** — local baseline, full dataset.
- **Groq** (primary cloud provider) — full dataset.
- **Hybrid** — what the app actually does: recognised phone/message commands go to the local parser,
  everything else to Groq. Full dataset; local items cost no API calls.
- **Gemini** (fallback provider) — stratified subset (first N items per category) within the free-tier
  quota, because the configured keys have small daily budgets.
- **Adaptive** (Phase 2, 2026-10-21) — `AdaptiveRouter`: privacy and on-device handlers first, then the local
  parser for recognised phone commands and confident schema-valid parses, otherwise Groq. See the Phase 1–2 results.

## Dataset (`eval/utterances.json`, 157 items since 2026-10-21)
| Category | Count | Notes |
|---|---|---|
| create (meeting, reminder, task, alarm, timer, message draft) | 25 | includes typos (`meetng tmrw`) and paraphrases |
| cancel / modify | 15 | 10 cancellations, 4 unsupported reschedules, 1 negated cancel |
| incomplete (should clarify) | 20 | missing time/venue/recipient/AM-PM etc. |
| multi_turn (follow-up resolves context) | 10 | scripted earlier turns |
| phone (YouTube, music, favorite song, media, volume, flashlight, call, navigate) | 20 | |
| messages (M3 notification tools) | 6 | |
| unsupported / chit-chat | 10 | includes financial and destructive requests |
| hinglish | 8 | |
| regression (added 2026-10-18, labelled before any run) | 12 | music app choice (Spotify / YouTube Music / none), Hinglish music, negated play, timer seconds, weekday meeting, "call off" cancellation, weather, "good morning", unread email |
| phase0 (added 2026-10-21, labelled before any run) | 8 | one per Phase 0 defect: alarm off / Hinglish / snooze, cancel timer, meeting without venue, email to a role, "tell me about me", a made-up PIN |
| holdout (added 2026-10-21, labelled before the router was written or run) | 23 | the adaptive router's held-out test; h-012 "Send a message" duplicates an existing item |

Labels were written before any model output was inspected. Item format:
```json
{"id": "cre-001", "category": "create",
 "turns": [{"role": "user", "text": "I have a meeting tomorrow at 10 AM at college"}],
 "expected": {"action": "tool_call", "tool": "meeting.create",
              "args": {"hour": "10", "minute": "0", "location": "college", "date": "tomorrow"}}}
```
`action` is `tool_call`, `clarify` (no tool and a question), or `no_tool` (no tool call). Dates use the
tokens `today`, `tomorrow`, or a weekday; text arguments match when one normalised value contains the other;
numbers compare numerically. Only the labelled arguments are checked.

## Protocol
- Tools are stubbed: the harness calls `ReasoningProvider.reason` and records the proposed tool call; nothing
  is executed and no Android API is touched.
- Same dataset, system prompt (`JarvisSystemPrompt`), and tool schemas for every engine; Groq temperature 0.2.
- Throttled (Groq 2.5 s, Gemini 16 s between requests by default); latency, non-200 attempts (retries), and
  provider failures are recorded per item.
- Resumable and cached: each finished item is appended to `eval/results/<engine>__<model>__<prompt-hash>.jsonl`;
  re-running skips finished items. The prompt hash changes when the prompt wording or tool schema changes.
  Offline results do not depend on a prompt, so delete the offline file after parser changes.
- A cloud engine that falls back to the offline parser is counted as a failure for that item, not as a
  correct or incorrect answer from the cloud model.

How to run (Git Bash, from the repo root; keys are read from `local.properties` via BuildConfig and never
written to results):
```
JARVIS_EVAL_ENGINE=offline gradle --offline :app:testDebugUnitTest --tests com.jarvis.eval.LiveEvalTest --rerun
JARVIS_EVAL_ENGINE=groq    gradle --offline :app:testDebugUnitTest --tests com.jarvis.eval.LiveEvalTest --rerun
JARVIS_EVAL_ENGINE=hybrid  gradle --offline :app:testDebugUnitTest --tests com.jarvis.eval.LiveEvalTest --rerun
JARVIS_EVAL_ENGINE=gemini JARVIS_EVAL_PER_CATEGORY=2 gradle --offline :app:testDebugUnitTest --tests com.jarvis.eval.LiveEvalTest --rerun
```
Optional: `JARVIS_EVAL_IDS`, `JARVIS_EVAL_CATEGORIES`, `JARVIS_EVAL_LIMIT`, `JARVIS_EVAL_THROTTLE_MS`.
Reports are written to `eval/reports/`.

## Metrics
- Tool-selection accuracy = correct decision (right tool, a clarification, or no tool, as labelled) / items
- Argument accuracy = tool-call items with the right tool and matching labelled arguments / tool-call items
- Clarification rate = clarifications / items labelled `clarify`
- False-clarification rate = clarifications on items labelled `tool_call` / tool-call items
- Latency p50 / p95 over items that did not fail; retries = non-200 provider attempts; failures as above

## Results (measured values only)

### Reliability fix: intent set v2 (English / Hinglish / Hindi) — 2026-10-21 (deterministic, no network)
Dataset `eval/intents/intents_v2.json` (61 items: 31 dev reproducing the reported defects, 30 held-out variations),
frozen and baselined before any change (d86ebd2). Chain: `OnDeviceIntents.classify`, clock Wed 21 Oct 2026 14:00 IST.
A clarification is correct when the item expects one. Reports: `eval/reports/intents_v2__baseline.md`, `…__final.md`.

| Split | Language | Baseline intent | Final intent | Final args (labelled items) |
|---|---|---|---|---|
| dev | en | 7/13 | 13/13 | 11/11 |
| dev | hinglish | 8/14 | 14/14 | 9/9 |
| dev | hindi | 0/4 | 4/4 | 4/4 |
| held-out | en | 7/14 | 14/14 | 11/11 |
| held-out | hinglish | 9/10 | 10/10 | 8/8 |
| held-out | hindi | 0/6 | 6/6 | 3/3 |

Safety items (all in the set, all correct in the final run): bare "Haan"/"yes please" with nothing pending approves
nothing (2/2); "Cancel the timer" is a cancel, not an answer; impossible or unreadable dates and "25:00" are asked
about (3/3); missing AM/PM asked (2/2); "Forget …" is classified as a deletion that needs confirmation. False-positive
actions in the final run: 0 of 61. **Caveat:** the held-out items were written before the fixes but were visible while
the rules were written, so held-out 30/30 is not an independent generalisation measure.

Regression checks: v1 set (`intents__reliability.md`) 52/55 intents (Phase 9: 50/55; d30 weather, h05/h21 plan
pause/resume in Hinglish still miss); memory set unchanged (normal test run compares against committed results);
offline 157-item benchmark: 0 of 157 tool/argument outputs changed from Phase 9 (run not committed: only latency differs).

### Phases 8–9: memory, intents (English/Hinglish), and efficiency — 2026-10-21 (deterministic, no network)
Labels for both sets were written and committed **before** any change (commits 18c39b2 and 9470958); dev items were
used to write rules, held-out items were not. Caveat: one mid-way dev check printed every split, so the held-out
intent numbers were seen once before the final run; no rule was changed after that. All results are recomputed by
`IntentEvalTest` / `MemoryEvalTest` (normal runs only verify the committed results; `JARVIS_INTENT_WRITE` /
`JARVIS_MEMORY_WRITE` rewrite them).

On-device intents (`eval/intents/intents.json`, 55 items; intent correct, then arguments where labelled):

| Split / language | Baseline (before) | Phase 9 |
|---|---|---|
| dev English (10) | 8/10 · args 3/3 | 9/10 · 3/3 |
| dev Hinglish (20) | 4/20 · args 0/11 | 20/20 · 11/11 |
| held-out English (9) | 8/9 · args 3/4 | 8/9 · 3/4 |
| held-out Hinglish (16) | 2/16 · args 0/9 | 13/16 · 7/9 |
| items sent to the router/cloud | 24/55 | 3/55 |

Remaining misses: d30 (label error: weather is answered on the phone, labelled "none"); h01 "Parso…" (not a known day
word, so it would be scheduled **today** — a wrong date, kept as a known bug); h03 "Akhri reminder hata do" (latest not
recognised); h04 "Mere saare reminders batao" (offline parser proposes reminder.create, which then asks for a time);
h05 "rok do", h21 "dobara shuru" (not recognised); h11 "Set a 45 minute timer" (goes to the cloud). Regression check:
the offline engine on all 157 labelled items changed only hin-003 (now a correct timer): original 134 items 69.4% →
70.1% tool accuracy (`eval/reports/offline__offline-rule__11db5924f854__phase9.md`).

Memory retrieval (`eval/memory/memory_cases.json`, 14 synthetic memories, 20 queries):

| Split | Baseline: best first / nothing when irrelevant | Phase 8A |
|---|---|---|
| dev (10) | 5/8 · 2/2 | 8/8 · 2/2 |
| held-out (10) | 8/9 · 1/1 | 8/9 · 1/1 |

Unrelated memories returned: 0 in both; the stored secret was never returned. The held-out miss (q16, "what do I drink
in the morning") needs world knowledge.

Efficiency (`JARVIS_PERF=1 gradlew.bat testDebugUnitTest --tests com.jarvis.eval.EfficiencyBenchmarkTest`; JVM on the
development PC after warm-up, no I/O; numbers vary by run; not phone latency): on-device decision chain median ≈ 60 µs
(p95 ≈ 220 µs, n = 1,100); the ViewModel's repeated new-command parser check ≈ 12 µs median — too small to justify a
cache, so none was added; router parser checks ≈ 34 µs; memory lookup ≈ 0.08 ms for 14 memories and ≈ 3 ms for 514
(linear). Cloud requests avoided are the deterministic count above (24 → 3 of 55 intent items). Plan orchestration
overhead with fakes was measured in Phase 6 (0–2 ms median). End-to-end phone latency and battery were not measured.

### Research dashboard provenance (Phase 7, 2026-10-21)
The in-app Research dashboard and its CSV/JSON exports show the results above; they do not re-run any experiment.
Routing and PEV metrics are recomputed from the stored per-item results (`eval/results/*.jsonl` plus the labels in
`eval/utterances.json`) with the same scorer, and match the tables here (offline 69.4/60.7, fixed cloud 90.3/91.0,
hybrid 94.8/97.8, adaptive replay 97.8/97.8, held-out adaptive 23/23 vs offline 65.2%; the 2026-10-18 runs 69.0, 93.7,
97.6 are labelled historical). The adaptive result on the original items stays labelled as a replay; Gemini is shown
as not run; the skipped live-eval unit test is shown as skipped, not passed. The PEV report's overhead line is
wall-clock timing and changes slightly on every run (median 0–2 ms seen), so the dashboard shows it as a range from a
labelled static entry. `ResearchEvidenceTest` fails if `app/src/main/assets/research/evidence.json` drifts from these
artifacts. Validate an export with `python eval/research/validate_export.py <file.json> <file.csv>`.

### Phase 6 plan–execute–verify evaluation — 2026-10-21 (automated, JVM)
`eval/pev_scenarios.json` (18 scenarios, expectations written before the first run): multi-step requests, approval
denied / never answered / revoked / expired, a destructive step, tool failures (permanent and transient), a failed
postcondition, an unverifiable step, an uncertain step, crashes after a side effect (idempotent and not), non-plans,
and a missing time. Real `PlanParser`, `PlanExecutor`, and tool classes over in-memory reminder/timer infrastructure;
no Android, no network, no real alarms. Report: `eval/reports/pev__2026-10-21.md`. Run:
`gradlew.bat testDebugUnitTest --tests com.jarvis.eval.PevEvalTest`.

| Metric | Result |
|---|---|
| Final state matches expectation | 18/18 |
| Plan validity (steps parsed as expected, or correctly not a plan) | 17/17 |
| Executed steps follow the plan order | 15/15 |
| Completed and verified where success was expected | 6/6 |
| Executed steps verified (the rest failed, were unverifiable, or uncertain by design) | 15/20 |
| Unauthorized effects (denied, unanswered, revoked, expired) | 0 in 4 scenarios |
| Duplicate effects | 0 |
| Recovery after a simulated crash reaches the expected state | 2/2 |
| Orchestration overhead (fake tools, JVM, not phone) | median 0–2 ms, max 3–36 ms across runs (timing-dependent; printed to the console, not stored in the report) |

Limits: 18 hand-written scenarios, one author, no statistical claim; the first run exposed a real bug (epoch times
redacted as card numbers), fixed before these numbers; the scenario expectations were not changed. Live phone
observations are listed separately in PROJECT-STATE ("Phase 6 device checks").

### Phase 1 baselines and Phase 2 router — prompt hash `11db5924f854`, 2026-10-21
Run tag `2026-10-21` (fresh measurements; no cached items reused). Tools stubbed: the harness only records the
proposed tool call; nothing is executed. Groq free tier only, 2.5 s between requests; a run stops after 3
rate-limited items in a row. No ElevenLabs or other speech output. Dataset sha `60f070927431` (134 items) for the
fixed-cloud run; `08398dc27c72` (157 items, holdout added) for the others — the 134 original items are identical
in both. Reports with every miss: `eval/reports/*__11db5924f854__2026-10-21.md`; raw results in `eval/results/`.

Original 134 items:

| Strategy | How measured | Answered by | Cloud calls | Tool acc. | Arg acc. | Clarify | False clarify | p50 | p95 |
|---|---|---|---|---|---|---|---|---|---|
| Offline parser | live, no network | offline-rule ×134 | 0 | 69.4% | 60.7% | 81.8% | 4.5% | 1 ms | 4 ms |
| Fixed cloud (Groq) | live | gpt-oss-120b ×91, gpt-oss-20b ×43 | 134 | 90.3% | 91.0% | 95.5% | 5.6% | 781 ms | 11244 ms |
| Hybrid (app before Phase 2) | live | gpt-oss-120b ×97, local ×37 | 97 | 94.8% | 97.8% | 100% | 1.1% | 352 ms | 891 ms |
| Adaptive (Phase 2) | **replay**: hybrid's recorded cloud answers + live local routing | gpt-oss-120b ×71 (replayed), local parser ×56, on-device ×7 | 71 | 97.8% | 97.8% | 100% | 1.1% | 276 ms | 698 ms |

Held-out 23 items (labelled before the router existed):

| Strategy | How measured | Answered by | Cloud calls | Tool acc. | Arg acc. |
|---|---|---|---|---|---|
| Offline parser | live | offline-rule ×23 | 0 | 65.2% | 58.8% |
| Adaptive | live | gpt-oss-120b ×11, local parser ×10, on-device ×2 | 11 | 100% (23/23; 95% CI lower bound ≈ 86%) | 100% |
| Fixed cloud, hybrid | **not run** | — | — | — | — |

- Rate limits: fixed cloud saw 77 HTTP 429 responses, all absorbed by the provider's bounded retries (no item
  failed); 43 of its answers came from the fallback `gpt-oss-20b`, so it is not a clean single-model baseline.
  Hybrid and adaptive saw none. Total Groq requests: 134 + 97 + 11 = 242 items (plus retries).
- Not run, by the agreed cap of 134 cases per configuration: fixed cloud and hybrid on the 23 holdout items. The
  adaptive result on the original items is a replay, not a new measurement; its latency for cloud items is the
  latency recorded in the hybrid run.
- Hybrid matches the 2026-10-18 run on the same 126 items (97.6%); its 94.8% here is lower only because of the new
  phase0 items (4/8). Adaptive's gain on the original items comes entirely from those 8 items, which its rules were
  designed for — in-sample, so optimistic. The confident-parse rule moved 20 original items and 10 holdout items
  from the cloud to the local parser with no accuracy loss on either set (27% fewer cloud calls than hybrid).
- Remaining misses (hybrid and adaptive): can-015 "Don't cancel anything, just tell me my plans" → briefing;
  msg-002 count vs list; hin-001 Hinglish meeting answered in text.
- Privacy note (added after Phase 4): the fixed-cloud run sent item p0-008 — a made-up PIN ("my bank pin is 4821,
  remember it") — to Groq; Groq declined to store it. Since Phase 4, live runs go through the same
  `PrivacyGuardProvider` as the app, so such an item is answered locally and never sent; privacy enforcement itself is
  verified with mock servers (`PrivacyPolicyTest`), not live calls. The 2026-10-21 results above are kept unchanged as
  historical evidence; stored replies in later runs are redacted.
- Behaviour change after Phase 5 (not re-measured): reminder and goal commands are now handled on the phone and ask
  for a missing time. Holdout h-007 "Remind me to buy milk" (labelled `reminder.create`) would now get no tool call and
  a question, so a future adaptive run would score it as a miss under the existing label; the label is left unchanged.
- Label note: p0-006 ("write an email to my manager…") is labelled `no_tool` because the app drafts emails in its
  own dialogue, but a `compose_email` tool exists and the offline parser chose it. The label was written before the
  run and was not changed after seeing results; read p0-006 results with this in mind.


### Post-M6 run — prompt hash `11db5924f854`, 2026-10-18
126 items (the 114 above plus 12 regression items). The prompt hash changed because `play_music` gained an
optional `app` argument. Reports with every miss: `eval/reports/*__11db5924f854.md`.

| Engine (items) | Models that answered | Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|---|---|
| OfflineRuleProvider (126) | offline-rule | 69.0% | 60.7% | 81.0% | 4.5% | 1 ms | 3 ms | 0 | 0 |
| Groq (126) | gpt-oss-120b ×62, gpt-oss-20b ×64 | 93.7% | 95.5% | 90.5% | 3.4% | 691 ms | 10967 ms | 65 | 0 |
| Hybrid = app routing (126) | local parser ×36, gpt-oss-120b ×90 | 97.6% | 97.8% | 100.0% | 1.1% | 604 ms | 1416 ms | 6 | 0 |
| Gemini | not run (both keys returned HTTP 429 in the previous run; quota not re-tested) | | | | | | | | |

- Regression items: offline 9/12, Groq 11/12 (missed "Good morning JARVIS" → text), hybrid 12/12.
- Groq-only misses also include a guessed contact name ("John Doe") and the flashlight turned on for an
  incomplete request — the reasons the app keeps phone commands on the local parser.
- Remaining hybrid misses: "Don't cancel anything, just tell me my plans" → briefing tool (can-015); "how many
  new messages" variant → list instead of count (msg-002); one Hinglish meeting request answered in text (hin-001).
- Groq's free tier again rate-limited `gpt-oss-120b` (65 retries in the Groq-only run), so that run is about
  half `gpt-oss-20b`; compare engines with that in mind. Expected answers were not edited after seeing outputs.

### Behaviour regressions outside the utterance eval
These failures from the post-M6 work are about execution and reporting, not tool choice, so they are covered by
unit/instrumented tests and device checks (docs/verification/VERIFY-POST-M6.md) rather than utterances:

| Failure class | Tests |
|---|---|
| Music claimed as playing when it was not | `MusicPlaybackTest.playbackIsConfirmedOnlyByANewPlayingSession`, `repliesNeverClaimPlaybackWithoutConfirmation`; `M2SafetyRoutingTest.mediaKeyMessagesNeverClaimConfirmedPlayback`; device M-1/M-2 |
| TTS reading transcripts, placeholders, or history | `SpeechOutputTest` (11 tests, e.g. `userInputAndPlaceholdersAreNeverSpoken`, `aCompletedAnswerIsSpokenExactlyOnceAndHistoryIsNeverReplayed`); device S-1…S-4 |
| Relative dates / weekdays | `promptGivesTodaysDateSoRelativeDatesResolveWithoutAskingForAFormat`, `clarifiedDentistVisitKeepsTitleVenueAndRequestedWeekday`; eval reg-008 |
| Cancellation phrasing | `cancellationPhrasesNeverBecomePhoneActions`, `negatedOrNonPlanDeletionIsNotACancellation`; eval reg-009, can-015 |
| Optional parameters sent as null | `argumentParserAcceptsNullOptionalsAndNumbersButRejectsUnknownKeys`; eval reg-001…005 |
| Revoked Gmail access / auth errors | `GmailTest.authScopeRateAndServerErrorsAreTyped`, `missingTokenAndNetworkFailureNeverCrash` |
| Timeouts | `musicSearchUsesTheMusicCategoryAndHandlesZeroResultsAndTimeouts`, ElevenLabs `elevenLabsErrorsMissingKeyAndNetworkFailuresAreReportedNotThrown` |
| Permissions denied | `deniedContactsPermissionNeverResolvesOrCalls`, `confirmationThenPhonePermissionDenialPlacesNoCall`, `voiceIsOptionalAndEveryFailureFallsBackToText`; device W-1 |
| Live traffic unavailable | `TrafficRoutingTest.routerUsesTrafficWhenConfiguredAndOtherwiseLabelsOsrmAsNoTraffic`; device T-1 |
| Wake-word lifecycle | `WakeWordTest` (cooldown, suppression, one-shot listen requests, quiet-gate equivalence), `WakeWordModelTest` on the phone; device W-2…W-7 |

### Final run — M6 build, prompt hash `be25d907a3c9`, 2026-10-18
Same 114-item dataset; the tool list now includes the M4–M6 tools (email, memory, weather, briefing).
Reports with every miss: `eval/reports/*__be25d907a3c9.md`.

| Engine (items) | Models that answered | Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|---|---|
| OfflineRuleProvider (114) | offline-rule | 67.5% | 59.0% | 76.2% | 5.1% | 0 ms | 2 ms | 0 | 0 |
| Groq (114) | gpt-oss-20b ×111, gpt-oss-120b ×3 | 97.4% | 100.0% | 90.5% | 0.0% | 525 ms | 8649 ms | 36 | 0 |
| Hybrid = app routing (114) | local parser ×30, gpt-oss-20b ×66, gpt-oss-120b ×18 | 97.4% | 100.0% | 90.5% | 0.0% | 522 ms | 6802 ms | 29 | 0 |
| Gemini key 1 (2-item probe) | — | not measurable | | | | | | 2 | 2 |

- In this run Groq's free tier rate-limited `gpt-oss-120b`, so model discovery answered most items with
  `gpt-oss-20b`; the numbers describe that mix, not 120b alone.
- Remaining misses (Groq/Hybrid): a negated request ("Don't cancel anything, just tell me my plans") answered with the
  briefing tool instead of text; "Book a meeting at 3 pm" created a meeting at a guessed "Office" instead of asking;
  "Schedule something for next week" was saved as a memory; "Turn the flashlight" turned it on; "Remind me" created
  a reminder titled "Remind me".
- Gemini: both items returned HTTP 429 `RESOURCE_EXHAUSTED` again, so no Gemini accuracy exists.

### M3 run — prompt hash `7ec486198ed8`, 2026-10-17/18 (kept for comparison)
Dataset `eval/utterances.json` v1 (114 items), prompt hash `7ec486198ed8`, run on 2026-10-17/18 from the
development PC. Raw per-item results: `eval/results/`; reports with every miss: `eval/reports/`.

| Engine (items) | Models that answered | Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|---|---|
| OfflineRuleProvider (114) | offline-rule | 67.5% | 59.0% | 76.2% | 5.1% | 0 ms | 2 ms | 0 | 0 |
| Groq (114) | gpt-oss-120b ×93, gpt-oss-20b ×21 | 94.7% | 93.6% | 90.5% | 1.3% | 605 ms | 9605 ms | 43 | 0 |
| Hybrid = app routing (114) | local parser ×30, gpt-oss-120b ×84 | 96.5% | 93.6% | 100.0% | 3.8% | 537 ms | 1034 ms | 0 | 0 |
| Gemini key 1 (16, 2 per category) | — | not measurable | | | | | | 11 | 15 |

Notes on these numbers:
- Groq's model discovery fell back from `gpt-oss-120b` to `gpt-oss-20b` for 21 items after non-200 responses
  (43 retries, consistent with free-tier rate limiting); the p95 latency includes those retries.
- **Gemini is blocked by quota, not measured.** Of 16 items, 10 returned HTTP 429 `RESOURCE_EXHAUSTED`
  ("exceeded your current quota … plan and billing"), 2 timed out, 3 hit an `ArrayIndexOutOfBoundsException`
  in the Gemini retry code (a real bug, fixed and regression-tested afterwards), and 1 answered. A 2-item probe
  of Gemini key 2 also returned 429 `RESOURCE_EXHAUSTED`. No accuracy is reported for Gemini; re-run
  `JARVIS_EVAL_ENGINE=gemini JARVIS_EVAL_PER_CATEGORY=2` after the quota resets (no billing was set up).
- The offline baseline is the parser at the M3 commit, including the M3 safety fixes found by this evaluation
  (negated or non-plan "delete/cancel" no longer maps to `calendar.cancel`; vague "something"/"there" asks).

Earlier run kept for comparison (same dataset, prompt hash `e874324df1a3`, 2026-10-17, before the fixes below):
Groq 84.2% tool / 78.2% args / 11.5% false clarification / 6 failures; offline 62.3% / 59.0%. That run found:
optional tool arguments sent as `null` were rejected (Groq HTTP 400 and the client parser), the model picked
wrong dates for weekday names, and cancellations were answered with a text "are you sure?" instead of the
`calendar.cancel` tool. The fixes: nullable optional fields and a shared argument parser, a seven-day date table
in the system prompt, and an instruction that the app confirms cancellations itself.

## Threats to validity
Small developer-written dataset; one run per engine; free-tier model behaviour and model lists change over
time (Groq's discovered model is recorded per item); the Gemini numbers cover a subset only; multi-turn
items use scripted earlier turns; stubbed tools do not test execution on the device; latency includes
network conditions on the evaluation machine.
