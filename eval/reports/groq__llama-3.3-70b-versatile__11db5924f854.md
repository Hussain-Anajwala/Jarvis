# Eval run: groq

- Models answering: groq/openai/gpt-oss-120b, groq/openai/gpt-oss-20b
- Prompt hash: 11db5924f854; run dates: 2026-10-18
- Items scored: 126

| Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|
| 93.7% | 95.5% | 90.5% | 3.4% | 691 ms | 10967 ms | 65 | 0 |

| Category | Items | Tool acc. | Arg acc. | Failures |
|---|---|---|---|---|
| create | 25 | 100.0% | 100.0% | 0 |
| cancel | 15 | 93.3% | 100.0% | 0 |
| incomplete | 20 | 90.0% | n/a | 0 |
| multi_turn | 10 | 100.0% | 100.0% | 0 |
| phone | 20 | 100.0% | 100.0% | 0 |
| messages | 6 | 66.7% | 66.7% | 0 |
| unsupported | 10 | 90.0% | n/a | 0 |
| hinglish | 8 | 87.5% | 85.7% | 0 |
| regression | 12 | 91.7% | 90.9% | 0 |

## Misses
| Item | Expected | Got | Args |
|---|---|---|---|
| can-015 | no_tool | daily_briefing |  |
| inc-006 | clarify | call_contact | name=John Doe |
| inc-014 | clarify | flashlight | enabled=true |
| msg-005 | tool_call:list_new_messages | count_new_messages |  |
| msg-006 | tool_call:summarize_new_messages | text |  |
| uns-005 | no_tool | remember_fact | text=User wants an Uber to the airport. |
| hin-001 | tool_call:meeting.create | text |  |
| reg-011 | tool_call:daily_briefing | text |  |
