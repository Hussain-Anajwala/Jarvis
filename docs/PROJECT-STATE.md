# JARVIS Project State

## Progress (resume here)
- **Branch:** `phase6-llm-assistant`. Remote is untouched (no pushes without explicit permission).
- **Build command:** no Gradle wrapper. Use `~/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle --offline :app:testDebugUnitTest :app:assembleDebug` (AGP 8.6; JDK 22 is the only JDK, and Gradle 8.7 cannot run on JDK 22).
- **Device:** `RZCY31G8ZFK` (SM-A266B) over USB; adb at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. Agent-driven UI checks use `adb shell input` on the real app UI and are labelled "agent" (AGENTS.md).
- **Milestones:**
  - M1 — Verified (agent, 2026-10-17); tags `phase6-m1-built`, `phase6-m1-verified`.
  - M2 — Built; tag `phase6-m2-built`. Device-checked except two human-only steps (see Next user action).
  - M3 — Built; tag `phase6-m3-built`. Notification checks agent-verified (VERIFY-M3.md); eval measured (docs/EVAL.md); Gemini eval blocked by quota.
  - M4 — Built; tag `phase6-m4-built`. Gmail consent done on the phone (then revoked by the Disconnect test); unread/today verified; compose handoff, re-consent, offline pending (VERIFY-M4.md).
  - M5 — Built; tag `phase6-m5-built`. Memory tab, lexical top-3 retrieval, real location travel/weather, habits from real plans (VERIFY-M5.md).
  - M6 — next: scheduled daily briefing (WorkManager) + speech in/out with text alternatives; final eval run.
  - Final hardening — not started.
- **Instrumented tests:** always `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` (a plain connected run uninstalled the app and wiped its data on 2026-10-18).
- **Notification access** is ON for JARVIS on the test phone (user choice). **Location** (while using the app) and **Gmail read-only** consent are granted on the test phone.
- **Providers:** Groq answers live (`openai/gpt-oss-120b`, HTTP 200; falls back to `gpt-oss-20b` under rate limits). Both Gemini keys returned HTTP 429 RESOURCE_EXHAUSTED on 2026-10-18 (quota). Airplane mode gives a labelled offline fallback.

## M2 summary
Tools: `set_timer`, `set_alarm`, `play_youtube`, `play_music`, `media_control`, `set_volume`, `flashlight`, `call_contact`, `navigate`, plus local favorite-song tools (`set_/play_/get_/clear_favorite_song`, SharedPreferences `personal_facts`). Recognised phone commands are parsed locally and never sent to a cloud provider; cancellation phrasing is never a phone command. `ToolRouter` enforces availability, schema validation, and the risk gate; `PhoneActionCoordinator` owns permission and call-confirmation state. Flashlight needs no permission. Media replies never claim playback. Navigation falls back to `geo:`. 51 unit tests pass; debug APK builds. Results per check: docs/VERIFY-M2.md.

## Next user action (only things that need a human)
1. M2-9: in JARVIS, `call <a consenting contact's exact name>` → tap **Call** → allow Phone permission → confirm the call starts, then hang up.
2. M2-5: unlock your music app (Spotify is behind an app lock) and confirm `play music <artist>` starts or searches in it.
Report both results; then `phase6-m2-verified` can be created.
4. Your app lock (AppLock PRO) asks for a fingerprint when JARVIS starts after a reinstall, which blocks agent testing. Unlock JARVIS when asked, or exclude JARVIS from the app lock while testing.
5. M4-6: name a contact that has an email address saved (or test it yourself) to check the draft → mail app handoff; discard the draft, do not send.
3. Gemini evaluation: both Gemini keys are out of free-tier quota (HTTP 429). When quota resets (or after you put a key with quota in `local.properties`), run `JARVIS_EVAL_ENGINE=gemini JARVIS_EVAL_PER_CATEGORY=2` (docs/EVAL.md). No billing is needed or authorized.

## Standing facts and limitations
- The OSRM travel adapter uses a fixed demo route, not device GPS (M5 replaces it). SQLCipher is deferred. Android force-stop removes scheduled WorkManager jobs until relaunch.
- Samsung Clock's confirmation screen after an alarm handoff varies; alarm creation itself was correct in every device test.
- Contact phone numbers, API keys, and `local.properties` values are never recorded in docs or logs.
- Historical per-session detail lives in docs/DECISION-LOG.md.
