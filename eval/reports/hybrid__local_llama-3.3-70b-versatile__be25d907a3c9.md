# Eval run: hybrid

- Models answering: groq/openai/gpt-oss-120b, groq/openai/gpt-oss-20b, offline/offline-rule
- Prompt hash: be25d907a3c9; run dates: 2026-10-18
- Items scored: 114

| Tool acc. | Arg acc. | Clarify rate | False clarify | p50 | p95 | Retries | Failures |
|---|---|---|---|---|---|---|---|
| 97.4% | 100.0% | 90.5% | 0.0% | 522 ms | 6802 ms | 29 | 0 |

| Category | Items | Tool acc. | Arg acc. | Failures |
|---|---|---|---|---|
| create | 25 | 100.0% | 100.0% | 0 |
| cancel | 15 | 93.3% | 100.0% | 0 |
| incomplete | 20 | 90.0% | n/a | 0 |
| multi_turn | 10 | 100.0% | 100.0% | 0 |
| phone | 20 | 100.0% | 100.0% | 0 |
| messages | 6 | 100.0% | 100.0% | 0 |
| unsupported | 10 | 100.0% | n/a | 0 |
| hinglish | 8 | 100.0% | 100.0% | 0 |

## Misses
| Item | Expected | Got | Args |
|---|---|---|---|
| can-015 | no_tool | daily_briefing |  |
| inc-003 | clarify | meeting.create | date=2026-10-19; hour=15; location=Office; title=Meeting at Office; minute=00 |
| inc-019 | clarify | remember_fact | text=User wants to schedule something for next week. |
