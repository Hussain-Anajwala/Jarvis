# M1 manual verification (built, unverified)

This guide validates the provider chain and the T1–T6 conversation flow. It is
not evidence that the milestone is verified until a person completes these
steps on the phone and reports the actual results.

## Preconditions

1. Build/install the APK from this commit on the physical Android phone.
2. Confirm the phone has internet access and calendar access.
3. In `local.properties`, configure any provider keys you want tested. Never
   paste a key into chat or a screenshot.
   The latest terminal smoke test returned Groq HTTP 403, Gemini key 1 HTTP
   200, and Gemini key 2 HTTP 503. Repair/replace the Groq key locally before
   expecting Groq to answer; the app falls through to Gemini and then offline.
4. Keep the phone unlocked. Use real taps and text entry; do not use scripted
   input or direct function calls.

## T1–T6: M1 user flow

| Test | Manual steps | Expected result |
|---|---|---|
| T1 — Provider status | Launch JARVIS and inspect the engine label. Submit one simple request such as `What can you do?`; then inspect the label and newest Decision Trace entries. | Before a request the label says `Reasoning providers configured` if at least one key is configured, otherwise it identifies the offline parser. After a live answer, the label identifies the provider, model, and HTTP 200. Trace rows show provider/model/status. If providers fail, the label names the attempted providers/models/statuses and says offline fallback. |
| T2 — Missing meeting details | Enter `I have a meeting tomorrow` and tap Run. Inspect Plans and Calendar without answering yet. | One concise clarification asks for the missing time and venue. No plan/event/reminder is created yet. The engine label identifies the live provider/model if it answered; an offline fallback must be labelled as such. |
| T3 — Meeting follow-up | Reply `10 AM at college`. Inspect Plans, the real Calendar app, and the linked reminder. | A meeting is created for tomorrow at 10 AM at college, with one linked departure reminder. The event title and date are preserved. The Decision Trace records the provider/model/status that handled the request. |
| T4 — Cancellation | Enter `cancel it`, inspect the confirmation dialog, then tap its confirm button. Inspect Calendar and Plans. | The dialog identifies the active meeting. After confirmation, the event disappears from Calendar and the plan and linked reminder are cancelled. |
| T5 — Offline fallback | Enable Airplane mode in Android Settings/Quick Settings. Enter `I have a meeting tomorrow` and tap Run. | JARVIS does not crash, asks one concise clarification, and labels the offline fallback with the network/provider failure reason. Restore connectivity afterward. |
| T6 — Language coverage | With connectivity restored, enter `put a dentist visit on my calendar friday around 6, clinic near Dadar`. If asked, answer `6 PM`. Inspect the created event in Calendar and Plans. | It asks one short AM/PM clarification (not generic help), then creates a Friday 6 PM event titled `Dentist visit` at `clinic near Dadar`, with a linked reminder. A live Gemini/Groq result must show a successful provider/model HTTP status in the label/trace to count as the LLM-path pass. If it falls back to offline rules, report the fallback separately; interaction success alone does not verify the LLM path. |

## Regression checklist R1–R9

| Test | Manual steps | Expected result |
|---|---|---|
| R1 — Create meeting | Create a meeting with a known time and venue. | Event is visible in the real Calendar app and Plans; a linked reminder exists. |
| R2 — Cancel from Home | From Home, request cancellation of the active meeting and confirm. | The plan and linked reminder are cancelled and the Calendar event is removed. |
| R3 — Cancel from Plans | Create another disposable meeting. In Plans, cancel it and confirm. | The same cascade occurs; Calendar no longer shows its event. |
| R4 — Alarm | Request an alarm for a specific time and inspect the Clock app. | The alarm exists at the requested time. |
| R5 — Message draft | Request a named-contact draft, edit it, open Messages and WhatsApp in turn. Do not send. | Each app opens the resolved recipient with the edited text prefilled; neither message is sent automatically. |
| R6 — Contact errors | Try an ambiguous contact name, then a clearly nonexistent name. | Each request stops with a clear ambiguity/not-found message; no handoff occurs. |
| R7 — Automations | Use an available suggested automation and approve it. | Its status changes to approved. If there is no suggested automation, record `not testable—no suggestion available`; do not approve an unrelated rule. |
| R8 — Travel re-check | Create an active meeting plan and tap `Re-check travel now`. | A Decision Trace entry reports the re-check; any estimate is labelled live or estimated accurately. |
| R9 — Airplane mode | Enable Airplane mode, submit a supported request, and inspect the label. | Offline fallback is labelled with the failure reason and the app remains responsive. Restore connectivity afterward. |

## Report back

For each test, report pass/fail/not-testable and the observed screen result.
For the provider check, report provider/model/status only, plus sanitized
reasons. Never include API keys, phone numbers, email addresses, or contact
contents. Do not mark this guide complete from build or unit-test results alone.

## Recorded results — agent-driven adb UI run, 2026-10-17

Device `RZCY31G8ZFK`; input typed into the Home field and buttons tapped via
`adb shell input`; outcomes read from UI dumps and Calendar Provider rows.
Includes the shared date-aware system prompt added in the M2 commit.

| Test | Result | Observed |
|---|---|---|
| T1 | Pass | Label before a request: `Reasoning providers configured`; after: `groq (openai/gpt-oss-120b; HTTP 200)`. |
| T2 | Pass (deviation) | `I have a meeting tomorrow` → `What time is the meeting?`; no plan created. It asked for time only, not time and venue. |
| T3 | Pass | `10 AM at college` → `Meeting at college created for Sun, Oct 18 at 10:00 am …`; Calendar row present; linked reminder active. |
| T4 | Pass | `cancel my meeting` → dialog → **Cancel meeting** → Calendar row `deleted=1`. |
| T5 | Pass | Airplane mode → offline clarification labelled `Offline fallback … UnknownHostException`. |
| T6 | Pass (deviation) | Groq asked `What exact time (hour and minute) would you like for the dentist visit?` (not AM/PM-specific); `6 PM` → `Dentist visit created for Fri, Oct 23 at 6:00 pm at clinic near Dadar` via Groq HTTP 200. Before the title rule fix the title was `Meeting at clinic near Dadar`; fixed and re-run. Event cancelled afterwards. |
| R1–R9 | See [VERIFY-M2.md](./VERIFY-M2.md) | All pass except R7 not testable (no suggestion available). |
