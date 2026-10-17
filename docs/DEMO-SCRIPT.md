# JARVIS Demo Script

## Verification status

This script contains individually tap-verified, stable steps from
`RZCY31G8ZFK`; the full sequence has not been rerun as one continuous session
after the final Calendar/Clock changes. The communication edit path is
available: `Keep editing` retains the draft and opens a prefilled editable
field.

## Stable steps

1. Open JARVIS on Home.
2. Enter `I have a meeting tomorrow at 10 AM at college.` and tap **Run request**.
3. Confirm the response shows a meeting for tomorrow at 10:00 AM at college,
   a travel estimate labelled either `live` or `estimated`, and successful
   Calendar account write confirmation.
4. Open **Plans**. Confirm the meeting is under **Active**, with two linked
   PlanItems: an active calendar event and an active reminder.
5. Open the device Calendar app and confirm the event appears at the expected
   date/time. Return to Home and tap **Re-check travel now**. Read the Decision
   Trace for the manual evaluation and any live/estimated recalculation result.
6. Enter a communication request using the recipient's full, unique saved
   contact name, for example `Message <contact name> that I will call soon`.
   Grant Contacts access if Android prompts. Confirm the draft identifies the
   resolved recipient before any handoff. A missing or ambiguous name must
   stop with an explicit error.
7. Tap **Keep editing**. Confirm the original text remains visible in the
   `Message` field, append an edit, and tap **Send**.
8. Choose **Messages** in the native chooser. Confirm the edited text is
   prefilled and no automatic send occurs.
9. Open **Automations**. Confirm the demo-only Monday routine suggestion and
   use **Approve** or **Dismiss**; approval changes only the rule status.
10. Open **Plans**, tap **Cancel** on an active plan, then confirm the dialog.
    Confirm the plan and its linked reminder show cancelled; verify the
    corresponding event disappears from Calendar.
11. Enter `Set an alarm for 7 AM`. Confirm the native Clock app opens showing
    the 7:00 AM alarm request. For `Remind me to take medicine at 7`, confirm
    JARVIS uses the reminder response instead of opening Clock.

## v2 assistant steps (agent-checked on `RZCY31G8ZFK`, 2026-10-17/18)

12. `I have a meeting tomorrow` → JARVIS (Groq) asks for the missing details; answer `10 AM at college` → event
    created; the engine label shows the provider/model/HTTP status. Turn on Airplane mode and repeat: the offline
    parser answers and the label says why.
13. Phone tools: `set a timer for 2 minutes`, `turn on flashlight` / `turn flashlight off`, `navigate to Dadar
    station`, `play weather forecast on YouTube`, `call <contact>` → confirmation dialog → **Cancel**.
14. Memory: `remember that Prof Rao is my project guide`, then `who is my project guide`; open the Memory tab to
    show the entry, its source, and when it was last sent to a model.
15. Messages (Notification access on): `any new messages?`, `show my new messages`, `summarize my messages` →
    show the disclosure dialog → **Cancel**.
16. Gmail (connected): `how many unread emails do I have`.
17. Context: `whats the weather`, and a meeting at a nearby place showing `Travel time: N min (live)`.
18. `good morning` for the briefing; Automations → Daily briefing to show the opt-in sections.
19. Evaluation: show docs/EVAL.md (measured offline vs Groq vs app hybrid; Gemini blocked by quota).
