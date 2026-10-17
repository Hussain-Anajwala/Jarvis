# Eval run: gemini

- Models answering: offline/gemini-flash-latest
- Prompt hash: be25d907a3c9; run dates: 2026-10-18
- Items scored: 2

| Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|
| 0.0% | 0.0% | 0.0% | 0.0% | 0 ms | 0 ms | 2 | 2 |

| Category | Items | Tool acc. | Arg acc. | Failures |
|---|---|---|---|---|
| create | 1 | 0.0% | 0.0% | 1 |
| incomplete | 1 | 0.0% | n/a | 1 |

## Misses
| Item | Expected | Got | Args |
|---|---|---|---|
| cre-001 | tool_call:meeting.create | HTTP 429 RESOURCE_EXHAUSTED: You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit.  | title=Meeting tomorrow at; hour=10; minute=0; location=college; date=2026-10-19 |
| inc-001 | clarify | HTTP 429 RESOURCE_EXHAUSTED: You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit.  |  |
