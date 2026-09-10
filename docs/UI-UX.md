**JARVIS**

*Personal Autonomous AI Operating System*

**UI/UX Design Document**

Screens, navigation, design language and phase-wise UI scope

Version 1.0 · Derived from JARVIS Scope v2.0 §13

Author: Hussain Anajwala

Final Year Project — AI/ML (Generative AI Honours), VIT Mumbai

**1. Design Principles**

- Voice-first, but never voice-only — every voice action has a visible,
  tappable equivalent.

- Calm by default — the futuristic aesthetic should not mean constant
  motion or noise; JARVIS stays quiet unless it has something worth
  saying (Scope v2.0 §9).

- Transparency over magic — autonomous actions are always explainable
  one tap away (Decision Trace), never a black box.

- Practical over cinematic — the Iron-Man-HUD inspiration informs the
  Home screen's mood, not the functional screens (Plans, Calendar,
  Permissions), which stay clean and information-dense.

**2. Visual Direction**

|             |                                                                                                                                                                              |
|-------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Element** | **Direction**                                                                                                                                                                |
| Palette     | Deep navy/charcoal base with a single electric-blue accent for active/AI state; status colors (green/amber/red) reserved strictly for risk-level and plan-status indicators. |
| Typography  | A clean geometric sans for UI chrome; a monospace accent used sparingly for the Decision Trace / logs to signal “system truth.”                                              |
| Motion      | Subtle — a breathing/pulse indicator while JARVIS is listening or thinking; no animation on static screens (Plans, Tasks, Settings).                                         |
| Iconography | Line icons, consistent stroke weight; risk level always paired with both color and an icon/text label (never color alone).                                                   |

**3. Screen Inventory (Scope v2.0 §13)**

|                  |                                                                    |                                                |
|------------------|--------------------------------------------------------------------|------------------------------------------------|
| **Screen**       | **Purpose**                                                        | **Phase Introduced**                           |
| JARVIS Home      | Voice-first interaction, current context, active plans at a glance | Phase 1 (MVP)                                  |
| Plans            | View active / completed / cancelled plans and their dependencies   | Phase 1 (minimal) → Phase 2 (full graph)       |
| Calendar         | Events and schedule                                                | Phase 1                                        |
| Tasks            | To-do items, deadlines, priorities                                 | Phase 1                                        |
| Permissions      | Manage capabilities JARVIS can use                                 | Phase 1                                        |
| Memory           | View, explain and delete stored memories                           | Phase 1 (basic list) → Phase 4 (explain view)  |
| Decision History | See why autonomous actions were taken                              | Phase 1 (inline trace) → Phase 2 (full screen) |
| Automations      | Approved recurring routines and monitoring rules                   | Phase 2                                        |
| Settings         | Voice, personality, notification and privacy controls              | Phase 1                                        |

**4. Screen Specifications**

**4.1 JARVIS Home (MVP)**

**Layout**

- Top: current context strip (time, next event, one-line status — e.g.
  “All clear” or “2 plans active”).

- Center: conversation surface — chat bubbles for text, a
  waveform/listening indicator for voice.

- Bottom: push-to-talk mic button (primary), text input field
  (secondary, always available).

- A slide-up panel surfaces the last Decision Trace entry immediately
  after any autonomous action fires.

**Key interaction**

User speaks or types a multi-part request → JARVIS responds
conversationally AND a small “Plan created” chip appears, tappable to
jump straight into the Plans screen for that goal.

**4.2 Plans**

- List grouped by status: Active / Completed / Cancelled.

- Each plan card shows its goal, the dependent items count (e.g. “3
  linked items”), and current status.

- Tapping a plan expands its dependency list (event → reminder →
  monitoring) — this view is the direct UI proof of the §5.4
  travel-reminder scenario.

- Cancel action on a plan requires the medium-risk confirmation dialog
  and shows exactly which dependent items will be deactivated before
  confirming.

**4.3 Calendar / Tasks**

Standard, practical list/agenda views — intentionally the least
“futuristic” screens in the app, since their job is fast scanning, not
ambience. Both support quick-create, and both show a small JARVIS glyph
on any item that was created autonomously, linking to its Decision Trace
entry.

**4.4 Permission Center**

- One row per Android capability JARVIS can use (microphone, calendar,
  location, notifications, etc.).

- Each row: current status (Granted / Not granted / Revoked), a
  plain-language description of what it's used for, and a toggle.

- A persistent, high-contrast “Pause All Automation” control lives at
  the top of this screen and is also reachable from Home (Scope v2.0
  §7.2 Emergency Pause).

**4.5 Memory**

- Simple list of stored memories in plain language (e.g. “Usually needs
  45 min to reach college”).

- Each entry: source (what conversation it came from), an “Explain”
  action (why this is stored / where it's been used), and a Delete
  action.

- A single “Clear all memories” control, behind a high-risk-style
  confirmation.

**4.6 Decision History**

- Reverse-chronological log of autonomous actions.

- Each entry: what happened, which plan it belongs to, the risk level,
  and the reasoning summary (not a raw model dump — a short
  human-readable explanation).

- Filterable by risk level and by plan.

**4.7 Automations (Phase 2)**

Cards for detected-but-not-yet-approved routines (“You do this most
Mondays — automate it?”) separated clearly from already-approved
automations, each individually pausable.

**4.8 Settings**

- Voice selection, JARVIS “personality” tone (if offered), notification
  tiering preferences, and a link back into Permissions and Memory for
  discoverability.

**5. Navigation Model**

Bottom navigation with four primary destinations in MVP: Home, Plans,
Calendar/Tasks (combined tab), and Settings (which surfaces Permissions,
Memory and Decision History as sub-destinations to avoid nav-bar clutter
in v1). Automations is promoted to a fifth primary destination starting
Phase 2 once it has enough standalone content.

**6. Accessibility & Safety-by-Design**

- Risk level is always communicated by icon + text label, never color
  alone.

- Every confirmation dialog for medium/high-risk actions states the
  action in plain language and what happens if declined — no ambiguous
  “Are you sure?”.

- Text sizes respect system accessibility settings; voice interactions
  always have a text/visual fallback.

**7. Phase-Wise UI Scope Summary**

|               |                                                                                                                              |
|---------------|------------------------------------------------------------------------------------------------------------------------------|
| **Phase**     | **UI Focus**                                                                                                                 |
| Phase 1 (MVP) | Home, Plans (minimal), Calendar, Tasks, Permissions, Memory (list), Settings — functional, not yet “futuristic-polished.”    |
| Phase 2       | Full Plans dependency graph view, Decision History screen, Automations screen, notification tiering UI.                      |
| Phase 3       | Context indicators on Home (traffic/weather chips), stale-data labelling UI.                                                 |
| Phase 4       | Full visual polish pass on Home (the cinematic JARVIS aesthetic), Vision/Document intake UI, Communication draft/confirm UI. |
| Phase 5       | Accessibility and usability pass; no new screens.                                                                            |
