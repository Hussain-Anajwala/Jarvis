# M4 Gmail (read-only) — verification guide

M4 adds Gmail access through Google Identity Services `AuthorizationClient` (play-services-auth 21.3.0) with the
`gmail.readonly` scope only, and four tools: `email_unread_count` and `email_today` (low risk, local display),
`summarize_emails` (medium risk, disclosure dialog; sends sender names, subjects, and snippets with addresses
and phone numbers masked — never full bodies), and `compose_email` (medium risk; recipient address resolved
from Contacts on the device, the model sees only the name and the request; an editable draft opens in the mail
app through `mailto:`, and the user taps Send there). The access token lives in memory only; email content is
never written to the database. Permissions → Gmail (read-only) has Connect/Disconnect (Disconnect revokes the
grant at Google and clears Play services' cached token).

Prerequisites already done by the user in Google Cloud: Android OAuth client for `com.jarvis` with the debug
SHA-1, consent screen in Testing with the account as a test user, Gmail API enabled, `gmail.readonly` scope.

## Checks
| Test | Steps | Expected |
|---|---|---|
| M4-1 Consent | Enter `how many unread emails do I have` on a fresh install. | Google shows "Google hasn't verified this app" (Testing) → Continue → account consent listing only "View your email messages and settings" → Continue. JARVIS then shows the unread count. |
| M4-2 Silent reuse | Force-stop JARVIS, enter `check my email`. | Count appears without a consent screen. |
| M4-3 Today's mail | `show todays emails` | `Today's emails:` with `• sender (time): subject — snippet`; no sender addresses shown as names. |
| M4-4 Summary consent | `summarize my emails` → **Cancel**; repeat → **Summarize** | Dialog names the provider(s) and says addresses/bodies are not sent. Cancel: `No email text was sent.` Summarize: `Summary of N email(s) by <provider/model>`; History records what was sent. |
| M4-5 Compose, unknown | `write an email to NoSuchContact991 about the project` | `No email address is saved in Contacts for NoSuchContact991.` |
| M4-6 Compose, known | `write an email to <contact with a saved email> asking for an extension on the report` | Editable draft (To: name and address, subject, body, source label). Edit; **Open mail app** opens the composer prefilled. Nothing is sent until you tap Send there (discard it if testing). |
| M4-7 Disconnect | Permissions → Gmail → **Disconnect**, then `check my email`. | `Gmail disconnected and Google access revoked.`; the next request shows Google's consent again. **Cancel** there → `Gmail connection was cancelled.` |
| M4-8 Offline | Airplane mode, `check my email`. | `Gmail could not be reached.` (no crash). |

Regression: R1–R9 (docs/IMPLEMENTATION-PLAN.md).

## Recorded results — agent-driven adb UI run, 2026-10-18

The user approved tapping through Google's consent screen. Email content seen during the run is not recorded
here; only shapes and outcomes are.

| Test | Result | Observed |
|---|---|---|
| M4-1 Consent | Pass | "Google hasn't verified this app" → Continue → consent for the test account listing only "View your email messages and settings" → Continue → `You have <n> unread emails in your Gmail inbox.` (real count, value not recorded). |
| M4-2 Silent reuse | Pass | After force-stop and reinstall, `check my email` returned the count with no consent screen. |
| M4-3 Today's mail | Pass | One entry in the `• sender (time): subject — snippet` format; the sender field contained no address. The subject/snippet contained an address, which led to the prompt-masking fix below. |
| M4-4 Summary consent | Pass (Cancel only) | Dialog `Summarize 1 email(s)?` named Groq then Gemini and stated addresses/bodies are not sent; **Cancel** → `Summary cancelled. No email text was sent.` **Summarize** with real email was not run, to avoid sending the user's email text to a cloud model during testing. |
| M4-5 Compose, unknown | Pass | `No email address is saved in Contacts for NoSuchContact991.` |
| M4-6 Compose, known | Not run | The tested names had no saved email address; creating a test contact would modify the user's contacts. |
| M4-7 Disconnect | Partial | Disconnect → `Gmail disconnected and Google access revoked.`; card `Not connected`. The next request then failed with `Gmail authorization expired` because Play services kept returning the revoked cached token — **bug, fixed** by clearing the cached token (`GoogleAuthUtil.clearToken`) on 401 and on Disconnect. Re-check blocked: a third-party app lock (fingerprint) covered JARVIS after the reinstall. |
| M4-8 Offline | Not run | Blocked by the same app lock. |

Fixes from this run: summary prompts mask email addresses and phone-like numbers inside subjects/snippets
(`PromptPrivacy`), a quoted sender name that is itself an address becomes `Unknown sender`, and the cached-token
bug above.

Follow-up on the fixed build (2026-10-18): after **Disconnect**, `check my email` showed Google's consent screen again
(the cached-token fix works); the consent then completed without an agent tap (apparently on the phone) and the
count returned. M4-8 Offline: airplane mode → `Gmail could not be reached.`, no crash. The Cancel-on-consent path
remains untested.
