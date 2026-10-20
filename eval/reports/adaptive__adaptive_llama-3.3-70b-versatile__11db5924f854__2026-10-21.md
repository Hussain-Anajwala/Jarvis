# Eval run: adaptive

- Models answering: groq/openai/gpt-oss-120b, local/offline-rule, on-device/handler
- Prompt hash: 11db5924f854; run dates: 2026-10-21
- Dataset: v1 · 157 items · sha 08398dc27c72
- Configuration: engine=adaptive, configured model=adaptive+llama-3.3-70b-versatile, throttle=2500 ms, categories=holdout
- Run tag: 2026-10-21
- Replayed cloud answers: none
- Items selected: 23; scored (answered or failed): 23; not run: 0
- Rate-limit (HTTP 429) responses: 0; items failed after retries: 0
- Not run (not scored, not counted as wrong): none

| Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|
| 100.0% | 100.0% | 100.0% | 0.0% | 12 ms | 1476 ms | 0 | 0 |

| Subset | Items scored | Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 |
|---|---|---|---|---|---|---|---|
| holdout | 23 | 100.0% | 100.0% | 100.0% | 0.0% | 12 ms | 1476 ms |

| Category | Items | Tool acc. | Arg acc. | Failures |
|---|---|---|---|---|
| holdout | 23 | 100.0% | 100.0% | 0 |

## Misses
| Item | Expected | Got | Args |
|---|---|---|---|

