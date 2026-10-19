# M2 phone tools — built, unverified

This guide is for real physical-device verification of the M2 build. Unit tests
and an APK build do not verify that another Android app accepts an intent or
that hardware behaves as expected. Record the actual on-screen result for each
step; do not mark a test passed based only on JARVIS's reply.

## Before testing

1. Install the M2 debug APK on the physical Android phone and launch JARVIS.
2. Use real taps and text entry. Do not use scripted input, direct function
   calls, or simulated permission results.
3. Keep the phone unlocked. Install and sign in to a music player for the music
   test. Keep YouTube and Maps installed if available.
4. YouTube API search is optional. To test API-selected videos, add
   `YOUTUBE_API_KEY=<your key>` to the ignored `local.properties`, enable
   YouTube Data API v3 for that key, and rebuild locally. Never paste the key
   into chat or screenshots. Without a key, or when API quota/access/search is
   unavailable, JARVIS should open a YouTube search instead.
5. For the call test, choose a trusted contact who expects the test. Do not use
   an emergency number. The app first requests Contacts access; after a unique
   match it shows the resolved contact. Tap **Call** to confirm. Only then, if
   needed, Android requests Phone permission and JARVIS may start the call.
   End the test call promptly. Denying either permission must not place a call.
6. The flashlight needs no permission: `CameraManager.setTorchMode` does not
   open the camera, so JARVIS does not declare or request CAMERA.

## M2 manual checks

| Test | Real device steps | Expected result |
|---|---|---|
| M2-1 Timer | Enter `set a timer for 2 minutes` and tap **Run request**. Follow any native Clock prompt. | Clock receives a 120-second timer request. JARVIS says it handed the request to Clock; count it as successful only if the timer is visibly present/accepted in Clock. |
| M2-2 Alarm | Enter `set an alarm for 7 AM`, then complete the native Clock UI if shown. | Clock receives the alarm time and label. Confirm the alarm exists/enabled in Clock; a handoff message alone is not proof of creation. |
| M2-3 YouTube, no API key | With `YOUTUBE_API_KEY` absent, enter `play weather forecast on YouTube`. | A YouTube search opens in an available browser/YouTube handler and JARVIS reports the no-key search fallback. It must not claim a specific video was selected. |
| M2-4 YouTube, API success | With a locally configured usable key, repeat M2-3. | If the API returns a result and the YouTube app is installed, the selected video opens in that app. If search returns quota/access/network failure, a YouTube search URL opens and the reply says why the API path was unavailable. |
| M2-5 Music | Start with `play music Miles Davis` and choose an available media app if Android asks. | Android hands the query to a media app. Autoplay is not guaranteed; verify the app's own search/playback state rather than relying on JARVIS wording. If no app handles the intent, JARVIS reports that. |
| M2-6 Media keys | Start audio in a music app. Enter `pause music`, then `resume music`, `next song`, and `previous song`, checking each action. Repeat once with no audio playing. | Media key events are sent through Android's media-key dispatcher. Replies say the result is unconfirmed, or that no audio was playing; JARVIS never claims playback started. |
| M2-7 Volume | Enter `set volume to 30%`, then try `set volume to 101%`. Inspect the phone's media volume. | The valid request changes music-stream volume to approximately 30% of its available range. The invalid request is rejected with a recoverable message and does not change volume. |
| M2-8 Flashlight | Enter `turn on flashlight`, verify the torch, then enter `turn flashlight off`. | No permission prompt appears. Torch turns on/off; missing torch hardware or camera contention produces a visible error; no error is reported as success. |
| M2-9 Call contact | Enter `call <exact contact name>`. Grant Contacts permission if prompted. Inspect the confirmation dialog and its resolved display name. Tap **Cancel** once and repeat; this time tap **Call**, then grant Phone permission if asked. | Unknown/ambiguous names do not proceed. No call is placed before the explicit **Call** tap. Cancel leaves the call unplaced. After confirmation and permission, Android starts the call to the locally resolved contact. |
| M2-11 Favorite song | Enter `what is my favorite song`, then `my favorite song is Kesariya`, `what is my favorite song`, `play my favorite song`, force-stop and reopen JARVIS, ask again, then `forget my favorite song`. | Unknown at first; saved locally; `play` hands `Kesariya` to the music chooser; the value survives a restart; `forget` clears it. |
| M2-12 Cancellation first | With no active plan, enter `call off my meeting` and `cancel my meeting`. | Neither phrase starts contact lookup or a call; JARVIS answers about cancelling a plan. |
| M2-10 Navigate | Enter `navigate to Dadar station` and follow the Maps app chooser if shown. | An installed Maps-compatible app opens navigation to the requested destination. If no handler exists, JARVIS reports the handoff failure. |

## Regression checklist R1–R9

Run these after the M2 checks. Use disposable test data where applicable.

| Test | Real device steps | Expected result |
|---|---|---|
| R1 — Create meeting | Create a meeting with a known time and venue. | Event is visible in the real Calendar app and Plans; a linked reminder exists. |
| R2 — Cancel from Home | From Home, request cancellation of the active meeting and confirm. | The plan and linked reminder are cancelled and the Calendar event is removed. |
| R3 — Cancel from Plans | Create another disposable meeting. In Plans, cancel it and confirm. | The same cascade occurs; Calendar no longer shows its event. |
| R4 — Alarm | Request an alarm for a specific time and inspect Clock. | The alarm exists at the requested time and is enabled. |
| R5 — Message draft | Request a named-contact draft, edit it, then open Messages and WhatsApp in turn. Do not send. | Each app opens the resolved recipient with the edited text prefilled; neither message is sent automatically. |
| R6 — Contact errors | Try an ambiguous contact name, then a clearly nonexistent name. | Each request stops with a clear ambiguity/not-found response; no handoff occurs. |
| R7 — Automations | Use an available suggested automation and approve it. | Its status changes to approved. If none is suggested, report `not testable—no suggestion available`. |
| R8 — Travel re-check | Create an active meeting plan and tap **Re-check travel now**. | A Decision Trace entry reports the re-check; any estimate is labelled live or estimated accurately. |
| R9 — Airplane mode | Enable Airplane mode, submit a supported request, and inspect the engine label. | Offline fallback is labelled with the provider/network reason and the app remains responsive. Restore connectivity afterward. |

## Report back

For M2-1 through M2-10 and R1–R9, report pass, fail, or not testable and the
observed screen/app result. Do not include API keys, phone numbers, contact
contents, or screenshots containing secrets. Report a YouTube provider result
as status/fallback only. M2 remains **Built (unverified)** until these real-tap
results are reviewed; do not create a verified tag from automated tests.

## Recorded results — agent-driven adb UI run, 2026-10-17

Device `RZCY31G8ZFK` (SM-A266B), APK built from the M2 working tree at the time
of the run. Input was typed into JARVIS's Home field and buttons were tapped via
`adb shell input`; outcomes were read from UI dumps, screenshots, and system
dumps (camera torch log, media/audio volume, Calendar Provider rows). No
function was called directly. Test data was cleaned up afterwards.

| Test | Result | Observed |
|---|---|---|
| M2-1 Timer | Pass | Samsung Clock showed a running `2 m` timer; cancelled afterwards. |
| M2-2 Alarm | Pass | `JARVIS alarm 4:17 am` listed and enabled in Clock; deleted afterwards. |
| M2-3 YouTube, no key | Not run | A key is configured locally; fallback is unit-tested only. |
| M2-4 YouTube, API | Pass | `Opened the selected YouTube video in the YouTube app.`; YouTube's watch activity was in front. |
| M2-5 Music | Partial | Chooser listed Spotify/VLC/YouTube/YT Music; Spotify received the request but sits behind a third-party app lock, so playback was not observed. |
| M2-6 Media keys | Pass (wording) | With nothing playing: `Sent pause, but no app appeared to be playing audio…`; `play`: `…cannot confirm that the app acted.` No audio started (Spotify locked). |
| M2-7 Volume | Pass | Stream 3 went 9/15 → 5/15 for 30%; `101%` rejected with no change; restored to 9. |
| M2-8 Flashlight | Pass | No CAMERA permission in the package; camera service logged torch on, then off, for the JARVIS process. |
| M2-9 Call contact | Partial | `NoSuchContact991` → not found; `Maa` → multiple matches; unique contact → dialog `Call Yusuf Anajwala?`; **Cancel** → `Call cancelled. No call was placed.`; CALL_PHONE was never requested. Tapping **Call** to place a real call is human-only and was not done. |
| M2-10 Navigate | Pass | Chooser → Maps (Just once) → Google Maps `Finding best route…`; Maps stopped afterwards. `geo:` fallback is unit-tested. |
| M2-11 Favorite song | Pass | Unknown → saved → recalled after force-stop → `play` opened the music chooser. |
| M2-12 Cancellation first | Pass | `call off my meeting` → Groq text reply about cancelling; `cancel my meeting` → `There are no active plans to cancel.` |
| R1 | Pass | Groq asked `What time is the meeting?`; `10 AM at college` created the event (Calendar row present) and linked reminder. |
| R2 | Pass | Home cancel dialog → Calendar row `deleted=1`, cascade message shown. |
| R3 | Pass | Plans **Cancel** → plan, event, and reminder cancelled; no active Calendar row. |
| R4 | Pass | Same as M2-2. |
| R5 | Pass | Draft → **Keep editing** → `regression test edited` prefilled in Messages; WhatsApp chat opened with its draft prefilled. Nothing was sent; both composers were cleared. |
| R6 | Pass | `No contact found named NoSuchContact991.` and `More than one contact matched Maa…` |
| R7 | Not testable | No suggested automation; the only rule is already `approved`. |
| R8 | Pass | `Manual travel re-check evaluated 1 active plan(s) and 1 monitoring rule(s)`. |
| R9 | Pass | Airplane mode: `Offline fallback … groq: Network error (UnknownHostException) …`; app responsive; airplane mode restored. |

Outstanding human-only steps before `phase6-m2-verified`: place one real call to
a consenting contact via **Call** (M2-9) and confirm music playback in an
unlocked music app (M2-5).
