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

The online result is labelled `live` when the OSRM request succeeds, and the
offline fallback is labelled `estimated`. The current OSRM adapter uses fixed
demonstration coordinates, so it is not continuous device-GPS traffic
routing. That limitation is documented and must not be presented as real
GPS-driven traffic.

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

No. The current detector is a deterministic, explainable heuristic over the
seeded repeated pattern. It is not a trained model and does not claim to learn
from a user's real history.

## Why OSRM?

OSRM is a free, swappable routing service that fits the zero-cost constraint.
It avoids a required Google billing account and is isolated behind the
TravelContextAgent interface. The public demo endpoint is bounded by network
timeouts and has an estimated fallback.

## What happens offline?

Core local operations continue using Room and the MockReasoningProvider.
Travel requests fall back to an estimated value instead of blocking or
crashing, and the freshness label makes that fallback visible. Cloud
reasoning is not required for the demo.

## What makes this architecture extensible?

The core selects tools through interfaces and agents, while Room entities
persist Plans, dependencies, reminders, monitoring rules, and traces. Travel
is behind a swappable integration interface, and the reasoning provider has
mock and cloud implementations. New agents can therefore be added without
putting their provider or persistence logic into the Home UI.
