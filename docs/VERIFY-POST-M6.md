# Post-M6 upgrade — verification record (2026-10-18)

Device `RZCY31G8ZFK` (SM-A266B). "Agent" = adb UI input on the real app. No messages, calls, or emails were sent;
no keys were added; no billing was enabled. Logs record ids, lengths, and timings only.

| # | Check | Result | Evidence |
|---|---|---|---|
| S-1 | Turning "Speak replies" on speaks nothing | Pass (agent) | JarvisSpeech log: no utterance on toggle |
| S-2 | One answer is spoken exactly once; "Heard:" transcript and placeholders are not spoken | Pass (agent) | one `speaking answer <id>` per answer; briefing placeholder not spoken |
| S-3 | Switching tabs / recomposition does not replay | Pass (agent) | no new utterance after tab switches |
| S-4 | Stop speaking interrupts | Pass (agent) | `stop` logged, audio ended |
| S-5 | ElevenLabs voice and fallback | Unit-tested only | `SpeechOutputTest` (MockWebServer: 401, disconnect, empty audio → phone voice). No key configured |
| M-1 | YouTube Music request starts playback and is confirmed | Pass (agent) | reply "Playing “…” in YouTube Music"; trace "playback confirmed"; paused afterwards |
| M-2 | Spotify (Free) request is reported truthfully | Pass (agent) | reply says playback did not start; search opened |
| T-1 | Travel time without a traffic key is labelled as no traffic data | Pass (agent) | "Travel time: 9 min (no traffic data: road-network time via OSRM (no traffic provider configured))" |
| T-2 | Google Routes / Mapbox parsing and delay arithmetic | Unit-tested only | `TrafficRoutingTest` with recorded responses; no key configured |
| W-1 | Wake word OFF by default; switch asks for mic + notification permission | Pass (agent) | switch false on first open; permission dialogs; after grant, switch on |
| W-2 | Foreground service with visible notification and mic capture | Pass (agent) | `dumpsys activity services`: `WakeWordService isForeground=true types=0x80`; notification id 7001; `dumpsys audio`: VOICE_RECOGNITION 16 kHz, pack com.jarvis |
| W-3 | Mic released while JARVIS speaks, resumed afterwards | Pass (agent) | "Test voice": recorder count 0 for ~2 s while speaking, then 1 |
| W-4 | Mic handed to speech recognition on the mic button, resumed afterwards | Pass (agent, after fix) | recorder owner switched to the system recognizer, then back to com.jarvis after "I didn't catch that" |
| W-5 | Real models detect "hey jarvis" once and reject other phrases | Pass (instrumented, phone) | `WakeWordModelTest` (synthetic clips, with and without the quiet gate) |
| W-6 | No false triggers in ordinary room sound | Pass (agent) | 0 `wake word detected` in ~3 min foreground + 10 min background soak |
| W-7 | CPU cost | Measured | thread at ~30% of a core before optimisation; after XNNPACK + quiet gate, model time was 2.5–14% of real time with the app open and 6.5–29% (median ~14%) in the background during the 10-minute soak with frequent room sound (30 × 20 s reports in logcat) |
| W-8 | A person saying "Hey JARVIS" opens listening (foreground) or posts "tap to talk" (background) | **Not verified — needs you** | phone media routes to Bluetooth, so the clip cannot be played into its mic |
| U-1 | All five tabs render in light and dark themes | Pass (agent) | screenshots reviewed (not committed: they showed real plan names) |
| U-2 | Status-bar icons readable in light mode | Pass (agent, after fix) | |

Wake-word state left on the phone: **OFF**; microphone permission returned to its pre-test state (denied).
