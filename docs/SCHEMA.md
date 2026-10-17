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

### Memory (v1 entity, extended in Room v5 by `MIGRATION_4_5`) — implemented as: id, contentText (the value), type, memoryKey, sourceRef, createdAt, updatedAt, lastUsedAt, userDeletable, embeddingVector (unused; lexical retrieval)
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

### NotificationRecord (M3) — implemented, Room v4 (`notification_records`)
| Field | Type | Notes |
|---|---|---|
| id | String (PK) | SHA-256 of package, sender, snippet, timestamp — re-posted notifications do not duplicate |
| notificationKey | String (indexed) | Android key; used to mark records read when the notification is removed |
| appPackage / appLabel | String | |
| sender | String | display name; raw phone numbers stored as "Unknown number" |
| textSnippet | String | at most 120 characters |
| postedAt | Long (indexed) | |
| read | Boolean | set when listed/summarized in JARVIS or dismissed in the shade |
Only message-like notifications (category `msg`, MessagingStyle, or known messaging apps) are stored, only while
Notification access is granted. Retention: 48 hours and at most 500 rows; "Clear data" deletes all rows.
Migration 3→4 (`MIGRATION_3_4`) only creates this table and its indices; it is tested on-device with Room's
`MigrationTestHelper` against the exported schemas in `app/schemas/`.

### BriefingRecord (M6, optional)
id, generated_at, summary_text, sections_included (JSON).

## Not persisted
Email bodies, full contact data, API keys (kept in local.properties/BuildConfig, never in Room).

## Evaluation dataset (file, not a table)
See docs/EVAL.md for the JSON format.
