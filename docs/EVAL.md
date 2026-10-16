# Evaluation Plan and Results

## Goal
Measure how well each reasoning engine chooses tools and arguments, and asks for missing information,
on the same labelled utterances. Compare OfflineRuleProvider (baseline) with GeminiReasoningProvider.

## Dataset (`eval/utterances.json`, >= 100 items)
| Category | Target count |
|---|---|
| Create (meeting, reminder, task, alarm, timer) | 25 |
| Cancel / modify | 15 |
| Incomplete (should trigger a clarifying question) | 20 |
| Multi-turn (follow-up resolves earlier context) | 10 |
| Phone tools (YouTube, music, call, navigate, media) | 20 |
| Unrelated / chit-chat / unsupported | 10 |

Item format:
```json
{
  "id": "inc-007",
  "category": "incomplete",
  "turns": [{"role": "user", "text": "I have a meeting tomorrow"}],
  "expected": {"action": "clarify", "missing": ["time", "location"]}
}
{
  "id": "cre-012",
  "category": "create",
  "turns": [{"role": "user", "text": "Meeting tomorrow at 10 AM at college"}],
  "expected": {"action": "tool_call", "tool": "create_meeting",
               "args": {"time": "10:00", "location": "college"}}
}
```
Labels are written before looking at model outputs. Include paraphrases, typos, and Hinglish phrasing.

## Protocol
Tools are stubbed: the harness records the chosen tool and arguments and executes nothing.
Temperature low and fixed; same dataset and prompts for each engine; log latency per request;
respect rate limits (throttle, record retries).

## Metrics
- Tool-selection accuracy = correct tool (or correct "clarify"/"no tool") / total
- Argument accuracy = items whose arguments match expected (normalized times/dates) / tool-call items
- Clarification rate = correct clarifications / items labelled incomplete (and false-clarification rate on complete items)
- Latency p50 / p95

## Results (fill only with measured values)
| Engine | Tool acc. | Arg acc. | Clarify rate | p50 | p95 |
|---|---|---|---|---|---|
| OfflineRuleProvider | not yet measured | | | | |
| Gemini (model: ___) | not yet measured | | | | |

## Threats to validity (state these in the report)
Small developer-written dataset; one run per engine unless repeated; free-tier model behavior can change; stubbed tools do not test execution.
