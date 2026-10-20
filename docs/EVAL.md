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
