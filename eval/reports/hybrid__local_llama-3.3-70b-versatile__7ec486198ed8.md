# Eval run: hybrid

- Models answering: groq/openai/gpt-oss-120b, offline/offline-rule
- Prompt hash: 7ec486198ed8; run dates: 2026-10-18
- Items scored: 114

| Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|
| 96.5% | 93.6% | 100.0% | 3.8% | 537 ms | 1034 ms | 0 | 0 |

| Category | Items | Tool acc. | Arg acc. | Failures |
|---|---|---|---|---|
| create | 25 | 100.0% | 96.0% | 0 |
| cancel | 15 | 93.3% | 90.0% | 0 |
| incomplete | 20 | 100.0% | n/a | 0 |
| multi_turn | 10 | 100.0% | 100.0% | 0 |
| phone | 20 | 100.0% | 100.0% | 0 |
| messages | 6 | 83.3% | 83.3% | 0 |
| unsupported | 10 | 100.0% | n/a | 0 |
| hinglish | 8 | 75.0% | 71.4% | 0 |

## Misses
| Item | Expected | Got | Args |
|---|---|---|---|
| cre-012 | tool_call:task.create | task.create | title=Renew passport |
| can-005 | tool_call:calendar.cancel | text |  |
| msg-002 | tool_call:count_new_messages | list_new_messages |  |
| hin-001 | tool_call:meeting.create | text |  |
| hin-007 | tool_call:calendar.cancel | text |  |
