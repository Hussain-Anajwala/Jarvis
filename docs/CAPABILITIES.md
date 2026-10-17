# Capabilities — honest status
Legend: Verified = tested on device with real taps · Built (unverified) = compiled and unit-tested, awaiting the user's real-tap check · Partial = works with known limits · Pending = built/fix not yet confirmed · Demo-only = seeded data · Planned = milestone not started.
Only user-confirmed real-tap results are marked Verified. Built tags do not imply device verification.

| Capability | Status | Notes |
|---|---|---|
| Language understanding / provider failover | Built (unverified) | Groq -> Gemini key 1 -> Gemini key 2 -> offline chain, runtime Groq model discovery, per-key/model Gemini budgets (18/day, 4/minute default), cooldowns, and shared tool schemas are implemented and unit-tested. Terminal smoke statuses: Groq HTTP 403, Gemini key 1 HTTP 200, Gemini key 2 HTTP 503. Live in-app provider-chain behavior awaits real-tap verification. See [VERIFY-M1.md](./VERIFY-M1.md) |
| Create meeting + linked reminder | Verified | Latest user test confirmed the event and linked reminder; explicit event title and requested date are preserved |
| Cancel plan with cascade (Home and Plans tab) | Verified | Calendar event removed too |
| Ask for missing time/venue | Verified | Latest user test passed; ambiguous “around 6” asks AM/PM, and the follow-up preserves the title, venue, and requested weekday |
| Alarm via Clock handoff | Partial | Alarm is created; Samsung Clock's confirmation screen varies |
| Reminder (in-app notification) | Verified | Distinct from alarm |
| Contact resolution by name | Verified | Handles ambiguous and unknown names |
| Message handoff to Messages | Verified | User taps send |
| WhatsApp message text prefill | Verified | Latest user regression test passed with the direct recipient chat and draft; no message is sent automatically |
| Travel time | Partial | OSRM live/estimated; route not yet tied to device location (M5) |
| Habit suggestion | Demo-only | Seeded history; real detection in M5 |
| Decision History, Plans, Permissions UI | Verified | Consistent dark theme |
| Timer | Planned (M2) | |
| YouTube video by query | Planned (M2) | |
| Music by query / favorite song | Planned (M2) | Autoplay not guaranteed |
| Media keys, volume, flashlight | Planned (M2) | |
| Call contact (confirm) | Planned (M2) | |
| Navigate | Planned (M2) | |
| New-message count/list (notifications) | Planned (M3) | Only messages received while access is enabled |
| Email unread count / today's mail / summary | Planned (M4) | Read-only; summaries send snippets to the LLM on request |
| Compose email draft | Planned (M4) | Handoff; user sends |
| Personal memory | Planned (M5) | |
| Daily briefing | Planned (M6) | |
| Voice in/out | Planned (M6) | |
| Evaluation results | Planned (M3) | Values appear in docs/EVAL.md only once measured |
