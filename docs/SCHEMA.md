# Local Data Schema — v2.0
Supersedes v1.0. All user data is on-device (Room). Retained from v1: UserProfile, Plan, PlanItem,
PlanItemDependency, Event, Reminder, Task, PermissionGrant, AutomationRule, MonitoringRule.
Every change ships with a real Room migration (no destructive migration) and a DECISION-LOG entry.

## Changed entities
### DecisionTraceEntry (add columns)
| Field | Type | Notes |
|---|---|---|
| engine | Enum(gemini, offline_rule, user) | Which engine decided |
| llm_iterations | Int, nullable | Loop iterations used |
| tool_name | String | existing |

### Memory (v1 entity, extended; becomes the personal memory store in M5)
| Field | Type | Notes |
|---|---|---|
| id | String (PK) | |
| type | Enum(fact, relationship, preference, favorite) | e.g. favorite_song |
| key | String | e.g. "favorite_song", "Prof. Rao" |
| value | String | e.g. "<song title>", "project guide" |
| embedding | BLOB, nullable | For retrieval (M5) |
| source | String | Conversation or manual entry |
| created_at / updated_at | Long | |
Always user-visible, editable, and deletable. M2 uses it as a simple key-value store before embeddings.

## New entities
### ConversationTurn (short-term context)
id, role(user|assistant|tool), content, tool_name?, created_at. Pruned to a retention limit.

### NotificationRecord (M3)
id, app_package, sender, text_snippet (short), posted_at, read(Boolean). Retention-limited with a user "clear data" action.
Created only while Notification access is granted.

### BriefingRecord (M6, optional)
id, generated_at, summary_text, sections_included (JSON).

## Not persisted
Email bodies, full contact data, API keys (kept in local.properties/BuildConfig, never in Room).

## Evaluation dataset (file, not a table)
See docs/EVAL.md for the JSON format.
