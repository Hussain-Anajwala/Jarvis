# Eval run: adaptive-replay

- Models answering: local/offline-rule, replay/groq/openai/gpt-oss-120b, on-device/handler
- Prompt hash: 11db5924f854; run dates: 2026-10-21
- Dataset: v1 · 157 items · sha 08398dc27c72
- Configuration: engine=adaptive-replay, configured model=adaptive+replay, throttle=0 ms, categories=create,cancel,incomplete,multi_turn,phone,messages,unsupported,hinglish,regression,phase0
- Run tag: 2026-10-21
- Replayed cloud answers: hybrid__local_llama-3.3-70b-versatile__11db5924f854__2026-10-21.jsonl (not new measurements; latency as recorded)
- Items selected: 134; scored (answered or failed): 134; not run: 0
- Rate-limit (HTTP 429) responses: 0; items failed after retries: 0
- Not run (not scored, not counted as wrong): none

| Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|
| 97.8% | 97.8% | 100.0% | 1.1% | 276 ms | 698 ms | 0 | 0 |

| Subset | Items scored | Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 |
|---|---|---|---|---|---|---|---|
| original (non-holdout) | 134 | 97.8% | 97.8% | 100.0% | 1.1% | 276 ms | 698 ms |

| Category | Items | Tool acc. | Arg acc. | Failures |
|---|---|---|---|---|
| create | 25 | 100.0% | 100.0% | 0 |
| cancel | 15 | 93.3% | 100.0% | 0 |
| incomplete | 20 | 100.0% | n/a | 0 |
| multi_turn | 10 | 100.0% | 100.0% | 0 |
| phone | 20 | 100.0% | 100.0% | 0 |
| messages | 6 | 83.3% | 83.3% | 0 |
| unsupported | 10 | 100.0% | n/a | 0 |
| hinglish | 8 | 87.5% | 85.7% | 0 |
| regression | 12 | 100.0% | 100.0% | 0 |
| phase0 | 8 | 100.0% | n/a | 0 |

## Misses
| Item | Expected | Got | Args |
|---|---|---|---|
| can-015 | no_tool | daily_briefing |  |
| msg-002 | tool_call:count_new_messages | list_new_messages |  |
| hin-001 | tool_call:meeting.create | text |  |
