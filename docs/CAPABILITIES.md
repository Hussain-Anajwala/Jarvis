# Capabilities — honest status
Legend: Verified = passed on the physical device through the app UI (user real taps, or agent-driven adb UI input where marked "agent") · Built (unverified) = compiled and unit-tested, awaiting the user's real-tap check · Partial = works with known limits · Pending = built/fix not yet confirmed · Demo-only = seeded data · Planned = milestone not started.
Only user-confirmed real-tap results are marked Verified. Built tags do not imply device verification.

| Capability | Status | Notes |
|---|---|---|
| Language understanding / provider failover | Verified (agent, 2026-10-17) | Groq answered live in-app (`openai/gpt-oss-120b`, HTTP 200) for clarification, follow-up, creation, cancellation, and T6; airplane mode produced a labelled offline fallback (`UnknownHostException` for Groq and both Gemini keys). Gemini fallback order is unit-tested; a live Gemini answer was not exercised in-app because Groq succeeded first. See [VERIFY-M1.md](./VERIFY-M1.md) |
| Create meeting + linked reminder | Verified | Latest user test confirmed the event and linked reminder; explicit event title and requested date are preserved |
| Cancel plan with cascade (Home and Plans tab) | Verified | Calendar event removed too |
| Ask for missing time/venue | Verified | Latest user test passed; ambiguous “around 6” asks AM/PM, and the follow-up preserves the title, venue, and requested weekday |
| Alarm via Clock handoff | Verified (agent, 2026-10-17) | `set_alarm` handoff: Samsung Clock listed an enabled `JARVIS alarm` at 4:17 AM (test alarm deleted afterwards). JARVIS reports a handoff, not a confirmed creation. |
| Reminder (in-app notification) | Verified | Distinct from alarm |
| Contact resolution by name | Verified | Handles ambiguous and unknown names |
| Message handoff to Messages | Verified | User taps send |
| WhatsApp message text prefill | Verified | Latest user regression test passed with the direct recipient chat and draft; no message is sent automatically |
| Travel time | Partial | OSRM live/estimated; route not yet tied to device location (M5) |
| Habit suggestion | Demo-only | Seeded history; real detection in M5 |
| Decision History, Plans, Permissions UI | Verified | Consistent dark theme |
| Timer | Verified (agent, 2026-10-17) | `ACTION_SET_TIMER`: Samsung Clock started a 2-minute timer (cancelled afterwards). |
| YouTube video by query | Verified (agent, 2026-10-17) | With `YOUTUBE_API_KEY` configured, the Data API selected a video and the YouTube app opened it. No-key/quota/network/malformed-response fallback to a search URL is unit-tested only. |
| Music by query / favorite song | Partial | `MEDIA_PLAY_FROM_SEARCH` reached the Android chooser (Spotify, VLC, YouTube, YT Music) and Spotify received the query, but playback was not observed: Spotify is behind a third-party app lock on the test phone. Favorite song is a local key-value preference (set / what is / play / forget) verified via the UI and persisted across force-stop. Autoplay is never claimed. |
| Media keys, volume | Verified (agent, 2026-10-17) | Media keys are fire-and-forget; replies state the result is unconfirmed or that no audio was playing. Volume 30% set stream 3 from 9/15 to 5/15 (restored); 101% rejected. |
| Flashlight | Verified (agent, 2026-10-17) | CameraManager torch; no CAMERA permission is declared or needed. Camera service logged torch on/off for the JARVIS process. |
| Call contact (confirm) | Partial | Unknown and ambiguous names stop; a unique contact shows the confirmation dialog and Cancel places no call (agent-checked). Placing a real call after **Call** + CALL_PHONE grant is human-only and not yet verified. Cancellation phrasing such as "call off my meeting" never reaches contact lookup. |
| Navigate | Verified (agent, 2026-10-17) | `google.navigation:` opened Google Maps navigation via the chooser; a `geo:` search fallback (unit-tested) covers devices without Google Maps. |
| New-message count/list (notifications) | Planned (M3) | Only messages received while access is enabled |
| Email unread count / today's mail / summary | Planned (M4) | Read-only; summaries send snippets to the LLM on request |
| Compose email draft | Planned (M4) | Handoff; user sends |
| Personal memory | Planned (M5) | |
| Daily briefing | Planned (M6) | |
| Voice in/out | Planned (M6) | |
| Evaluation results | Planned (M3) | Values appear in docs/EVAL.md only once measured |
