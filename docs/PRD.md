# Product Requirements Document (PRD) — v2.0
Supersedes v1.0. Phases 1-5 (Plan Engine, risk-gated actions, Decision Trace, Calendar/Contacts/Clock
integration, communication handoff) are the built FOUNDATION. v2 adds the intelligence layer.

## 1. Vision
A personalized daily assistant for Android that understands natural language through an LLM agent
and acts on the phone's real capabilities through a safety-gated tool layer, remembers the user's
people and preferences, and is evaluated with measured accuracy. Not a "does everything" claim:
a defined set of daily tasks, reliably done.

## 2. Personas
- Primary user: a student/professional who wants one assistant for time, people, information, and phone control.
- Evaluator: needs a working demo, honest limits, and measurable results.

## 3. Capability areas and user stories
| Area | Stories (examples) |
|---|---|
| Time | "Meeting tomorrow at 10 at college" (asks if details are missing); "set a timer for 10 minutes"; "alarm for 7"; departure reminder with real travel time |
| People | "Call Mom" (confirm); "message Yusuf I'll be late" (draft, edit, handoff); contacts resolved locally |
| Information | "Any new messages?"; "check my emails" (unread count, today's mail, summary); morning briefing |
| Phone control | "Open this video on YouTube"; "play my favorite song"; pause/next/volume; flashlight; navigate to a place |
| Personalization | Remembers "Prof. Rao is my project guide", favorites, tone for emails; learns usual buffer times from history |
| Writing | "Write an email to Prof. Rao asking for an extension" with minimal context -> properly formatted editable draft |

## 4. Milestones (see docs/ASSISTANT-ROADMAP.md for detail)
M1 LLM agent loop and clarification · M2 phone tools · M3 notifications + evaluation harness ·
M4 Gmail · M5 personal memory + real location · M6 daily briefing + voice.

## 5. Acceptance principles
- A feature counts only when verified on a physical device with real taps and recorded in docs/CAPABILITIES.md.
- Incomplete requests produce a clarifying question, not a guess.
- Medium/high-risk actions always require explicit confirmation.
- Nothing sends a message or email without the user's final tap.

## 6. Non-goals
Arbitrary cross-app control via Accessibility Service; automated WhatsApp sending; financial actions;
iOS; claiming fully on-device processing; multi-user.

## 7. Success metrics
Measured with docs/EVAL.md on a labelled utterance set: tool-selection accuracy, argument accuracy,
correct-clarification rate, latency p50/p95, comparison of rule baseline vs LLM. Numeric targets are
set after the baseline is measured; only measured values appear in reports.

## 8. Honesty requirements
Every claim in UI, docs, and presentations must match docs/CAPABILITIES.md. Seeded or demo data is labelled as such.
