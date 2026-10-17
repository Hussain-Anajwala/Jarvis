# M3 notifications + evaluation — verification guide

M3 adds a `NotificationListenerService` that stores message notifications locally (sender, app,
120-character snippet, time; 48-hour retention, 500-record cap), three tools — `count_new_messages`,
`list_new_messages`, `summarize_new_messages` (medium risk, disclosure dialog) — a Notification access card
in Permissions with a Clear data action, and the evaluation harness in `eval/` (see docs/EVAL.md).

Unit tests and a debug build do not prove that Android delivers notifications to the listener. Record the
on-screen result for each step.

## Before testing
1. Install the M3 debug APK. Use real taps (or agent-driven adb UI input, labelled as such).
2. Notification access is a special Android permission granted only in Settings. Do not grant it through
   adb shortcuts (`cmd notification allow_listener`) when recording verification evidence.
3. Test messages: ask someone to send a WhatsApp/SMS, or post a local test notification from a computer:
   `adb shell cmd notification post -S messaging --conversation "Test chat" --message "Asha:See you at 5" jarvis-test 1`
   (it arrives from the Android shell package as a MessagingStyle notification).

## Checks
| Test | Steps | Expected |
|---|---|---|
| M3-1 Access off | With access off, enter `any new messages?` | `Notification access is off…` with where to enable it; no count is invented. |
| M3-2 Grant access | Permissions → Notification access → **Open settings** → enable JARVIS → accept Android's warning → back. | Card shows `Granted` after returning. |
| M3-3 Count | Receive/post a message, then enter `any new messages?` | `You have 1 new message: 1 in <app>.` plus the note that only messages received while access is on are seen. Engine label says Offline parser (answered locally). |
| M3-4 List and mark read | `show my new messages`, then `any new messages?` again | List shows app, sender, time, snippet and `Marked as read in JARVIS.`; the second count says no new messages. |
| M3-5 Filters | Post two messages from different senders; `any new messages from Asha`, `how many whatsapp messages` | Counts reflect the sender/app filter. |
| M3-6 Dismissed = read | Post a message, swipe it away in the shade, then `any new messages?` | It is no longer counted. |
| M3-7 Number masking | Message from an unsaved number (or `--message "+919876543210:hi"`), then `show my new messages` | Sender shows `Unknown number`. |
| M3-8 Summary consent | `summarize my messages` → read the dialog → **Cancel**; repeat → **Summarize** | Dialog names the cloud provider(s) before anything is sent; Cancel says no text was sent. Summarize shows `Summary of N message(s) by <provider/model>` and History shows `sent N message snippet(s) to …`. Offline: `No cloud model was available, so nothing was summarized.` |
| M3-9 Clear data | Permissions → **Clear data** → **Clear** | Stored count becomes 0. |
| M3-10 Revoke | Disable access in Settings, then `any new messages?` | Access-off reply again. |

Regression: R1–R9 from docs/IMPLEMENTATION-PLAN.md.

## Evaluation harness checks (desktop)
| Test | Command | Expected |
|---|---|---|
| E-1 Harness unit tests | `gradle --offline :app:testDebugUnitTest` | EvalHarnessTest passes; LiveEvalTest is skipped. |
| E-2 Offline baseline | `JARVIS_EVAL_ENGINE=offline … --tests com.jarvis.eval.LiveEvalTest --rerun` | Report in `eval/reports/`, numbers equal docs/EVAL.md. |
| E-3 Resume | Re-run E-2 | Items are read from the results file; no change in numbers. |

## Recorded results — agent-driven adb UI run, 2026-10-18

Device `RZCY31G8ZFK`. The user approved enabling Notification access through the Settings UI. Test messages
were posted with `cmd notification post -S messaging` (arguments quoted for the device shell); no real person
was messaged.

| Test | Result | Observed |
|---|---|---|
| M3-1 Access off | Pass | `Notification access is off, so I can't see new messages. Turn it on in Permissions → Notification access…` |
| M3-2 Grant access | Pass | Card → **Open settings** → JARVIS → Android warning → **Allow**; Android listed `com.jarvis/.core.JarvisNotificationListener`; card showed `Granted`. |
| M3-3 Count | Pass | Two posted messages → `You have 2 new messages: 2 in Shell…`; engine label `Offline parser`. |
| M3-4 List and mark read | Pass | List showed app, sender, time, snippet and `Marked as read in JARVIS.`; recount → `No new messages.` |
| M3-5 Filters | Pass | `how many messages from Asha` → 1. |
| M3-6 Dismissed = read | Pass | Swiping the notification away in the shade removed it from the count. |
| M3-7 Number masking | Pass | Sender `+919876543210` listed as `Unknown number`. |
| M3-8 Summary consent | Pass | Dialog named Groq then Gemini before sending; **Cancel** → `Summary cancelled. No message text was sent.`; **Summarize** → `Summary of 2 message(s) by groq/openai/gpt-oss-120b`; History: `sent 2 message snippet(s) to groq/openai/gpt-oss-120b after confirmation`. |
| M3-9 Clear data | Pass | Stored 6 → confirmation → 0. |
| M3-10 Revoke | Not run | The user chose to keep access on. |

Defects found on the device and fixed before the M3 commit:
1. **Pre-grant history was stored.** WhatsApp re-posts a conversation notification with earlier messages, so
   messages from before access was granted entered the store. Capture now keeps a message only if it is newer
   than the capture start (first listener connection after a grant, or the last Clear data) and within 10 minutes
   of the notification's post time. The affected records were purged with Clear data; their content was not
   copied into any file. Re-check: a fresh test message was stored and no WhatsApp history was.
2. A MessagingStyle notification with an empty message list was dropped; it now falls back to title/text.
3. After a summary the engine label kept saying `Offline parser`; it now names the summarizing model.

Known limitation: after JARVIS is updated or reinstalled, Android rebinds the listener after a short delay;
messages that arrive in that gap are not captured.

Regression on the final M3 build (agent-driven, 2026-10-18). The accidental uninstall during the migration test
had reset runtime permissions, so Calendar and Contacts were re-granted through Android's normal prompts.
| Test | Result | Observed |
|---|---|---|
| R1 | Pass | Groq created `Meeting at college` for Mon, Oct 19 11:00 (Calendar row present). |
| R2 | Pass | `call off my meeting` → Groq `calendar.cancel` → dialog → Calendar row `deleted=1`. |
| Negated cancel | Pass | `Do not cancel anything just tell me my plans` → text reply, no cancel dialog. |
| R6 | Pass | `call NoSuchContact991` → `No matching contact was found.`; `message Maa …` → multiple-match dialog. |
| R9 | Pass | Airplane mode → `Offline fallback … UnknownHostException` for all three providers; restored. |
| R3, R4, R5, R7, R8 | Not re-run | Code paths unchanged since the M2 run (VERIFY-M2.md). |
