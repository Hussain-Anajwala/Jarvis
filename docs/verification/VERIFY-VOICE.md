# Voice reliability, timer alerts, Hindi/Hinglish — verification (2026-10-21)

Status keys: **FIXED AND TESTED** (automated tests ran and passed, and the behaviour was checked on the phone where
possible) · **IMPLEMENTED BUT NOT PHYSICALLY VERIFIED** · **BLOCKED BY PLATFORM/ENVIRONMENT** · **STILL FAILING**.
"Agent" checks were typed through the real app UI over adb on the SM-A266B (Android 16, app targetSdk 35); nothing
in this file was spoken by a person.

## What was checked on the phone (agent)
| Check | Evidence |
|---|---|
| 10 s timer, app on screen | Android event log `notification_alert … 17009, buzz=1, beep=1` (before the fix: no alert event; the alert was cancelled 200 ms after posting). Alarm stream 11/15, not muted. Record `RINGING`, "alert shown with sound requested". Card Stop → `FINISHED`, alert removed, "Stopped your 10-second timer." |
| Timer engine on the phone | `TimerAlertDeviceTest` 2/2 (3 consecutive runs): countdown shown; on ring the countdown goes and one alert is posted on the sound channel; a repeated delivery posts nothing; Stop removes alert and alarm; a timer cancelled early never alerts. |
| Hinglish replies | "Mere saare reminders batao" → "Aapka koi reminder nahi hai."; "Haan" → "Abhi kisi cheez ke liye haan ya na ka intezaar nahi hai."; "Kal shaam 7 baje reminder lagao" → "Aapko kis cheez ka reminder chahiye?"; "cancel" → "Theek hai, koi reminder nahi lagaya." |
| Meetings | "Kal subah 10 baje VJTI mein project review meeting hai" → Hinglish proposal for "Project review meeting", Thu 22 Oct, 10:00 AM, VJTI; "nahi" → nothing written. "I have a meeting tomorrow at 10 AM at Dadar station" → "What is the meeting about?" → "Budget review" → proposal → "no" → nothing written. |
| Confirmation retry | Test memory: "Forget …" → "hmm" → "I didn't catch a yes or no. Should I delete "…"?" (same memory, count unchanged) → "Han" → deleted; the user's memories unchanged (5 → 6 → 5). |
| Wake word UI | Service listening; Settings shows real room noise (81) and quiet gate (203), score 0.00 in silence. |
| Wake word, synthetic | 5 plays of a synthetic "Hey JARVIS" clip through the PC speakers: 0 detections, peak score ≤ 0.04 per 20 s window. Synthetic audio — not evidence about a real voice. |

## Statuses
| Item | Status |
|---|---|
| Timer finishes audibly (screen on) | FIXED AND TESTED — Android logged beep+buzz; *hearing* it needs you (Manual 1) |
| Timer with app in background / screen off | IMPLEMENTED BUT NOT PHYSICALLY VERIFIED — the end-of-session attempt was invalid: the 20 s timer rang (beep=1 buzz=1 logged) 24 s *before* the screen went off, because the test script waited for the reply first; the phone then locked with its credential, so it could not be repeated. The ringing alert could not be stopped from the lock screen (notifications hidden there) and was stopped with `am force-stop com.jarvis` (no data cleared). Manual 1 covers it. |
| Timer sound/vibration settings, "you won't hear it" warnings | FIXED AND TESTED (unit + Settings on the phone) |
| Spoken yes / no / haan / nahi | IMPLEMENTED BUT NOT PHYSICALLY VERIFIED by voice (typed and unit/instrumented paths pass) — Manual 2 |
| Confirmation kept through misheard answers, expiry, corrections | FIXED AND TESTED (typed on the phone, instrumented, unit) |
| Wake word reliability | BLOCKED BY ENVIRONMENT — needs real spoken attempts (Manual 3); tooling to measure it is built |
| Screen-off wake word | IMPLEMENTED BUT NOT PHYSICALLY VERIFIED — platform limits below |
| Voice enrollment | Feasibility study + calibration screen only (not a functional enrollment) — see VOICE-ENROLLMENT-FEASIBILITY.md |
| Meeting titles | FIXED AND TESTED |
| Hindi/Hinglish replies | FIXED AND TESTED for the local replies listed in EVAL.md; cloud answers follow the prompt instruction (not measured: no live calls) |

## Platform limits (Android 16, targetSdk 35)
- The wake-word service is a `microphone` foreground service with a visible notification. Android only lets it *start*
  while JARVIS is on screen; if Android stops it (battery saver, force-stop, low memory) it cannot restart itself with
  microphone access — JARVIS then shows "Hey JARVIS not running" until you open it again.
- A request heard with the screen off is answered aloud only while JARVIS's screen task still exists (not swiped away).
  Otherwise JARVIS posts a "tap to run" notification: Android does not allow opening the app from the background.
- If another app takes the microphone, Android gives JARVIS silence; after 2 s the chip and Settings say "Mic blocked
  by Android".
- Do Not Disturb is never bypassed. The timer alert uses alarm audio (allowed by your "Work" mode); the wake cue uses
  assistant audio (heard whenever JARVIS's voice is).

## Manual tests (you)
**1. Timer sound.** Settings → Timers: both switches on and no red warning. Say or type "set a 10 second timer", lock
the phone, put it down. Expected: a repeating beep (and vibration) until you tap Stop on the notification or the
card. Repeat with JARVIS swiped to the home screen (not closed from Recents). Note whether you heard it each time.

**2. Spoken answers.** Settings → Permissions: allow the microphone (normal Android prompt). Say "remember that my
test colour is teal", then "forget that my test colour is teal". When asked, say "haan", and in a second run "nahi",
"no", "yes", "हाँ" (if your keyboard/recogniser gives Devanagari). Expected: the answer is taken without "I didn't catch
that"; if the recogniser misses it, JARVIS says the request is still waiting and listens again (up to twice).

**3. Wake word.** Settings → "Hey JARVIS" on (it starts while JARVIS is open). In the "Wake-word test" card: Start a
test session. Screen on: say "Hey JARVIS" 10 times at arm's length (wait for the two-tone beep or 5 s, say "cancel"),
tapping +1 each time. Screen off: lock, say it 10 times, unlock, add 10. Quiet period: start it, talk normally for
5 minutes without the phrase, stop it. Read the card's line (detected / missed / false activations) and note: phone
battery saver on/off, room noise, distance. The service log line "peak score" shows how close missed attempts came.

**4. Hands-free follow-up.** Settings → Voice: "Speak replies automatically" and "Keep listening after answers" on.
Tap the mic, ask "what time is it"; after the answer the panel should say "Still listening…" and stop after the window.
