**JARVIS**

*Personal Autonomous AI Operating System*

**Implementation Plan**

Phase-wise milestones, repo structure, and documentation governance for
multi-agent “vibe coding”

Version 1.0 · Derived from JARVIS Scope v2.0 §18

Author: Hussain Anajwala

Final Year Project — AI/ML (Generative AI Honours), VIT Mumbai

**1. Purpose**

This plan sequences the six project documents into working software, and
— because you'll be building with AI coding agents and may switch
between them mid-project — defines the governance layer that keeps every
agent working from the same understanding of the project, even across
sessions and tools.

**2. Phase-Wise Milestones**

|           |                             |                                        |                                                                                               |
|-----------|-----------------------------|----------------------------------------|-----------------------------------------------------------------------------------------------|
| **Phase** | **Milestone**               | **Primary Docs Referenced**            | **Definition of Done**                                                                        |
| Phase 0   | Feasibility spikes complete | TRD §2, §7                             | Cloud round-trip + local RAG spikes pass; wake-word go/no-go documented                       |
| Phase 1   | JARVIS MVP demo-able        | PRD §4 Phase 1, App Flow §3, Schema §3 | §5.4 travel scenario runs end-to-end on device; all Phase 1 PRD acceptance criteria pass      |
| Phase 2   | Autonomous Plans working    | PRD §4 Phase 2, Schema §4              | 3-step dependent plan cascades correctly; background evaluation verified                      |
| Phase 3   | Context Intelligence live   | PRD §4 Phase 3, App Flow §8            | Live travel-time change triggers correct recalculation and trace entry                        |
| Phase 4   | Advanced JARVIS features    | PRD §4 Phase 4, UI/UX §4.5–4.8         | Vision, Document RAG, Communication and Habit agents each pass their own acceptance test      |
| Phase 5   | Testing & final delivery    | All docs, TRD §6                       | Security/reliability/performance passes complete; all six docs finalized; demo + report ready |

**3. Suggested Milestone Order Inside Phase 1 (MVP)**

Build in this order to keep every milestone independently demoable,
which matters both for your own momentum and for handing context to a
coding agent cleanly:

- Local Data Layer: Room schema from Backend/Local Schema §3, encrypted,
  with seed/test data.

- Permission Layer + Permission Center screen — get real Android
  permission plumbing working early, since everything else depends on
  it.

- ReasoningProvider interface + one working cloud LLM call (reuse the
  Phase 0 spike).

- Calendar Agent + Reminder Agent + Task Agent, each behind the
  Agent↔Core protocol (TRD §4).

- JARVIS Core: intent → tool selection → execution, wired to the three
  agents above.

- Minimal Plan Engine: create a Plan + link dependent PlanItems (no
  background monitoring yet).

- JARVIS Home UI + text/voice input (push-to-talk).

- Decision Trace logging + inline display.

- The full §5.4 travel-reminder demo, wired end-to-end last — it's the
  integration test for everything above.

**4. Recommended Repository Structure**

A structure any coding agent can orient itself in within one read,
without needing prior conversation history:

|                                                                                |                                                                                                                                                                                                       |
|--------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Path**                                                                       | **Contents**                                                                                                                                                                                          |
| /docs/PRD.md, TRD.md, UI-UX.md, APP-FLOW.md, SCHEMA.md, IMPLEMENTATION-PLAN.md | Markdown versions of these six documents — the ones an agent actually reads. Keep the .docx as the human-facing/submission copy and treat Markdown as the source of truth once building starts.       |
| /docs/DECISION-LOG.md                                                          | Append-only dated log of every scope/architecture decision made after this baseline (see §6).                                                                                                         |
| /docs/PROJECT-STATE.md                                                         | One page, always current: which phase/milestone is active, what's done, what's next, any blocking open question.                                                                                      |
| /app                                                                           | Android app module (Kotlin/Compose).                                                                                                                                                                  |
| /app/core                                                                      | JARVIS Core, ReasoningProvider interface + implementations.                                                                                                                                           |
| /app/agents                                                                    | One package per agent (calendar, reminder, task, ...), each implementing the Agent↔Core protocol.                                                                                                     |
| /app/data                                                                      | Room entities/DAOs matching Schema §3–4 exactly.                                                                                                                                                      |
| /app/ui                                                                        | Compose screens, one package per screen from UI/UX §3.                                                                                                                                                |
| CLAUDE.md / AGENTS.md (repo root)                                              | A short pointer file: “Read /docs/PROJECT-STATE.md and /docs/DECISION-LOG.md before making changes.” This is the single line that keeps any agent — Claude Code or otherwise — aligned on first read. |

**5. Keeping Documentation Consistent Across Agent Swaps**

This directly addresses your requirement that progress stay tracked even
if you change coding agents mid-project. The mechanism is deliberately
simple — three small files, kept current, beat one large file nobody
updates:

**PROJECT-STATE.md (rewritten in place, not appended)**

- Current phase and milestone.

- What's been built and verified working (not just “written”).

- What's next, in order.

- Any open question blocking progress.

**DECISION-LOG.md (append-only)**

One dated entry per real decision — a schema change, a swapped library,
a scope cut. Format: date, decision, reason, which doc it affects. This
is what stops a new agent from silently reversing a decision you already
made for a reason it can't see in the code alone.

**Working agreement for whichever agent you use**

- Start every new session/agent by pointing it at CLAUDE.md/AGENTS.md →
  PROJECT-STATE.md → the relevant /docs file for the task at hand.

- End every session by updating PROJECT-STATE.md before stopping — make
  this a hard habit, since it's the entire mechanism.

- Any change that contradicts the PRD/TRD/Schema gets a Decision Log
  entry before the code is written, not after.

*Note: This is effectively a lightweight version of the same idea as
JARVIS's own Decision Trace (TRD §6) — applied to your development
process instead of the app's runtime behavior.*

**6. Schema/Scope Change Process**

- Propose the change in one line in DECISION-LOG.md.

- Update the relevant section of the affected /docs file(s) in the same
  commit.

- Update PROJECT-STATE.md if it changes what's currently in progress.

- Only then implement the code change.

**7. Immediate Next Actions**

- Convert these six .docx files to the /docs Markdown structure in §4.

- Create CLAUDE.md/AGENTS.md and an initial PROJECT-STATE.md (“Phase 0,
  not started”).

- Run the Phase 0 spikes in TRD §2 before writing any MVP feature code —
  they determine whether any TRD assumptions need revisiting.

- Build Phase 1 in the milestone order in §3.
