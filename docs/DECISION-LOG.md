# Decision Log

- 2026-09-10 — Skipped SQLCipher for the Phase 1 demo and used plain Room; key management is deferred so the local data and end-to-end flow can be demonstrated first. A TODO remains in the data layer. (TRD §3, SCHEMA §2)
- 2026-09-10 — Kept MockReasoningProvider active by default and made CloudReasoningProvider injectable/configurable; no API key was provided, so the offline demo must not depend on cloud access. (TRD §2, SCHEMA §5)
- 2026-09-10 — Stored UUIDs and timestamps as Room-compatible String/Long values while preserving the schema fields and enum values; this avoids custom converters in the first scaffold. (SCHEMA §3)
- 2026-09-10 — Used a local calendar representation for the demo and attempt Android Calendar Provider writes only when calendar permission is granted; this keeps the demo reliable on an emulator without seeded provider accounts. (APP-FLOW §3)
- 2026-09-10 — Device verification found the mock parser matched `meeting` before `cancel`, so “Cancel my meeting” created another meeting; cancellation matching now runs first. (APP-FLOW §5)
- 2026-09-10 — Device verification found the Plans screen hid its persisted dependency rows; it now renders linked PlanItems and reminder status so the create/cascade demo is directly inspectable. (APP-FLOW §3, UI-UX §4.2)
- 2026-09-10 — Clean device run on `RZCY31G8ZFK` passed the requested flow. The “calendar event” is verified as a local Room Event mirror with `androidCalendarEventId = 0`; no Android Calendar Provider row was created because no writable provider account/permission was available on the device. (SCHEMA §3, APP-FLOW §3)
