**JARVIS**

*Personal Autonomous AI Operating System*

**App Flow Document**

Step-by-step flows for the core JARVIS scenarios, phase-wise

Version 1.0 · Derived from JARVIS Scope v2.0 §5, §15

Author: Hussain Anajwala

Final Year Project — AI/ML (Generative AI Honours), VIT Mumbai

**1. How to Read These Flows**

Each flow lists the actor (User, JARVIS Core, a specific Agent, or the
OS/Android system) and the action at that step. Flows marked (MVP) must
work for Phase 1 acceptance; others are introduced in later phases as
noted.

**2. Flow: First Launch & Onboarding (MVP)**

|          |           |                                                                                                                               |
|----------|-----------|-------------------------------------------------------------------------------------------------------------------------------|
| **Step** | **Actor** | **Action**                                                                                                                    |
| 1        | User      | Opens JARVIS for the first time.                                                                                              |
| 2        | JARVIS    | Explains, in one short screen, the local-first privacy model and the three-tier autonomy policy.                              |
| 3        | OS        | Requests calendar, notification and microphone permissions one at a time, each with a plain-language reason shown beforehand. |
| 4        | User      | Grants or defers each permission individually.                                                                                |
| 5        | JARVIS    | Lands on Home with a short prompt: “Try asking me to remind you of something.”                                                |

**3. Flow: Meeting Management — Adaptive Travel Reminder (MVP, Scope
v2.0 §5.4)**

This is the flagship MVP demo flow — it is the primary Phase 1
acceptance scenario.

|          |                      |                                                                                                                   |
|----------|----------------------|-------------------------------------------------------------------------------------------------------------------|
| **Step** | **Actor**            | **Action**                                                                                                        |
| 1        | User                 | “I have a meeting tomorrow at 10 AM at college.”                                                                  |
| 2        | JARVIS Core          | Parses intent → identifies this as one goal with dependent sub-actions, not an isolated command.                  |
| 3        | Calendar Agent       | Creates the 10:00 AM event (low risk — auto-executes).                                                            |
| 4        | Travel/Context Agent | Estimates normal travel time (e.g. 30 min) and applies the user's preferred buffer (e.g. 15 min).                 |
| 5        | Plan Engine          | Creates a Plan linking: event → departure reminder → travel monitoring, all tagged to one plan_id.                |
| 6        | Reminder Agent       | Schedules the departure reminder (low risk — auto-executes).                                                      |
| 7        | Background Engine    | Periodically re-checks travel conditions as departure approaches (Phase 1: on a coarse schedule; Phase 3: live).  |
| 8        | Travel/Context Agent | Detects travel time has changed from 30 to 50 minutes.                                                            |
| 9        | Plan Engine          | Recalculates the required departure time and updates the linked reminder.                                         |
| 10       | JARVIS Core          | Writes a Decision Trace entry explaining the change and (per notification tiering) notifies the user.             |
| 11       | User                 | (Optional) Cancels the meeting.                                                                                   |
| 12       | Plan Engine          | Identifies dependent items in the plan and deactivates the departure reminder and travel monitoring as a cascade. |

**4. Flow: Permission-Gated Action (MVP)**

|          |                  |                                                                                                                                         |
|----------|------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| **Step** | **Actor**        | **Action**                                                                                                                              |
| 1        | User             | Asks JARVIS to do something requiring a capability not yet granted (e.g. location-based trigger before location permission is granted). |
| 2        | JARVIS Core      | Checks the Permission Layer before dispatching the tool call.                                                                           |
| 3        | Permission Layer | Reports the capability is unavailable.                                                                                                  |
| 4        | JARVIS           | Explains what's needed and why, and offers a direct link to grant it — never silently fails or silently proceeds.                       |
| 5        | User             | Grants or declines.                                                                                                                     |
| 6        | JARVIS Core      | Resumes the original plan if granted; otherwise offers the closest available alternative or asks for a different approach.              |

**5. Flow: Medium/High-Risk Confirmation (MVP)**

|          |                  |                                                                                                                                                            |
|----------|------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Step** | **Actor**        | **Action**                                                                                                                                                 |
| 1        | JARVIS Core      | Determines a requested action (e.g. cancel a calendar event) is medium risk per the static risk table.                                                     |
| 2        | JARVIS Core      | Prepares the action but does not dispatch it.                                                                                                              |
| 3        | UI               | Shows a plain-language confirmation dialog naming the exact action and its downstream effects (e.g. “This will also cancel your 9:30 departure reminder”). |
| 4        | User             | Confirms or declines.                                                                                                                                      |
| 5        | Agent/Tool Layer | Executes only on confirmation.                                                                                                                             |
| 6        | JARVIS Core      | Logs the outcome (including a decline) to the Decision Trace.                                                                                              |

**6. Flow: Offline Fallback (MVP)**

|          |             |                                                                                                                                                                      |
|----------|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Step** | **Actor**   | **Action**                                                                                                                                                           |
| 1        | OS          | Reports no network connectivity.                                                                                                                                     |
| 2        | JARVIS Core | Detects the current request needs the cloud Reasoning Provider (complex planning) and cannot be served locally.                                                      |
| 3        | JARVIS      | Tells the user plainly that planning is temporarily limited offline, and offers what it can still do (view existing plans/tasks/calendar, create a simple reminder). |
| 4        | User        | Proceeds with an offline-capable action, or waits.                                                                                                                   |
| 5        | JARVIS Core | Automatically resumes full capability once connectivity returns, with no separate user action required.                                                              |

**7. Flow: Routine Detection → Suggested Automation (Phase 2)**

|          |                     |                                                                                               |
|----------|---------------------|-----------------------------------------------------------------------------------------------|
| **Step** | **Actor**           | **Action**                                                                                    |
| 1        | Habit/Routine Agent | Detects a repeated pattern across plan history (e.g. the same reminder created every Monday). |
| 2        | JARVIS              | Surfaces a suggestion card on the Automations screen — never creates the automation itself.   |
| 3        | User                | Approves, edits, or dismisses the suggestion.                                                 |
| 4        | Background Engine   | Only begins executing the automation after explicit approval.                                 |

**8. Flow: Location-Triggered Task (Phase 3)**

|          |                 |                                                                                         |
|----------|-----------------|-----------------------------------------------------------------------------------------|
| **Step** | **Actor**       | **Action**                                                                              |
| 1        | User            | “Remind me when I get home to call Dad.”                                                |
| 2        | JARVIS Core     | Creates a location-conditioned Plan instead of a time-conditioned one.                  |
| 3        | OS (Geofencing) | Registers a geofence trigger for “home” (requires location permission already granted). |
| 4        | OS              | Fires the geofence trigger on arrival.                                                  |
| 5        | Reminder Agent  | Surfaces the reminder at that moment.                                                   |

**9. Flow: Document Intelligence (Phase 4)**

|          |                |                                                                                                                             |
|----------|----------------|-----------------------------------------------------------------------------------------------------------------------------|
| **Step** | **Actor**      | **Action**                                                                                                                  |
| 1        | User           | Shares a local PDF and asks for key deadlines.                                                                              |
| 2        | Document Agent | Ingests and chunks the document locally.                                                                                    |
| 3        | Memory Engine  | Runs local embedding + retrieval to find relevant sections.                                                                 |
| 4        | JARVIS Core    | Sends only the retrieved relevant chunks (not the full document) to the cloud Reasoning Provider for structured extraction. |
| 5        | JARVIS         | Returns a structured list of deadlines, each traceable back to its source section.                                          |

**10. Phase-Wise Flow Summary**

|               |                                                                                                                       |
|---------------|-----------------------------------------------------------------------------------------------------------------------|
| **Phase**     | **Flows Active**                                                                                                      |
| Phase 1 (MVP) | Onboarding, Meeting Management/Travel Reminder, Permission-Gated Action, Risk Confirmation, Offline Fallback.         |
| Phase 2       | Adds Routine Detection → Suggested Automation; Plan cancellation cascade generalized beyond the single MVP demo case. |
| Phase 3       | Adds Location-Triggered Task; Travel/weather flows move from cached to live.                                          |
| Phase 4       | Adds Document Intelligence, Vision understanding, Communication draft/confirm.                                        |
| Phase 5       | No new flows — all existing flows exercised under failure-injection testing.                                          |
