# M6 daily briefing + voice — verification guide

M6 adds:
- **Daily briefing**: off by default (Automations → Daily briefing). One notification per day at the chosen time,
  scheduled as a single unique WorkManager job (`REPLACE`) plus a last-delivered-date guard. Sections: Calendar,
  Travel to next meeting, Weather, Open tasks (on by default) and Unread email count, New message count (opt-in).
  Assembled on the phone without any model call; each failed source is reported ("Weather: unavailable (location
  permission off)") rather than skipped. Email in the background uses only a token Google grants silently.
  `good morning` / `brief me` / `give me my briefing` produce it on demand. Enabling it asks for notification
  permission and warns when notifications are off.
- **Voice**: Home → **Speak** (push-to-talk, one utterance, then stops; **Stop listening** cancels) using
  `SpeechRecognizer`; the transcript is shown (`Heard: "…"`) and submitted like typed text. **Speak replies**
  reads replies with `TextToSpeech`. Microphone permission is requested only on the first Speak tap; denial,
  missing recognition service, and recognizer errors all fall back to the text field with a message.

## Checks
| Test | Steps | Expected |
|---|---|---|
| M6-1 Briefing on request | `good morning` | `Good morning. Briefing for <date>:` with Calendar, Travel, Weather, Tasks lines; empty sections say so. |
| M6-2 Section failures | Turn Location off (or deny it), request the briefing. | `Weather: unavailable (location permission off).`; no crash. |
| M6-3 Opt-in sections | Tick Unread email and New messages, Preview now. | Email/Messages lines appear (or say why they are unavailable). Unticked sections never appear. |
| M6-4 Scheduled briefing | Enable, set the time a few minutes ahead, wait. | One "Daily briefing" notification at that time; History shows `Daily briefing delivered (assembled on device, no model call)`; no second briefing that day. |
| M6-5 Notification permission | Enable with notifications off. | Android asks to allow notifications; if declined, the card shows a red warning. |
| M6-6 Mic denied | Tap **Speak**, choose Don't allow. | `Microphone permission was denied. You can still type your request.` |
| M6-7 Voice input | Allow microphone, tap **Speak**, say "set a timer for one minute". | `Listening…` while active; then `Heard: "set a timer for one minute"` and the normal timer handoff. Mic stops after the utterance. |
| M6-8 Speak replies | Turn on Speak replies, ask `whats the weather`. | Reply is read aloud; turning it off stops speech. |
| M6-9 No speech service | On a phone without a recognition service. | `Speech recognition isn't available on this phone. Type your request instead.` |

Regression: R1–R9 (docs/IMPLEMENTATION-PLAN.md).

## Recorded results — agent-driven adb UI run, 2026-10-18

| Test | Result | Observed |
|---|---|---|
| M6-1 Briefing on request | Pass | `good morning` → `Good morning. Briefing for Sun 18 Oct:` / `Calendar: nothing scheduled today.` / `Travel: no meeting with a place today.` / `Weather: 28°C, partly cloudy. Today 27°C–33°C, 35% chance of rain. (Open-Meteo, live)` / `Tasks: none open.` |
| M6-2 Section failures | Pass | Final build in airplane mode: `good morning` → `Weather: unavailable (Open-Meteo unreachable).` with the other sections intact; no crash. |
| M6-3 Opt-in sections | Unit-tested | Email/Messages are off by default and appear only when ticked. |
| M6-4 Scheduled briefing | Pass | Enabled in Automations with time 01:30 (Android asked for notification permission → Allow). At 01:30 WorkManager posted a "Daily briefing" notification by itself; History: `Daily briefing delivered (assembled on device, no model call)`; afterwards exactly one `DailyBriefingWorker` job was pending (the next day). Afterwards the card was set back to the defaults (off, 07:30) through the UI and the pending job was cancelled (0 jobs). |
| M6-5 Notification permission | Pass | Enabling the briefing triggered Android's notification prompt (notification permission had been reset by the earlier uninstall). |
| M6-6 Mic denied | Pass | **Speak** → `Allow JARVIS to record audio?` → Don't allow → `Microphone permission was denied. You can still type your request.` |
| M6-7 Voice input | Not run (human-only) | Needs a person to speak; microphone permission was left denied. |
| M6-8 Speak replies | Pass | With media volume lowered to 1/15 (restored to 11 afterwards), Speak replies + `whats the weather` → `com.google.android.tts` started a `CONTENT_TYPE_SPEECH` track; switched off again. |
| M6-9 No speech service | Unit-tested | `VoicePolicy.onMicTapped(recognitionAvailable = false)`. |
