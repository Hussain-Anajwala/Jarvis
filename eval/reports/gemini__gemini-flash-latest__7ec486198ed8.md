# Eval run: gemini

- Models answering: offline/gemini-3.7-flash, gemini-flash-latest, offline/gemini-flash-latest, gemini-primary/gemini-flash-latest
- Prompt hash: 7ec486198ed8; run dates: 2026-10-18
- Items scored: 16

| Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|
| 6.3% | 8.3% | 0.0% | 0.0% | 3236 ms | 3236 ms | 11 | 15 |

| Category | Items | Tool acc. | Arg acc. | Failures |
|---|---|---|---|---|
| create | 2 | 0.0% | 0.0% | 2 |
| cancel | 2 | 0.0% | 0.0% | 2 |
| incomplete | 2 | 0.0% | n/a | 2 |
| multi_turn | 2 | 0.0% | 0.0% | 2 |
| phone | 2 | 0.0% | 0.0% | 2 |
| messages | 2 | 0.0% | 0.0% | 2 |
| unsupported | 2 | 0.0% | n/a | 2 |
| hinglish | 2 | 50.0% | 50.0% | 1 |

## Misses
| Item | Expected | Got | Args |
|---|---|---|---|
| cre-001 | tool_call:meeting.create | Network error (SocketTimeoutException). | title=Meeting tomorrow at; hour=10; minute=0; location=college; date=2026-10-19 |
| cre-002 | tool_call:meeting.create | Network error (SocketTimeoutException). |  |
| can-001 | tool_call:calendar.cancel | exception: ArrayIndexOutOfBoundsException |  |
| can-002 | tool_call:calendar.cancel | HTTP 429 RESOURCE_EXHAUSTED: You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit.  |  |
| inc-001 | clarify | HTTP 429 RESOURCE_EXHAUSTED: You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit.  |  |
| inc-002 | clarify | exception: ArrayIndexOutOfBoundsException |  |
| mt-001 | tool_call:meeting.create | HTTP 429 RESOURCE_EXHAUSTED: You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit.  | title=Meeting tomorrow; hour=10; minute=0; location=college; date=2026-10-19 |
| mt-002 | tool_call:meeting.create | exception: ArrayIndexOutOfBoundsException |  |
| ph-001 | tool_call:play_youtube | HTTP 429 RESOURCE_EXHAUSTED: You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit.  | query=lofi beats |
| ph-002 | tool_call:play_youtube | HTTP 429 RESOURCE_EXHAUSTED: You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit.  |  |
| msg-001 | tool_call:count_new_messages | HTTP 429 RESOURCE_EXHAUSTED: You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit.  |  |
| msg-002 | tool_call:count_new_messages | HTTP 429 RESOURCE_EXHAUSTED: You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit.  | recipient=me; body= |
| uns-001 | no_tool | HTTP 429 RESOURCE_EXHAUSTED: You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit.  |  |
| uns-002 | no_tool | HTTP 429 RESOURCE_EXHAUSTED: You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit.  |  |
| hin-001 | tool_call:meeting.create | HTTP 429 RESOURCE_EXHAUSTED: You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit.  |  |
