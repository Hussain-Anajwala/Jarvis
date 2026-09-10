**JARVIS**

*Personal Autonomous AI Operating System*

**Technical Requirements Document (TRD)**

Architecture, stack decisions, agent protocol and non-functional
requirements

Version 1.0 · Derived from JARVIS Scope v2.0

Author: Hussain Anajwala

Final Year Project — AI/ML (Generative AI Honours), VIT Mumbai

**1. Architecture Overview**

JARVIS Core sits between the Android app surface and a set of specialist
agents/tools. It never exposes agents to the user directly — the user
only ever talks to “JARVIS.”

|                            |                                                                          |                                 |
|----------------------------|--------------------------------------------------------------------------|---------------------------------|
| **Layer**                  | **Responsibility**                                                       | **MVP Status**                  |
| Presentation (Compose)     | Home, Plans, Calendar, Tasks, Memory, Permissions, Settings screens      | Built                           |
| Voice Interface            | STT/TTS, push-to-talk (wake-word deferred pending Phase 0)               | Built (push-to-talk)            |
| JARVIS Core                | Intent understanding, planning, routing, synthesis — calls the cloud LLM | Built                           |
| Plan Engine                | Persistent plans, dependencies, cancellation cascade                     | Minimal in MVP, full in Phase 2 |
| Memory Engine              | Local DB + local embeddings + retrieval                                  | Lightweight in MVP              |
| Permission Layer           | Runtime permission checks + 3-tier risk policy                           | Built                           |
| Agent/Tool Layer           | Calendar, Reminder, Task agents in MVP; more added per phase             | Partial (3 agents)              |
| Background Engine          | WorkManager-based plan evaluation                                        | Minimal in MVP, full in Phase 2 |
| External Integration Layer | Cloud LLM API, travel/weather APIs (Phase 3)                             | Cloud LLM only in MVP           |
| Local Data Layer           | Encrypted local storage (Room + SQLCipher)                               | Built                           |

**2. The Hybrid AI Decision (resolves Scope v2.0 §14's open question)**

Scope v2.0 left the AI runtime as “on-device where feasible” without
committing. That ambiguity blocks both the TRD and the schema, so it's
resolved here:

**Decision: Cloud LLM drives Core reasoning for MVP. On-device handles
memory, wake-word and offline fallback.**

- JARVIS Core (intent understanding, multi-step planning, tool
  selection, synthesis) calls a cloud LLM API through a
  provider-agnostic interface. Default: the Gemini API, which has a
  genuine ongoing free tier (Flash-class models, no card required,
  rate-limited). Claude API is supported as an optional swap-in but is
  not free beyond a one-time trial credit — use it only if you're
  covering the cost.

- On-device: sentence-embedding model for local memory/RAG search,
  STT/TTS, and (Phase 4+) a small quantized fallback model for basic
  offline chat continuity — not full planning.

- This is not a compromise on the privacy principle: only the minimum
  context needed for the current request is sent to the cloud LLM per
  call; nothing is persisted cloud-side; the Local Data Layer remains
  the only durable store.

**Why not fully on-device from day one**

Models small enough to run acceptably on typical phone hardware (roughly
1–4B parameters, quantized) are usable for chat and extraction but are
measurably unreliable at multi-step planning and structured tool-calling
— the exact behavior the MVP needs to demonstrate. Models capable of
good planning (7B+) are slow and battery-heavy on-device today. Real
local-first Android assistants researched for this project (e.g. an
on-device-Gemma/Gemini-Nano app with cloud Gemini Flash as an explicit
opt-in route, and a llama.cpp-based fully-offline chat AAR) confirm this
is the standard trade-off being made in practice, not a shortcut
specific to this project.

**Migration path (not a dead end)**

Phase 3–4 re-evaluate on-device planning as smaller models and phone
NPUs improve. The Core is built against an internal “Reasoning Provider”
interface from day one specifically so a local provider can be swapped
in later without rewriting the Plan Engine or agents.

*Note: If you're benchmarking your own target device in Phase 0 and find
a 7–8B quantized model runs acceptably (via llama.cpp/MediaPipe) on it,
this decision should be revisited — the interface is designed to make
that cheap.*

*Note: Cost check: every other component in this stack (Kotlin, Compose,
Room, Hilt, WorkManager, SQLCipher, Retrofit/OkHttp, the on-device
embedding model) is free and open source. The Gemini free tier keeps the
cloud LLM call free too, within its rate limits — plenty for a
solo-developer MVP and demo. The only stack item to watch is the Phase 3
live-travel integration (§7 risk table): Google's Directions API
requires a billing account on file even though it has a monthly free
quota, so an open routing alternative (e.g. OSRM/OpenStreetMap) is the
zero-cost substitute if that matters to you.*

**3. Android Technology Stack**

|                     |                                                                                        |                                                                                     |
|---------------------|----------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| **Area**            | **Choice**                                                                             | **Rationale**                                                                       |
| Language            | Kotlin                                                                                 | Standard modern Android; coroutines fit the agent/async model                       |
| UI                  | Jetpack Compose (Material 3)                                                           | Faster iteration for a UI-heavy, evolving project                                   |
| DI                  | Hilt                                                                                   | Standard, testable, works cleanly with WorkManager                                  |
| Local DB            | Room + SQLCipher                                                                       | Encrypted local-first storage; matches privacy principle                            |
| Background work     | WorkManager                                                                            | Android-approved scheduling; survives process death                                 |
| Local vector search | Room + a lightweight on-device embedding model (e.g. a MiniLM-class ONNX model)        | Enough for personal-scale memory; avoids a heavy vector-DB dependency on-device     |
| STT                 | Android SpeechRecognizer, or on-device Whisper-family model if offline STT is required | Start with the platform API; upgrade only if offline STT becomes a hard requirement |
| TTS                 | Android TextToSpeech                                                                   | Native, no extra weight                                                             |
| Cloud LLM access    | Single HTTPS client behind a ReasoningProvider interface                               | Provider-agnostic; testable with a mock                                             |
| Networking          | Retrofit/OkHttp                                                                        | Standard, well-supported                                                            |
| Testing             | JUnit + Compose UI test + Robolectric                                                  | Covers agents, ViewModels and screens                                               |

**4. Agent ↔ Core Protocol**

Every specialist agent implements the same contract so JARVIS Core can
route to any of them uniformly, and so new agents (added in later
phases) don't require Core changes.

**Tool-call contract (conceptual, provider-agnostic JSON shape)**

- tool_name: string — unique agent/tool identifier (e.g.
  "calendar.create_event").

- risk_level: enum(low \| medium \| high) — drives the confirmation
  policy.

- parameters: object — tool-specific structured input produced by Core.

- plan_id: string \| null — links this call to a Plan for dependency
  tracking.

- result: { status: success\|failure\|needs_confirmation, data, error? }

- decision_trace_ref: string — id of the Decision Trace entry explaining
  why this call was made.

Core is responsible for populating risk_level from a static
per-tool-action table (see §5), never trusting the cloud LLM's own
judgment of risk — this keeps the safety policy enforceable in code, not
just in the prompt.

**5. Risk-Level Enforcement**

The three-tier policy from Scope v2.0 §7 is enforced in the Permission
Layer, not the LLM:

|          |                                                                             |                                                                   |
|----------|-----------------------------------------------------------------------------|-------------------------------------------------------------------|
| **Risk** | **Enforcement point**                                                       | **MVP examples**                                                  |
| Low      | Auto-execute if permission already granted                                  | Create reminder, create task, create calendar event               |
| Medium   | Core prepares the call, UI requires explicit tap-to-confirm before dispatch | Cancel event, edit existing calendar entry                        |
| High     | Same as medium + a second explicit confirmation dialog; logged distinctly   | Not reachable in MVP scope (no financial/destructive actions yet) |

**6. Non-Functional Requirements**

|                    |                                                                                                                                                                                                                                      |
|--------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Category**       | **Requirement**                                                                                                                                                                                                                      |
| Privacy            | No personal conversation/memory content leaves the device except the minimum text needed for the current cloud LLM call; that call is never logged with PII server-side by design of the request (strip identifiers where feasible). |
| Security           | Local DB encrypted (SQLCipher); no plaintext API keys in source — use Android Keystore-backed storage or BuildConfig injected at build time from a non-committed local.properties.                                                   |
| Offline resilience | Chat (cached context only), Plans/Tasks/Calendar viewing, and reminder creation must work with zero connectivity.                                                                                                                    |
| Battery            | No continuous foreground listening in MVP; background evaluation batched via WorkManager, not polling loops.                                                                                                                         |
| Performance        | Perceived latency \< 3s for a simple intent on a mid-tier device (≥ 4GB RAM) with network available.                                                                                                                                 |
| Auditability       | Every medium/high-risk action produces an immutable Decision Trace entry (append-only table).                                                                                                                                        |
| Testability        | Agents are pure interfaces with mockable Android system dependencies (Calendar Provider, AlarmManager, etc.).                                                                                                                        |

**7. Phase-Wise Technical Scope**

**Phase 0 — Feasibility**

- Spike the ReasoningProvider interface against one cloud LLM; spike
  local embedding search; measure background/wake-word behavior on your
  actual test device.

**Phase 1 — MVP**

- Full stack above minus: full Plan dependency graph, background
  monitoring beyond the one travel demo, wake-word (unless Phase 0
  clears it).

**Phase 2 — Autonomous Plans**

- Full Plan Engine graph model, WorkManager periodic evaluation,
  Decision History screen, notification tiering.

**Phase 3 — Context Intelligence**

- Travel/weather API integration behind the External Integration Layer,
  geofencing for location triggers, stale-data labelling.

**Phase 4 — Advanced JARVIS**

- Vision Agent (on-device or cloud vision model, same ReasoningProvider
  pattern), Document Agent at scale, Communication Agent, Habit/Routine
  Agent, on-device fallback model evaluation.

**Phase 5 — Testing & Delivery**

- Security/reliability/performance test passes against the NFRs in §6;
  finalize all six documents.

**8. Key Technical Risks (TRD-level, complements Scope v2.0 §20)**

|                                                       |                                                                                                                                                        |
|-------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Risk**                                              | **Mitigation**                                                                                                                                         |
| Cloud LLM latency/cost on every turn                  | Cache recent context locally; only send deltas; consider a cheaper/faster model for simple intents, escalate to a stronger one for complex planning.   |
| Provider lock-in                                      | ReasoningProvider interface abstracts the API; swapping providers touches one module.                                                                  |
| Background execution killed by OEM battery optimizers | Document per-OEM guidance; use WorkManager's recommended constraints; provide a user-facing “allow background activity” prompt.                        |
| Schema drift as agents are added across phases        | Backend/Local Schema doc is versioned; every new agent's data needs go through a schema-change entry in the Decision Log (see Implementation Plan §6). |
