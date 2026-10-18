# Eval run: hybrid

- Models answering: groq/openai/gpt-oss-120b, offline/offline-rule
- Prompt hash: 11db5924f854; run dates: 2026-10-18
- Items scored: 126

| Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|
| 97.6% | 97.8% | 100.0% | 1.1% | 604 ms | 1416 ms | 6 | 0 |

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

## Misses
| Item | Expected | Got | Args |
|---|---|---|---|
| can-015 | no_tool | daily_briefing |  |
| msg-002 | tool_call:count_new_messages | list_new_messages |  |
| hin-001 | tool_call:meeting.create | text |  |
