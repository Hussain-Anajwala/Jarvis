# Eval run: groq

- Models answering: groq/openai/gpt-oss-120b, groq/openai/gpt-oss-20b
- Prompt hash: 11db5924f854; run dates: 2026-10-21
- Dataset: v1 · 157 items · sha 08398dc27c72
- Configuration: engine=groq, configured model=llama-3.3-70b-versatile, throttle=2500 ms, categories=create,cancel,incomplete,multi_turn,phone,messages,unsupported,hinglish,regression,phase0
- Run tag: 2026-10-21
- Replayed cloud answers: none
- Items selected: 134; scored (answered or failed): 134; not run: 0
- Rate-limit (HTTP 429) responses: 77; items failed after retries: 0
- Not run (not scored, not counted as wrong): none

| Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|
| 90.3% | 91.0% | 95.5% | 5.6% | 781 ms | 11244 ms | 77 | 0 |

| Subset | Items scored | Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 |
|---|---|---|---|---|---|---|---|
| original (non-holdout) | 134 | 90.3% | 91.0% | 95.5% | 5.6% | 781 ms | 11244 ms |

| Category | Items | Tool acc. | Arg acc. | Failures |
|---|---|---|---|---|
| create | 25 | 100.0% | 100.0% | 0 |
| cancel | 15 | 80.0% | 80.0% | 0 |
| incomplete | 20 | 95.0% | n/a | 0 |
| multi_turn | 10 | 100.0% | 100.0% | 0 |
| phone | 20 | 100.0% | 100.0% | 0 |
| messages | 6 | 33.3% | 33.3% | 0 |
| unsupported | 10 | 100.0% | n/a | 0 |
| hinglish | 8 | 87.5% | 85.7% | 0 |
| regression | 12 | 91.7% | 90.9% | 0 |
| phase0 | 8 | 62.5% | n/a | 0 |

## Misses
| Item | Expected | Got | Args |
|---|---|---|---|
| can-005 | tool_call:calendar.cancel | text |  |
| can-009 | tool_call:calendar.cancel | text |  |
| can-015 | no_tool | daily_briefing |  |
| inc-014 | clarify | flashlight | enabled=true |
| msg-001 | tool_call:count_new_messages | list_new_messages |  |
| msg-002 | tool_call:count_new_messages | list_new_messages |  |
| msg-003 | tool_call:count_new_messages | list_new_messages | app=WhatsApp |
| msg-006 | tool_call:summarize_new_messages | text |  |
| hin-001 | tool_call:meeting.create | text |  |
| reg-011 | tool_call:daily_briefing | text |  |
| p0-003 | no_tool | set_timer | duration_seconds=600 |
| p0-007 | no_tool | list_memories |  |
| p0-008 | no_tool | remember_fact | text=Bank PIN: 4821 |
