# Efficiency measurements (Phase 9B)

Timing boundary: local JVM compute on the development PC after warm-up; no Android runtime, no I/O, no network.
Not phone latency (end-to-end phone latency was not measured). Re-run with JARVIS_PERF=1; numbers vary between runs.

| Measurement | Result |
|---|---|
| On-device decision chain per utterance (OnDeviceIntents.classify, 55 texts × 20) | n = 1100, median 60.2 µs, p95 220.1 µs |
| ViewModel new-command check (5 parsers repeated) | n = 1100, median 12.5 µs, p95 32.9 µs |
| Router decision (parsers only) | n = 1100, median 33.6 µs, p95 73.3 µs |
| Memory lookup, 14 memories (20 queries × 20) | n = 400, median 78.1 µs, p95 216.4 µs |
| Memory lookup, 514 memories (20 queries × 5) | n = 100, median 3166.9 µs, p95 6277.2 µs |
| Intent-set items sent to the router/cloud ("none"), baseline | 24/55 |
| Intent-set items sent to the router/cloud ("none"), Phase 9 | 3/55 |
