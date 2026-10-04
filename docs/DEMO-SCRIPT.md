# JARVIS Demo Script

## Verification status

This script reflects the final continuous, tap-verified behavior on
`RZCY31G8ZFK`. The communication edit path is available: `Keep editing`
retains the draft and opens a prefilled editable field.

## Stable steps

1. Open JARVIS on Home.
2. Enter `I have a meeting tomorrow at 10 AM at college.` and tap **Run request**.
3. Confirm the response shows a meeting for tomorrow at 10:00 AM at college and
   a travel estimate labelled either `live` or `estimated`.
4. Open **Plans**. Confirm the meeting is under **Active**, with two linked
   PlanItems: an active calendar event and an active reminder.
5. Return to Home and tap **Re-check travel now**. Read the Decision Trace for
   the manual evaluation and any live/estimated recalculation result.
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
    Confirm the plan and its linked reminder show cancelled.
