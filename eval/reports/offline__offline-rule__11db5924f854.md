# Eval run: offline

- Models answering: offline/offline-rule
- Prompt hash: 11db5924f854; run dates: 2026-10-18
- Items scored: 126

| Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|
| 69.0% | 60.7% | 81.0% | 4.5% | 1 ms | 3 ms | 0 | 0 |

| Category | Items | Tool acc. | Arg acc. | Failures |
|---|---|---|---|---|
| create | 25 | 64.0% | 64.0% | 0 |
| cancel | 15 | 80.0% | 70.0% | 0 |
| incomplete | 20 | 80.0% | n/a | 0 |
| multi_turn | 10 | 50.0% | 50.0% | 0 |
| phone | 20 | 55.0% | 55.0% | 0 |
| messages | 6 | 83.3% | 83.3% | 0 |
| unsupported | 10 | 100.0% | n/a | 0 |
| hinglish | 8 | 37.5% | 28.6% | 0 |
| regression | 12 | 75.0% | 72.7% | 0 |

## Misses
| Item | Expected | Got | Args |
|---|---|---|---|
| cre-002 | tool_call:meeting.create | text |  |
| cre-005 | tool_call:meeting.create | text |  |
| cre-006 | tool_call:meeting.create | text |  |
| cre-014 | tool_call:set_alarm | text |  |
| cre-016 | tool_call:set_alarm | text |  |
| cre-019 | tool_call:set_timer | text |  |
| cre-020 | tool_call:set_timer | text |  |
| cre-024 | tool_call:meeting.create | text |  |
| cre-025 | tool_call:meeting.create | text |  |
| can-006 | tool_call:calendar.cancel | text |  |
| can-007 | tool_call:calendar.cancel | text |  |
| can-009 | tool_call:calendar.cancel | text |  |
| inc-007 | clarify | communication.draft | recipient=; body= |
| inc-012 | clarify | communication.draft | recipient=Rahul; body= |
| inc-017 | clarify | text |  |
| inc-019 | clarify | text |  |
| mt-004 | tool_call:set_alarm | text |  |
| mt-005 | tool_call:set_timer | text |  |
| mt-006 | tool_call:communication.draft | text |  |
| mt-008 | tool_call:navigate | text |  |
| mt-010 | tool_call:call_contact | text |  |
| ph-002 | tool_call:play_youtube | text |  |
| ph-005 | tool_call:play_music | text |  |
| ph-007 | tool_call:media_control | text |  |
| ph-008 | tool_call:media_control | text |  |
| ph-009 | tool_call:media_control | text |  |
| ph-012 | tool_call:set_volume | text |  |
| ph-014 | tool_call:flashlight | text |  |
| ph-016 | tool_call:call_contact | text |  |
| ph-019 | tool_call:navigate | text |  |
| msg-002 | tool_call:count_new_messages | communication.draft | recipient=me; body= |
| hin-001 | tool_call:meeting.create | text |  |
| hin-002 | tool_call:set_alarm | text |  |
| hin-003 | tool_call:set_timer | text |  |
| hin-004 | tool_call:call_contact | text |  |
| hin-005 | tool_call:flashlight | text |  |
| reg-003 | tool_call:play_music | text |  |
| reg-004 | tool_call:play_music | text |  |
| reg-005 | tool_call:play_music | text |  |
