# Eval run: groq

- Models answering: groq/openai/gpt-oss-120b, groq/openai/gpt-oss-20b
- Prompt hash: e874324df1a3; run dates: 2026-10-17
- Items scored: 114

| Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|
| 84.2% | 78.2% | 90.5% | 11.5% | 759 ms | 8551 ms | 62 | 6 |

| Category | Items | Tool acc. | Arg acc. | Failures |
|---|---|---|---|---|
| create | 25 | 96.0% | 96.0% | 0 |
| cancel | 15 | 60.0% | 40.0% | 0 |
| incomplete | 20 | 90.0% | n/a | 0 |
| multi_turn | 10 | 100.0% | 90.0% | 0 |
| phone | 20 | 90.0% | 90.0% | 0 |
| messages | 6 | 0.0% | 0.0% | 5 |
| unsupported | 10 | 100.0% | n/a | 0 |
| hinglish | 8 | 87.5% | 85.7% | 1 |

## Misses
| Item | Expected | Got | Args |
|---|---|---|---|
| cre-007 | tool_call:reminder.create | text |  |
| can-002 | tool_call:calendar.cancel | text |  |
| can-003 | tool_call:calendar.cancel | text |  |
| can-004 | tool_call:calendar.cancel | text |  |
| can-006 | tool_call:calendar.cancel | text |  |
| can-009 | tool_call:calendar.cancel | text |  |
| can-010 | tool_call:calendar.cancel | text |  |
| inc-009 | clarify | meeting.create | date=2026-10-20; hour=18; location=clinic near Dadar; title=Dentist visit; minute=00 |
| inc-014 | clarify | flashlight | enabled=true |
| mt-002 | tool_call:meeting.create | meeting.create | date=2026-10-22; hour=16; location=library; title=Meeting at library; minute=00 |
| ph-002 | tool_call:play_youtube | text |  |
| ph-006 | tool_call:play_favorite_song | get_favorite_song |  |
| msg-001 | tool_call:count_new_messages | HTTP 400 Tool call validation failed: tool call validation failed: parameters for tool count_new_messages did not match schema: errors: [`/app`: expected string, but got null, `/contact`: expected string, but got null] |  |
| msg-002 | tool_call:count_new_messages | HTTP 400 Tool call validation failed: tool call validation failed: parameters for tool count_new_messages did not match schema: errors: [`/app`: expected string, but got null, `/contact`: expected string, but got null] |  |
| msg-003 | tool_call:count_new_messages | Unexpected function argument. |  |
| msg-004 | tool_call:list_new_messages | Unexpected function argument. |  |
| msg-005 | tool_call:list_new_messages | HTTP 400 Tool call validation failed: tool call validation failed: parameters for tool list_new_messages did not match schema: errors: [`/app`: expected string, but got null, `/contact`: expected string, but got null] |  |
| msg-006 | tool_call:summarize_new_messages | text |  |
| hin-006 | tool_call:count_new_messages | HTTP 400 Tool call validation failed: tool call validation failed: parameters for tool count_new_messages did not match schema: errors: [`/app`: expected string, but got null, `/contact`: expected string, but got null] |  |
