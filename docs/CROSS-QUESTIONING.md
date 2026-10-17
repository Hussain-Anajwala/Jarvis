# JARVIS Cross-Questioning Prep

## Why is this an agent and not just a chatbot?

JARVIS routes an intent through a core layer to capability-specific agents.
For example, one meeting request coordinates a calendar event, a departure
reminder, travel monitoring, and a persistent Plan. The response is therefore
an executed, inspectable workflow rather than only generated text.

## Why do you need Decision Trace?

Decision Trace makes autonomous actions inspectable by recording what happened,
which tool ran, the risk level, and the outcome. It is also where travel
recalculation and communication handoff explanations are shown. This gives the
user a reviewable reason instead of an opaque state change.

## Why not send messages automatically?

Communication is medium risk and the app does not send directly. JARVIS shows
a draft first, then hands it to the native Android communication app through an Intent. The user can edit the draft in
JARVIS before handoff and remains responsible for pressing Send in the native
app.

## How are communication recipients resolved?

The mock communication parser separates the recipient name from the message
body, then JARVIS requests Android Contacts permission and queries matching
phone entries. It prefers an exact display-name match and does not pick
arbitrarily when multiple contacts match. The selected number is stripped of
display punctuation, while an existing international `+` or `00` prefix is
preserved/normalized; JARVIS does not invent a country code for a local-only
number.

## Why does the Habit Agent only suggest, not auto-automate?

The suggestion changes an AutomationRule from `suggested` to `approved` or
`paused`, but it does not execute actions automatically. This keeps a
potentially surprising routine under explicit user control. The current UI
offers Approve and Dismiss.

## Is the travel time really live?

Since M5, yes when it says so: JARVIS takes the phone's current location (LocationManagerCompat, with the user's
Location permission), geocodes the meeting place with OpenStreetMap Nominatim, and asks OSRM for the driving time
from that location. Only that path is labelled `live`. Anything else is labelled `estimated:` with the reason —
permission off, location services off, no recent fix, place not found, or a network error. OSRM's public server
uses road distances and speeds, not live traffic, so "live" means "routed now from where you are", not traffic-aware.

## How do you detect when to recalculate a reminder, and how do you avoid duplicate plans?

The background/manual evaluator compares the stored travel estimate for the
existing MonitoringRule and recalculates the linked reminder when the absolute
change reaches 15 minutes. It updates the existing plan's reminder rather than
creating a new plan. The earlier connectivity-toggle test verified this same
plan path with `30 min (estimated)` changing to `11 min (live)`.

## How was travel-time recalculation actually tested?

The verified test created one plan offline, restored connectivity, and tapped
the visible `Re-check travel now` control on that same plan. The trace showed
the 30-to-11-minute change and reminder recalculation, with no duplicate plan.
The current final-review run also confirmed that the manual control is
reachable and does not crash offline, but it did not produce a change because
the cached value was already unchanged.

## Why is Habit Agent data seeded, and how is that disclosed?

The three historical Monday report Plans are demo-only seed data so the
deterministic suggestion is visible on a fresh install. They are marked with
`sourceConversationRef = habit-seed` and are documented as seed data. They
must not be described as the user's real history.

## Is habit detection AI/ML?

No. It is a deterministic, explainable rule: the same plan goal on the same weekday in at least three different
weeks within the last eight weeks becomes a *suggestion* (never an automatic action). Since M5 it runs over the
user's real plans; the first-launch seed is still there for demos and is labelled "Demo (seeded data)".

## Why OSRM?

OSRM is a free, swappable routing service that fits the zero-cost constraint.
It avoids a required Google billing account and is isolated behind the
TravelContextAgent interface. The public demo endpoint is bounded by network
timeouts and has an estimated fallback.

## What happens offline?

The provider chain (Groq → Gemini key 1 → Gemini key 2) fails fast and the local `OfflineRuleProvider` answers,
and the UI label says which providers failed and why (verified in airplane mode). Recognised phone, message,
email, memory, weather, and briefing commands are parsed locally in the first place. Room data stays available;
travel falls back to an `estimated:` value with the reason. Cloud-only features (summaries, LLM email drafts) say
nothing was sent instead of guessing.

## Does JARVIS write to Android Calendar and how does cancellation work?

When READ_CALENDAR and WRITE_CALENDAR are granted, JARVIS selects a writable
email-named calendar first, inserts a CalendarContract event, and stores its
real provider ID in the Room Event mirror. Cancellation deletes that provider
event and cascades the Room Plan and linked reminder. If Calendar access or a
writable account is unavailable, the event stays local and the UI/trace says
so; the no-writable-account branch has not been device-tested because this
test device has writable calendars.

## Does JARVIS schedule alarms itself?

No. A clear `set/create alarm` request is handed to the native Clock app with
the parsed hour, minute, and label using `AlarmClock.ACTION_SET_ALARM`.
JARVIS does not use AlarmManager or exact-alarm scheduling. `Remind me`
phrasing remains in the existing reminder system; on the test phone Clock
displayed the 7:00 AM request after handoff.

## What makes this architecture extensible?

The core selects tools through interfaces and agents, while Room entities
persist Plans, dependencies, reminders, monitoring rules, and traces. Travel
is behind a swappable integration interface, and the reasoning provider has
mock and cloud implementations. New agents can therefore be added without
putting their provider or persistence logic into the Home UI.
