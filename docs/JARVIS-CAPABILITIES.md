# What JARVIS can actually do (audit, 2026-10-18)

An evidence-based summary for someone deciding how far to trust JARVIS. It is deliberately not a single
"intelligence score": understanding, acting, and reporting truthfully are measured separately, and each claim
names its evidence. Row-level detail: docs/CAPABILITIES.md; per-check records: docs/VERIFY-*.md.

## 1. Understanding requests (measured)
Tool choice and arguments on a labelled dataset of 126 utterances (docs/EVAL.md; 12 regression items added this
phase for music apps, dates, negation, weather, briefing, email). Tools are stubbed, so this measures
understanding, not execution.

| Engine | What it is | Picks the right action | Right details (when it acts) | Asks when info is missing | Asks when it shouldn't |
|---|---|---|---|---|---|
| App as shipped (hybrid) | phone commands parsed on the phone, everything else to Groq | 97.6% | 97.8% | 100% | 1.1% |
| Groq alone | cloud model only (about half the answers from the smaller gpt-oss-20b under rate limits) | 93.7% | 95.5% | 90.5% | 3.4% |
| Offline only | rule parser, no network | 69.0% | 60.7% | 81.0% | 4.5% |

Latency for the app as shipped: median 0.6 s, 95th percentile 1.4 s. Gemini (the second fallback) is
unmeasured because its free quota was exhausted. Known misses: a negated "don't cancel anything, just tell me my
plans" ran the briefing; one "how many messages" variant listed them instead of counting; one Hinglish meeting
request got a text reply.

What this means in practice: with the cloud model available, JARVIS picks the right action for most clear
requests and asks when time or place is missing. Offline (no network or providers down), it falls back to a rule
parser that handles common phrasings only — roughly two out of three requests.

## 2. Acting on the phone (verified on the test phone unless marked)
| Area | Works | Limits |
|---|---|---|
| Meetings, reminders, cancellation cascade | Yes | Reschedule is not supported (says so) |
| Alarm, timer, flashlight, volume, navigation | Yes | Alarm/timer are Clock handoffs |
| YouTube video, YouTube Music song | Yes; music playback is confirmed from the media session | Needs `YOUTUBE_API_KEY` for exact results |
| Spotify | Opens search only (Free account) | Autoplay needs Premium + App Remote registration |
| Messages / WhatsApp | Prefilled drafts; you press send | Never sends by itself |
| Calls | Confirmation dialog | Placing a real call not yet verified (needs you) |
| New-message notifications | Count / list / opt-in summary | Only messages arriving after access was granted |
| Gmail | Unread count, today's mail, opt-in summary; read-only | Compose handoff not yet verified (needs a contact with an email) |
| Memory | Remember / list / forget / edit; up to 3 relevant memories sent with cloud requests | Word-overlap matching; paraphrases missed |
| Weather, travel time | Yes, from the phone's location | **No live traffic** until a Google Routes or Mapbox key is added; says so |
| Daily briefing | Yes, assembled on the phone | Off by default |

## 3. Voice
- Speaks each finished answer once (phone voice); never reads your words, placeholders, or old messages.
- ElevenLabs voice is built but untested on the phone (no key). Private answers always use the phone voice.
- "Hey JARVIS" (opt-in): runs on the phone, nothing recorded or uploaded, pauses while JARVIS talks or listens.
  Synthetic test clips and a 13-minute no-false-trigger check passed; **a real spoken "Hey JARVIS" has not been
  tested yet**. While on it uses about 3–29% of one CPU core depending on how noisy the room is (median ~14% in a 10-minute test with people around; near zero in a quiet room).
- Push-to-talk starts, times out, and hands the microphone back correctly; recognising real speech needs a person.

## 4. Truthfulness safeguards (tested)
- Music: "playing" only when a media session shows the new track playing.
- Travel: "live traffic" only when a traffic provider answered; otherwise "no traffic data".
- Handoffs (alarm, timer, messages, email) are described as handoffs, not completed actions.
- Cloud fallbacks are labelled with the model that answered; offline answers say they are offline.

## 5. Not possible or not done
- Sending messages/emails or placing calls without your tap (by design).
- Controlling other apps through Accessibility (out of scope).
- Commercial use of the wake-word model (CC BY-NC-SA 4.0).
- Gemini quality is unmeasured (both keys return HTTP 429 quota errors).
