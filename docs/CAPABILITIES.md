# Capabilities — honest status
Legend: Verified = tested on device with real taps · Partial = works with known limits · Pending = built/fix not yet confirmed · Demo-only = seeded data · Planned = milestone not started.
Baseline below reflects tags up to phase5-alarm-diagnosed. Update this file after every verification.

| Capability | Status | Notes |
|---|---|---|
| Language understanding | Partial | Gemini 3.8 Flash is configured; its live endpoint returned HTTP 503 (high demand) in the probe, so retry/fallback handling is implemented but on-device Gemini success is not verified |
| Create meeting + linked reminder | Verified | Real Calendar Provider write (Google account calendar) |
| Cancel plan with cascade (Home and Plans tab) | Verified | Calendar event removed too |
| Ask for missing time/venue | Partial | Offline parser now asks a concise clarification and resolves a time/venue follow-up; automated unit tests pass, physical-device retest pending |
| Alarm via Clock handoff | Partial | Alarm is created; Samsung Clock's confirmation screen varies |
| Reminder (in-app notification) | Verified | Distinct from alarm |
| Contact resolution by name | Verified | Handles ambiguous and unknown names |
| Message handoff to Messages | Verified | User taps send |
| WhatsApp message text prefill | Pending | ACTION_SEND text/plain + EXTRA_TEXT handoff is implemented; Messages and WhatsApp prefill still require device verification |
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
