# JARVIS Project State

## Progress (resume here)
- **Branch:** `phase6-llm-assistant`. Nothing has been pushed; the remote is untouched.
- **Build/test:** no Gradle wrapper. `~/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle --offline :app:testDebugUnitTest :app:assembleDebug`
  (AGP 8.6; JDK 22 is the only JDK; Gradle 8.7 cannot run on it). Lint: `:app:lintDebug` (needs network once).
  Instrumented migration tests: `:app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`
  (without the flag AGP uninstalls the app and wipes its data — this happened once on 2026-10-18).
- **Device:** `RZCY31G8ZFK` (SM-A266B). Agent-driven checks use adb UI input on the real app and are labelled "agent".
- **Latest results (follow-up fixes, 2026-10-18):** 143 unit tests (142 pass, 1 live-eval test skipped by design),
  lint 0 errors (warnings are dependency versions, locale, manifest, one old ObsoleteSdkInt), debug APK
  `app/build/outputs/apk/debug/app-debug.apk` installed on the phone; 4 instrumented tests pass on the phone
  (3 Room migration + `WakeWordModelTest`).
- **Follow-up fixes (2026-10-18):** "Hey JARVIS" now captures and runs the next spoken request; ElevenLabs voices
  curated by name (keys only from `local.properties`); launcher icon + splash; theme choice; Activity rebuilt.
  Record: docs/VERIFY-POST-M6.md (F-1…F-10).
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
wake-word testing, then revoked again), Phone (call) never granted. Daily briefing OFF (07:30). Speak replies ON and
voice George on ElevenLabs (as you set them; the key you saved in the older Settings screen is still used). Wake word
OFF. Wi-Fi off / mobile data on (as found). No traffic key. YouTube Music and Spotify were
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
7. Wake word (F-1…F-3): Settings → turn on "Listen for “Hey JARVIS”" (allow microphone). Say "Hey JARVIS",
   wait for the beep, then a request — once with JARVIS open on another tab, once in the background ("what's the
   weather"), and once in the background with "set a timer for one minute" (expect a "tap to open Clock"
   notification, not a timer). Turn it off afterwards.
8. Optional: to move the ElevenLabs key into the build, add `ELEVENLABS_API_KEY=<key>` to `local.properties`,
   rebuild, then Settings → "Forget the saved key". Not needed today: the saved key works.
9. Optional live traffic: `GOOGLE_ROUTES_API_KEY` (Google Cloud project with billing and the Routes API enabled)
   or `MAPBOX_ACCESS_TOKEN` in `local.properties`, then rebuild. Without one, travel times say "no traffic data".
10. Optional Spotify autoplay would need Spotify Premium plus registering an app for the App Remote SDK.
Report results; then `phase6-m2-verified`, `-m4-verified`, `-m6-verified` (and m3 once Gemini is measured) can be tagged.

## Known limitations
- Live traffic needs a Google Routes or Mapbox key; without one, OSRM road-network time is labelled "no traffic data".
- The wake-word model is CC BY-NC-SA 4.0 (non-commercial only); "Hey JARVIS" was verified with a synthetic voice, not a person.
- From the background JARVIS can answer but cannot open its own screen or other apps (Android rule); it posts a notification to tap. Weather from the background may say no recent location (location is "while using the app"). Nominatim/OSRM/Open-Meteo are public free services.
- Messages arriving while Android rebinds the notification listener after an app update are not captured.
- Memory retrieval is lexical (no paraphrase matching); learned buffer times are not implemented.
- Groq's free tier rate-limits `gpt-oss-120b`; the app then answers with `gpt-oss-20b` (labelled).
- SQLCipher is not used (plain Room). Force-stop cancels WorkManager jobs until the app is opened again.
- Historical per-session detail: docs/DECISION-LOG.md.
