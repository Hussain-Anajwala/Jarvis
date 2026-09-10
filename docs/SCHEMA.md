**JARVIS**

*Personal Autonomous AI Operating System*

**Backend & Local Data Schema**

Local-first data model (Room/SQLCipher) and the cloud Reasoning Provider
contract

Version 1.0 · Derived from JARVIS Scope v2.0 §11, §12

Author: Hussain Anajwala

Final Year Project — AI/ML (Generative AI Honours), VIT Mumbai

**1. Why “Backend” Means “Local Data Layer” Here**

Scope v2.0 §11 is explicit: there is no centralized JARVIS cloud
database of personal memories. There is therefore no traditional
server-side backend schema for user data. This document instead defines
(1) the on-device Room schema that is the real system of record, and (2)
the stateless request/response contract with the cloud Reasoning
Provider, which persists nothing about the user between calls.

*Note: If a future phase introduces optional cloud sync (explicitly out
of MVP and not currently planned), it must be added as an opt-in,
end-to-end-encrypted addition — not a replacement for local-first
storage.*

**2. Storage Technology**

- Room (SQLite abstraction) for all structured entities below.

- SQLCipher for at-rest encryption of the Room database file.

- Android Keystore for the encryption key itself — never stored in
  plaintext or in source.

- A local embedding index (small vector table or a lightweight on-device
  library) for Memory/Document RAG — keyed back to the Memory/Document
  entities below, not a separate source of truth.

**3. Core Entities (Phase 1 MVP)**

**UserProfile**

Single-row table — this app is single-user in MVP.

|                           |                            |                                           |
|---------------------------|----------------------------|-------------------------------------------|
| **Field**                 | **Type**                   | **Notes**                                 |
| id                        | UUID (PK)                  | Fixed single row in MVP                   |
| display_name              | String                     | Optional, used for greetings              |
| home_location             | String / lat-lon, nullable | Used by §5.4-style travel logic; user-set |
| default_travel_buffer_min | Int                        | Default buffer added to travel estimates  |
| created_at                | Timestamp                  |                                           |

**Plan**

A persistent representation of one user goal and its dependent items
(Scope v2.0 §5.2).

|                         |                                    |                                                    |
|-------------------------|------------------------------------|----------------------------------------------------|
| **Field**               | **Type**                           | **Notes**                                          |
| id                      | UUID (PK)                          |                                                    |
| goal_text               | String                             | Human-readable goal, e.g. “Attend project meeting” |
| status                  | Enum(active, completed, cancelled) |                                                    |
| created_at / updated_at | Timestamp                          |                                                    |
| source_conversation_ref | String, nullable                   | Traceability back to the triggering request        |

**PlanItem**

One dependent item inside a Plan (event, reminder, monitoring rule,
etc.).

|                    |                                                              |                                                              |
|--------------------|--------------------------------------------------------------|--------------------------------------------------------------|
| **Field**          | **Type**                                                     | **Notes**                                                    |
| id                 | UUID (PK)                                                    |                                                              |
| plan_id            | UUID (FK → Plan)                                             | Cascade rule: item deactivates when parent Plan is cancelled |
| item_type          | Enum(calendar_event, reminder, alarm, task, monitoring_rule) |                                                              |
| ref_id             | String, nullable                                             | Points to the concrete row in Event/Reminder/Task below      |
| depends_on_item_id | UUID, nullable (FK → PlanItem)                               | Enables dependency-chain cancellation                        |
| status             | Enum(active, completed, cancelled)                           |                                                              |

**Event**

Local record of a calendar event JARVIS created or is tracking (mirrors
Android Calendar Provider entry).

|                           |                  |                                                     |
|---------------------------|------------------|-----------------------------------------------------|
| **Field**                 | **Type**         | **Notes**                                           |
| id                        | UUID (PK)        |                                                     |
| android_calendar_event_id | Long             | FK into the Android Calendar Provider               |
| title                     | String           |                                                     |
| start_time / end_time     | Timestamp        |                                                     |
| location_text             | String, nullable |                                                     |
| created_by_jarvis         | Boolean          | Distinguishes JARVIS-created vs pre-existing events |

**Reminder**

A scheduled reminder/alarm managed by the Reminder & Alarm Agent.

|                      |                                   |                                      |
|----------------------|-----------------------------------|--------------------------------------|
| **Field**            | **Type**                          | **Notes**                            |
| id                   | UUID (PK)                         |                                      |
| title                | String                            |                                      |
| trigger_time         | Timestamp, nullable               | Null if location-triggered (Phase 3) |
| trigger_location_ref | UUID, nullable                    | FK → geofence definition, Phase 3+   |
| status               | Enum(scheduled, fired, cancelled) |                                      |
| plan_item_id         | UUID, nullable (FK → PlanItem)    |                                      |

**Task**

A to-do item managed by the Task Agent.

|           |                         |           |
|-----------|-------------------------|-----------|
| **Field** | **Type**                | **Notes** |
| id        | UUID (PK)               |           |
| title     | String                  |           |
| due_at    | Timestamp, nullable     |           |
| priority  | Enum(low, medium, high) |           |
| status    | Enum(open, completed)   |           |

**Memory**

A stored personal-context fact (Scope v2.0 §8).

|                           |           |                                               |
|---------------------------|-----------|-----------------------------------------------|
| **Field**                 | **Type**  | **Notes**                                     |
| id                        | UUID (PK) |                                               |
| content_text              | String    | e.g. “Usually needs 45 min to reach college”  |
| embedding_vector          | Float\[\] | Local embedding for retrieval                 |
| source_ref                | String    | Which conversation/plan produced this memory  |
| created_at / last_used_at | Timestamp |                                               |
| user_deletable            | Boolean   | Always true — supports Scope v2.0 §8 “Forget” |

**PermissionGrant**

Local record of what the user has authorized, independent of the raw
Android permission state (used to drive the Permission Center UI
copy/history).

|            |                                                                    |           |
|------------|--------------------------------------------------------------------|-----------|
| **Field**  | **Type**                                                           | **Notes** |
| id         | UUID (PK)                                                          |           |
| capability | Enum(microphone, calendar, location, notifications, contacts, ...) |           |
| status     | Enum(granted, denied, revoked)                                     |           |
| updated_at | Timestamp                                                          |           |

**DecisionTraceEntry**

Immutable, append-only log explaining an autonomous action (Scope v2.0
§7, §9, §16).

|                |                                             |                                                                               |
|----------------|---------------------------------------------|-------------------------------------------------------------------------------|
| **Field**      | **Type**                                    | **Notes**                                                                     |
| id             | UUID (PK)                                   |                                                                               |
| plan_id        | UUID, nullable (FK → Plan)                  |                                                                               |
| action_summary | String                                      | Human-readable, e.g. “Updated departure reminder: travel time rose to 50 min” |
| risk_level     | Enum(low, medium, high)                     |                                                                               |
| tool_name      | String                                      | Which agent/tool executed                                                     |
| outcome        | Enum(executed, confirmed, declined, failed) |                                                                               |
| created_at     | Timestamp                                   |                                                                               |

**4. Additional Entities (Phase 2+)**

**AutomationRule (Phase 2)**

An approved recurring routine detected by the Habit/Routine Agent.

|                       |                                   |                                                            |
|-----------------------|-----------------------------------|------------------------------------------------------------|
| **Field**             | **Type**                          | **Notes**                                                  |
| id                    | UUID (PK)                         |                                                            |
| pattern_description   | String                            | e.g. “Every Monday, remind me to submit the weekly report” |
| status                | Enum(suggested, approved, paused) |                                                            |
| created_from_plan_ids | UUID\[\]                          | Which historical plans the pattern was detected from       |

**MonitoringRule (Phase 2–3)**

A background-evaluated condition tied to a Plan (e.g. travel
monitoring).

|                                 |                                                |                                                  |
|---------------------------------|------------------------------------------------|--------------------------------------------------|
| **Field**                       | **Type**                                       | **Notes**                                        |
| id                              | UUID (PK)                                      |                                                  |
| plan_item_id                    | UUID (FK → PlanItem)                           |                                                  |
| condition_type                  | Enum(travel_time, weather, deadline_proximity) |                                                  |
| last_checked_at / next_check_at | Timestamp                                      |                                                  |
| last_known_value                | String                                         | Used for stale-data labelling per Scope v2.0 §10 |

**DocumentChunk (Phase 4)**

Locally ingested document text chunks for RAG.

|                  |           |                                      |
|------------------|-----------|--------------------------------------|
| **Field**        | **Type**  | **Notes**                            |
| id               | UUID (PK) |                                      |
| document_ref     | String    | Local file reference, never uploaded |
| chunk_text       | String    |                                      |
| embedding_vector | Float\[\] |                                      |

**5. Cloud Reasoning Provider Contract (stateless)**

This is not a database schema — it is the request/response shape sent to
the cloud LLM per call. Nothing here is persisted server-side; the app
is the only system of record.

|                                 |               |                                                                                                                              |
|---------------------------------|---------------|------------------------------------------------------------------------------------------------------------------------------|
| **Field**                       | **Direction** | **Description**                                                                                                              |
| request.user_utterance          | → cloud       | The current turn's text (transcribed if voice).                                                                              |
| request.relevant_context        | → cloud       | A minimal, locally-assembled bundle: retrieved Memory snippets, active Plan summaries, current time — not the full local DB. |
| request.available_tools         | → cloud       | Schema of tools currently permitted (per Permission Layer), so the model can't propose unavailable actions.                  |
| response.plan_steps\[\]         | ← cloud       | Structured multi-step plan; each step maps to a tool_name + parameters.                                                      |
| response.natural_language_reply | ← cloud       | What JARVIS says back to the user.                                                                                           |
| response.confidence             | ← cloud       | Used by Core to decide whether to ask a clarifying question instead of executing (Scope v2.0 §16).                           |

**6. Schema Governance**

Every new entity or field added after MVP goes through the Decision Log
described in the Implementation Plan (§6) — one dated entry naming what
changed and why, so this document (and any coding agent reading it)
never drifts silently out of sync with the actual Room schema in code.
