**JARVIS**

*Personal Autonomous AI Operating System*

**Product Requirements Document (PRD)**

Phase-wise product scope, personas, features and acceptance criteria

Version 1.0 · Derived from JARVIS Scope v2.0

Author: Hussain Anajwala

Final Year Project — AI/ML (Generative AI Honours), VIT Mumbai

**1. Purpose of this Document**

This PRD translates the JARVIS Scope & Technical Specification (v2.0)
into buildable, phase-wise product requirements: who it's for, what each
phase ships, and how we'll know each phase is done. It is the source of
truth for “what to build” — the TRD covers “how,” and the Implementation
Plan covers “when/in what order.”

*Note: This PRD assumes the Hybrid AI Decision defined in the TRD (§2):
a cloud LLM drives planning/reasoning for MVP speed and quality, while
on-device components handle memory search, wake-word, and offline
fallback. Every phase below is written against that decision.*

**2. Personas**

|                              |                                                                                                                          |                                                                                         |
|------------------------------|--------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| **Persona**                  | **Description**                                                                                                          | **Primary Need**                                                                        |
| Primary User (You)           | Android power user who wants one assistant to coordinate calendar, tasks, reminders and travel instead of juggling apps. | A single natural-language interface that plans and executes, not just answers.          |
| Evaluator / Reviewer         | Project guide / panel assessing the final-year submission.                                                               | A working, demoable MVP with clear scope boundaries, safety controls and documentation. |
| Future Multi-User (post-MVP) | Out of scope for MVP — single-user, single-device only.                                                                  | N/A for v1.                                                                             |

**3. Product Principles (carried from Scope v2.0)**

- Personal — centered on one user's context and goals.

- Local-first — memory, conversations and tasks stay on-device by
  default.

- Hybrid — cloud is used only where it adds real capability (planning
  quality, live info).

- Autonomous but controlled — three-tier risk policy gates every action.

- Explainable — every autonomous action has a visible reason.

- Offline-capable — core functions degrade gracefully, never break,
  without internet.

- Android-first — built for real phone constraints, not desktop
  assumptions.

**4. Phase-Wise Product Scope**

Each phase below lists: goal, features included, explicit exclusions,
and the acceptance criteria that define “done.” Phases mirror Scope v2.0
§18 exactly so this PRD, the TRD and the Implementation Plan stay in
lockstep.

**Phase 0 — Feasibility & Architecture**

Goal: De-risk the two hardest unknowns (on-device inference feasibility,
Android background/permission constraints) before committing to the MVP
build.

**Included**

- Android proof-of-concept app (empty shell, permission requests
  working).

- Cloud LLM integration spike: one round-trip intent → structured plan
  JSON.

- On-device spike: local embedding model + a 5-document RAG search
  returning correct results.

- Wake-word / background-service feasibility note (battery + Doze impact
  measured on your test device).

- Repository scaffold with the documentation structure defined in the
  Implementation Plan (§6).

**Acceptance Criteria**

- Cloud round-trip produces valid structured JSON for 3 sample requests.

- Local RAG returns the correct chunk for at least 4 of 5 test queries.

- A documented go/no-go on wake-word-while-backgrounded, with a fallback
  (in-app push-to-talk) if not feasible.

**Phase 1 — JARVIS MVP (primary near-term target)**

Goal: Prove the central JARVIS loop end-to-end — natural language in,
coordinated action out, on real Android hardware — using the MVP
boundary from Scope v2.0 §21.

**Included**

- JARVIS Home screen: voice + text input, conversational response.

- JARVIS Core: intent understanding via cloud LLM, tool selection,
  result synthesis.

- Calendar Agent: create / view / cancel events (Android Calendar
  Provider).

- Reminder & Alarm Agent: create / adjust reminders and alarms.

- Task Agent: create, list, complete tasks.

- Local Memory (lightweight): store and recall short user-stated
  preferences (e.g. “I usually need 45 minutes to reach college”).

- Permission Center: request-on-first-use + a settings screen to
  view/revoke.

- Plan Engine (persistent, minimal): a Plan groups one goal's dependent
  actions and can be cancelled as a unit.

- One adaptive-travel demo: meeting created → departure reminder
  scheduled → reminder adjusts if travel time changes (cached/live
  data).

- Decision Trace: a visible “why did JARVIS do this” log entry per
  autonomous action.

- Offline core mode: chat, view existing plans/tasks/calendar and create
  reminders work without internet (cloud-dependent planning gracefully
  degrades to “try again when online”).

**Explicitly excluded from MVP**

- Always-on background wake-word (push-to-talk / foreground voice button
  instead, unless Phase 0 proves otherwise).

- Vision / screenshot understanding.

- Document RAG beyond the Phase 0 spike.

- Communication agent (sending messages/emails).

- Routine/habit learning and proactive automation suggestions.

- Any financial or destructive action.

**Acceptance Criteria**

- The exact §5.4 travel-reminder scenario runs end-to-end on a physical
  device without a crash.

- Cancelling a meeting deactivates its dependent reminder (plan
  dependency proven).

- Every low-risk action executes without confirmation; every medium-risk
  action (e.g. cancel event) prompts for confirmation.

- App functions (chat, view plans/tasks) with airplane mode on; only
  live-travel and cloud planning are unavailable.

- A 5-minute live demo can be given without manual data seeding.

**Phase 2 — Autonomous Plans**

Goal: Make the Plan Engine and monitoring real — plans persist, depend
on each other, and get evaluated in the background, not just at creation
time.

- Full Plan Engine: multi-step plans with explicit dependency graph.

- Background Engine: WorkManager-based periodic plan evaluation.

- Proactive suggestions (opt-in prompt, never silent automation).

- Decision Trace becomes a full browsable Decision History screen.

- Notification tiering (silent / normal / prompt / urgent) per Scope
  v2.0 §9.

**Acceptance Criteria**

- A 3-step dependent plan (event → reminder → monitoring) correctly
  cascades a cancellation.

- Background evaluation runs on schedule and survives app-kill (verified
  via WorkManager logs).

**Phase 3 — Context Intelligence**

Goal: Make plans context-aware using live external signals, with
explicit offline/online fallback.

- Travel/Context Agent: live traffic integration (Google Maps/Directions
  API or equivalent).

- Weather signal integration for relevant plans.

- Location-aware triggers (e.g. “when I get home”) using geofencing.

- Explicit stale-data labelling when falling back to cached info.

**Acceptance Criteria**

- A live travel-time change of ≥ 15 minutes correctly triggers a
  departure-reminder recalculation and is visible in the Decision Trace.

**Phase 4 — Advanced JARVIS**

Goal: Add the differentiating, higher-effort capabilities once the core
loop is proven and stable.

- Vision Agent: screenshot/image/document understanding.

- Document Agent: local document ingestion + RAG at full scale.

- Communication Agent: draft + confirm-to-send messages.

- Habit/Routine Agent: pattern detection → suggested automation.

- Polished, futuristic JARVIS Home UI (per UI/UX Design doc).

- Wake-word revisited if Phase 0 deferred it.

**Phase 5 — Testing & Final Delivery**

- Security testing (permission misuse, data-at-rest checks).

- Reliability testing (tool/API failure injection).

- Performance evaluation on target hardware tier.

- Final documentation pass across all six project documents.

- Demo script + final report.

**5. Non-Goals (all phases)**

Carried directly from Scope v2.0 §4.2 — restated here because they
constrain every phase's acceptance criteria:

- No unrestricted OS control or bypassing Android security.

- No autonomous financial transactions.

- No silent deletion of important user data.

- No training a foundation model from scratch.

- No centralized cloud database of personal memories.

- No iOS version in v1.

**6. Success Metrics (project-level)**

|                       |                                                                        |                                  |
|-----------------------|------------------------------------------------------------------------|----------------------------------|
| **Metric**            | **MVP Target**                                                         | **Measured By**                  |
| Plan completion       | ≥ 90% of the 5 core Phase-1 use cases complete without manual recovery | Manual test script, 10 runs each |
| Permission compliance | 0 unauthorized tool executions                                         | Test scenarios in Phase 5        |
| Offline functionality | Chat + existing-plan viewing fully usable offline                      | Airplane-mode test pass          |
| Response latency      | Perceived response \< 3s for a simple intent on mid-tier hardware      | Manual timing on target device   |
| Decision traceability | 100% of medium/high-risk actions have a Decision Trace entry           | Log audit                        |

**7. Open Product Questions (track in Decision Log)**

- Which cloud LLM provider for Core reasoning in MVP (see TRD §2 for the
  recommendation and trade-offs).

- Minimum supported Android version / RAM tier for the on-device
  components.

- Whether push-to-talk is an acceptable permanent MVP substitute for
  wake-word, or only temporary.
