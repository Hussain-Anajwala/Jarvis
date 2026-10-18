# JARVIS Project State

## Progress (resume here)
- **Branch:** `phase6-llm-assistant`. Nothing has been pushed; the remote is untouched.
- **Build/test:** no Gradle wrapper. `~/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle --offline :app:testDebugUnitTest :app:assembleDebug`
  (AGP 8.6; JDK 22 is the only JDK; Gradle 8.7 cannot run on it). Lint: `:app:lintDebug` (needs network once).
  Instrumented migration tests: `:app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`
  (without the flag AGP uninstalls the app and wipes its data — this happened once on 2026-10-18).
- **Device:** `RZCY31G8ZFK` (SM-A266B). Agent-driven checks use adb UI input on the real app and are labelled "agent".
- **Latest results (post-M6 build):** 120 unit tests (119 pass, 1 live-eval test skipped by design), instrumented
  tests pass on the phone (3 migration + `WakeWordModelTest`), lint 0 errors / 19 warnings (dependency versions,
  locale, manifest — none new from this phase except one ObsoleteSdkInt), debug APK (~55 MB) installed on the phone.
- **Post-M6 upgrade (2026-10-18):** speech-output fix + optional ElevenLabs, truthful music playback, traffic
  provider chain, opt-in "Hey JARVIS", UI redesign. Records: docs/VERIFY-POST-M6.md, docs/JARVIS-CAPABILITIES.md,
  docs/OPEN-SOURCE-RESEARCH.md, docs/EVAL.md (126 items). No milestone tags were added or moved.

## Milestones
| Milestone | Status | Tags | Evidence |
|---|---|---|---|
| M1 LLM agent loop + provider chain | Verified (agent) | `phase6-m1-built`, `phase6-m1-verified` | VERIFY-M1.md |
| M2 phone tools + favorite song | Built; most checks verified (agent) | `phase6-m2-built` | VERIFY-M2.md — real call placement and music playback need you |
| M3 notifications + eval harness | Built; notification checks verified (agent) | `phase6-m3-built` | VERIFY-M3.md, EVAL.md — Gemini comparison blocked by quota |
| M4 Gmail read-only + compose | Built; unread/today/disconnect/offline verified (agent) | `phase6-m4-built` | VERIFY-M4.md — compose handoff needs a contact with an email |
| M5 memory + location + weather | Built; all M5 checks verified (agent) except M5-8 (unit-tested) | `phase6-m5-built` | VERIFY-M5.md |
| M6 briefing + voice | Built; briefing + TTS verified (agent) | `phase6-m6-built` | VERIFY-M6.md — spoken input needs you |

Verified tags were created only where the milestone's whole checklist passed (M1). Other milestones have one or
more human-only or blocked checks and stay at `built`.

## Phone state left by testing
Notification access ON (user approved), Gmail read-only consent granted, Location "while using the app", Calendar,
Contacts, and Notifications granted (re-granted after the accidental uninstall), Microphone denied (granted for
wake-word testing on 2026-10-18, then revoked again), Phone (call) never granted. Daily briefing OFF (07:30). Speak
replies OFF. Wake word OFF. Voice: phone voice (no ElevenLabs key). No traffic key. YouTube Music and Spotify were
paused after the playback tests; dark mode left as found. Test memories, notification records, alarms, timers,
calendar events, and composer drafts were cleaned up. Media volume restored to 11.

## Next user actions (only things that need a human)
1. M2-9: `call <a consenting contact>` → **Call** → allow Phone → confirm the call starts, hang up.
2. M2-5: with your music app unlocked (Spotify has an app lock), `play music <artist>`; confirm it searches/plays.
3. M4-6: `write an email to <contact with a saved email> asking …` → edit the draft → **Open mail app** → discard it.
4. M6-7: allow the microphone, tap **Speak**, say "set a timer for one minute".
5. Gemini evaluation: both keys return HTTP 429 RESOURCE_EXHAUSTED. After quota resets (no billing needed), run
   `JARVIS_EVAL_ENGINE=gemini JARVIS_EVAL_PER_CATEGORY=2` (docs/EVAL.md).
6. Optional: exclude JARVIS from AppLock PRO while testing; it asks for a fingerprint after each reinstall.
7. Wake word (W-8): Settings → turn on "Listen for “Hey JARVIS”" (allow microphone), say "Hey JARVIS" with the
   app open (it should start listening) and with it in the background (a "tap to talk" notification). Turn it off after.
8. Optional ElevenLabs voice: paste your API key in Settings → Voice replies → ElevenLabs, then "Test voice"
   (uses your ElevenLabs character quota).
9. Optional live traffic: a Google Routes API key (needs a billing-enabled Google Cloud project) or a Mapbox
   access token in Settings → Travel and live traffic. Without one, travel times say "no traffic data".
10. Optional Spotify autoplay would need Spotify Premium plus registering an app for the App Remote SDK.
Report results; then `phase6-m2-verified`, `-m4-verified`, `-m6-verified` (and m3 once Gemini is measured) can be tagged.

## Known limitations
- Live traffic needs a Google Routes or Mapbox key; without one, OSRM road-network time is labelled "no traffic data".
- The wake-word model is CC BY-NC-SA 4.0 (non-commercial only); a real spoken "Hey JARVIS" is not yet verified. Nominatim/OSRM/Open-Meteo are public free services.
- Messages arriving while Android rebinds the notification listener after an app update are not captured.
- Memory retrieval is lexical (no paraphrase matching); learned buffer times are not implemented.
- Groq's free tier rate-limits `gpt-oss-120b`; the app then answers with `gpt-oss-20b` (labelled).
- SQLCipher is not used (plain Room). Force-stop cancels WorkManager jobs until the app is opened again.
- Historical per-session detail: docs/DECISION-LOG.md.
