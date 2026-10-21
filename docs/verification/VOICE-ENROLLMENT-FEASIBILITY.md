# Voice enrollment — feasibility study (2026-10-21)

**Status: feasibility study plus a calibration/test screen. No enrollment is implemented, and JARVIS does not claim to
know your voice.**

## What the detector is
JARVIS uses openWakeWord on the phone (ONNX Runtime): a melspectrogram model, Google's speech-embedding model (one
96-value embedding per 80 ms), and the pre-trained "hey_jarvis" classifier over the last 16 embeddings (trigger 0.50,
2 s cooldown, adaptive quiet gate). It is a *general* keyword model: it decides whether the phrase was said, by anyone.
It has no speaker model and no training step on the phone. Model licence: CC BY-NC-SA 4.0.

Two different problems:
- **Wake-word detection** — was "Hey JARVIS" said? Personalisation would mean raising recall for your voice.
- **Speaker verification** — does the voice resemble an enrolled speaker? This can reduce activations by other people
  or TV, but never improves recall on its own.

## Options assessed
| Option | What it changes | Size / cost on the SM-A266B | Licence | Verdict |
|---|---|---|---|---|
| openWakeWord "custom verifier": logistic regression on the 96-d embeddings of 5–10 of your "Hey JARVIS" clips vs. negative speech | Fewer false activations for others; recall can rise only by lowering the base threshold when the verifier agrees | Under 10 KB of weights; microseconds per check; trained on the phone in well under a second | Same as openWakeWord code (Apache-2.0); uses the existing models | **Recommended next step** |
| Speaker-embedding model (e.g. ECAPA-TDNN, about 6–20 MB ONNX) checked after a detection | Speaker verification only | New download, about 50–100 ms per check | e.g. SpeechBrain models, Apache-2.0 | Not needed for the reported problem |
| Retrain a personal wake-word model | Recall for your voice | Needs a PC training pipeline and many clips | Model licence constraints | Out of scope |

## Why it is not implemented in this change
- The reported problem is recall ("works only occasionally"). There are no measurements yet with your voice: the new
  test screen exists to get them. A verifier can only be validated with your recorded attempts, which could not be
  made in this session (no human speaker); building it without that would be enrollment that cannot be shown to work.
- Lowering the trigger for an enrolled voice trades false activations for recall; that trade has to be measured with
  the same protocol (VERIFY-VOICE.md, Manual 3), before and after.

## What exists now
Settings → "Hey JARVIS" → **Wake-word test and calibration**: the detector's live score (0–1, fires at 0.50), the room
noise floor and quiet-gate threshold, a log of detections (time, score, screen on/off), and counted trials (screen-on and
screen-off attempts, quiet periods for false activations). Counts only; no audio is stored or uploaded.

## Recommended plan (separately approved)
1. Measure a baseline with Manual 3 (at least 20 screen-on, 20 screen-off attempts, 30 quiet minutes).
2. Opt-in enrollment: explain purpose and limits, ask consent, record 8 clips of "Hey JARVIS" and 8 of other phrases
   in a quiet room, check level/clipping for each, keep only the 96-d embedding averages (no audio) in app-private
   storage, with Delete. Train the logistic verifier on the phone.
3. Runtime: base score ≥ 0.50 → wake (as now); 0.30–0.50 and verifier ≥ 0.80 → wake. Log which rule fired.
4. Re-measure with the same protocol; keep it only if recall rises without more false activations. Never use it to
   authorise anything — it is not authentication.
