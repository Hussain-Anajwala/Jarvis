# M5 personal memory + real context — verification guide

M5 adds:
- **Personal memory** in Room v5 (`memories` gains `type`, `memoryKey`, `updatedAt` via `MIGRATION_4_5`; existing
  rows are kept as facts). Tools `remember_fact`, `forget_memory`, `list_memories` (parsed locally for common
  phrasing). A **Memory** tab lists every entry with type, source, when it was saved, and when it was last sent
  with a cloud request; Edit, Delete, Add, and Clear all (confirmation). The M2 favorite song moved from
  SharedPreferences into this store on first launch; name and email sign-off feed M4 drafts.
- **Relevant-only context**: for requests that go to a cloud model, at most 3 memories sharing words with the
  request are added to the system prompt (masked for addresses/numbers); the trace records the count, not the text.
- **Real location**: `AndroidLocationSource` (LocationManagerCompat; no Play services location dependency),
  Nominatim geocoding biased near the device, OSRM routing from the device — replaces the fixed demo route.
  Travel is `live` only when routed from a real fix; otherwise `estimated: <reason>`.
- **Weather**: `get_weather` from Open-Meteo at the device location (coordinates rounded to ~1 km).
- **Habits**: suggestions from the user's real plans (same goal, same weekday, ≥3 distinct weeks in 8 weeks);
  the seeded demo rule is labelled "Demo (seeded data)".

## Checks
| Test | Steps | Expected |
|---|---|---|
| M5-1 Location off | Location permission not granted: `whats the weather`; create a meeting with a place. | Weather: `Location permission is off…`. Meeting reply: `Travel time: 30 min (estimated: location permission off)`. |
| M5-2 Grant | Permissions → Location → Grant → Android dialog (precise or approximate) → While using the app. | Card shows Granted. |
| M5-3 Live travel | `I have a meeting tomorrow at 10 AM at <nearby place>` | `Travel time: N min (live)`; cancel the test meeting afterwards. |
| M5-4 Weather | `whats the weather` | `Near you now: …°C, … (Open-Meteo, live)`. |
| M5-5 Remember / recall | `remember that Prof Rao is my project guide`, then `who is my project guide` | Saved reply; the second answer uses the memory; Memory tab shows "Last sent with a request". |
| M5-6 Favorites | `my favorite song is Kesariya`, `what do you remember about me` | List shows `favorite song: Kesariya`. |
| M5-7 Edit / delete | Memory tab → Edit an entry → Save; Delete another; Clear all → confirm. | List updates immediately; recall no longer returns deleted entries. |
| M5-8 Forget by voice/text | `forget the viva room` | `Forgot: "…"` when exactly one memory matches; otherwise asks to use the Memory tab. |
| M5-9 Migration | Install M5 over an M4 install with data. | App opens; existing plans and notification records remain. |

Regression: R1–R9 (docs/IMPLEMENTATION-PLAN.md).

## Recorded results — agent-driven adb UI run, 2026-10-18

| Test | Result | Observed |
|---|---|---|
| M5-1 Location off | Pass | `Location permission is off, so I can't get local weather. Turn on Location in Permissions.`; meeting reply `Travel time: 30 min (estimated: location permission off)`. |
| M5-2 Grant | Pass | Permissions → Location → Android dialog (Precise/Approximate; While using the app / Only this time / Don't allow). Fine and coarse location became granted (the final tap appears to have been made on the phone by the user). |
| M5-3 Live travel | Pass | `Meeting at Dadar railway station … Travel time: 9 min (live)` via Groq; test meetings cancelled, no Calendar rows left. A re-check on an earlier plan ran (`evaluated 1 active plan(s) and 1 monitoring rule(s)`) without a ≥15-minute change. |
| M5-4 Weather | Pass | `Near you now: 28°C, partly cloudy. Today 27°C–33°C, 35% chance of rain. (Open-Meteo, live)`. |
| M5-5 Remember / recall | Pass | `remember that Prof Rao is my project guide` saved; `who is my project guide` → Groq called `list_memories` → `About project guide I remember: • Prof Rao is my project guide`. |
| M5-6 Favorites | Pass (fixed) | The list first showed a bare `Kesariya`; keyed memories now render as `favorite song: Kesariya` (unit-tested, rebuilt). |
| M5-7 Edit / delete | Pass | Final build: Memory tab showed `Last sent with a request: … 1:06 am` on the memory used by Groq; **Edit** → appended text → Save (`Source: edited by user`); **Delete** removed one; **Clear all memories** → high-risk dialog naming the count → Delete all → `No memories yet.` (test memories cleaned up). |
| M5-8 Forget | Not run | Unit-tested (`forget` deletes exactly one match). |
| M5-9 Migration | Pass | Installing the M5 build over the M4 install (DB v4 with plans and notification records) opened without a crash and kept existing plans; `MigrationTest` 4→5 also passes on the device (3/3 instrumented tests). |
