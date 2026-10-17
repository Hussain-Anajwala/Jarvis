# Eval run: groq

- Models answering: groq/openai/gpt-oss-120b, groq/openai/gpt-oss-20b
- Prompt hash: 7ec486198ed8; run dates: 2026-10-17
- Items scored: 114

| Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|
| 94.7% | 93.6% | 90.5% | 1.3% | 605 ms | 9605 ms | 43 | 0 |

| Category | Items | Tool acc. | Arg acc. | Failures |
|---|---|---|---|---|
| create | 25 | 100.0% | 96.0% | 0 |
| cancel | 15 | 100.0% | 100.0% | 0 |
| incomplete | 20 | 90.0% | n/a | 0 |
| multi_turn | 10 | 100.0% | 100.0% | 0 |
| phone | 20 | 95.0% | 95.0% | 0 |
| messages | 6 | 66.7% | 66.7% | 0 |
| unsupported | 10 | 100.0% | n/a | 0 |
| hinglish | 8 | 87.5% | 85.7% | 0 |

## Misses
| Item | Expected | Got | Args |
|---|---|---|---|
| cre-012 | tool_call:task.create | task.create | title=Renew passport |
| inc-001 | clarify | meeting.create | date=2026-10-18; hour=?; location=?; title=?; minute=? |
| inc-014 | clarify | flashlight | enabled=true |
| ph-006 | tool_call:play_favorite_song | get_favorite_song |  |
| msg-001 | tool_call:count_new_messages | list_new_messages |  |
| msg-003 | tool_call:count_new_messages | list_new_messages | app=WhatsApp |
| hin-001 | tool_call:meeting.create | text |  |
