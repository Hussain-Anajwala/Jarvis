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

## Dataset (`eval/utterances.json`, 114 items)
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
