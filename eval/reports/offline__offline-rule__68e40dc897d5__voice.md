# Eval run: offline

- Models answering: offline/offline-rule
- Prompt hash: 68e40dc897d5; run dates: 2026-10-21
- Dataset: v1 · 157 items · sha 08398dc27c72
- Configuration: engine=offline, configured model=offline-rule, throttle=0 ms
- Run tag: voice
- Replayed cloud answers: none
- Items selected: 157; scored (answered or failed): 157; not run: 0
- Rate-limit (HTTP 429) responses: 0; items failed after retries: 0
- Not run (not scored, not counted as wrong): none

| Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|
| 70.1% | 62.3% | 80.0% | 3.8% | 0 ms | 3 ms | 0 | 0 |

| Subset | Items scored | Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 |
|---|---|---|---|---|---|---|---|
| original (non-holdout) | 134 | 70.1% | 61.8% | 81.8% | 4.5% | 0 ms | 3 ms |
| holdout | 23 | 69.6% | 64.7% | 66.7% | 0.0% | 0 ms | 1 ms |

| Category | Items | Tool acc. | Arg acc. | Failures |
|---|---|---|---|---|
| create | 25 | 64.0% | 64.0% | 0 |
| cancel | 15 | 80.0% | 70.0% | 0 |
| incomplete | 20 | 80.0% | n/a | 0 |
| multi_turn | 10 | 50.0% | 50.0% | 0 |
| phone | 20 | 55.0% | 55.0% | 0 |
| messages | 6 | 83.3% | 83.3% | 0 |
| unsupported | 10 | 100.0% | n/a | 0 |
| hinglish | 8 | 50.0% | 42.9% | 0 |
| regression | 12 | 75.0% | 72.7% | 0 |
| phase0 | 8 | 75.0% | n/a | 0 |
| holdout | 23 | 69.6% | 64.7% | 0 |

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
| hin-004 | tool_call:call_contact | text |  |
| hin-005 | tool_call:flashlight | text |  |
| reg-003 | tool_call:play_music | text |  |
| reg-004 | tool_call:play_music | text |  |
| reg-005 | tool_call:play_music | text |  |
| p0-004 | no_tool | calendar.cancel |  |
| p0-006 | no_tool | compose_email | recipient_name=my manager; request=saying the report is ready |
| h-001 | tool_call:set_alarm | text |  |
| h-002 | tool_call:set_timer | text |  |
| h-005 | tool_call:set_volume | text |  |
| h-012 | clarify | communication.draft | recipient=; body= |
| h-013 | tool_call:count_new_messages | text |  |
| h-014 | tool_call:get_weather | text |  |
| h-017 | tool_call:media_control | text |  |
