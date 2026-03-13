# security — Authentication, Encryption, and Delivery Guards

This package handles all security-sensitive concerns: OAuth2 token acquisition and storage, credential encryption, email address validation, delivery rate limiting, spam filtering, bounce handling, suppression list management, DKIM/SPF checking, and the audit trail.

## Classes

| Class | Description |
|---|---|
| `OAuth2Helper` | MSAL4J `PublicClientApplication` wrapper. Executes the interactive browser PKCE flow to acquire an access token. Persists the MSAL token cache to `~/.outlookautoemailier/msal-cache-{type}.json` (where `type` is `source` or `sender`) for silent re-use on subsequent launches. |
| `GoogleOAuth2Helper` | Google PKCE OAuth2 flow for services other than Google Drive (e.g. Sheets). Handles refresh-token auto-restore from disk. |
| `CredentialStore` | Encrypts and decrypts OAuth tokens using AES-256/GCM. Persisted to `~/.outlookautoemailier/credentials.enc`. The encryption key is derived from the machine's hardware identifiers. |
| `RateLimiter` | Token-bucket rate limiter. Default capacity is 100 emails/hour (configurable via `RATE_LIMIT_EMAILS_PER_HOUR`). `acquire()` blocks and injects a jitter delay (`RATE_LIMIT_DELAY_MIN_MS`–`RATE_LIMIT_DELAY_MAX_MS`) between acquisitions. |
| `SpamGuard` | Pre-send content and recipient validation. Checks for missing subject, empty body, invalid recipient count, and heuristic spam-signal patterns in the HTML body. |
| `EmailValidator` | RFC-compliant email address syntax validation. Used by `SpamGuard` and `ContactFetcher` to filter malformed addresses before they reach the SMTP layer. |
| `UnsubscribeManager` | Loads and saves `~/.outlookautoemailier/unsubscribed.txt`. Exposes `isSuppressed(email)` checked by `EmailDispatcher` before every send. Adds and removes entries atomically. |
| `BounceHandler` | Records bounce events (permanent and transient) returned by `SmtpSender`. Permanently bounced addresses are added to the suppression list via `UnsubscribeManager`. |
| `SecurityAuditLog` | Writes JSON Lines entries to `~/.outlookautoemailier/audit.jsonl` for every send, failure, and skip event. **Not yet wired** — the class exists and is instantiated in `AppContext` but is not yet called from `SmtpSender` or `EmailDispatcher`. |
| `DkimSpfChecker` | Performs DNS TXT/MX lookups to verify that the sender domain has DKIM and SPF records configured. Results are logged but **not yet surfaced** in `AccountSetup.fxml`. |

## Key Design Decisions

- **PKCE everywhere** — both Microsoft (MSAL4J) and Google OAuth2 flows use PKCE (`S256` code challenge). No client secrets are stored for the Microsoft flow; Google requires a client secret from the Google Cloud Console.
- **AES-256/GCM for token storage** — `CredentialStore` uses authenticated encryption so that tampered credential files are detected at load time rather than silently producing wrong tokens.
- **Token cache persistence** — MSAL4J's `ITokenCache` serialisation is hooked to write JSON to disk after every token acquisition, enabling silent re-authentication across application restarts.
- **`RateLimiter` is blocking** — `acquire()` sleeps on the calling thread (an `EmailDispatcher` worker thread), which is intentional. The jitter delay mimics human send cadence and reduces the chance of being flagged as a bulk sender.

## Integration Points

- `OAuth2Helper` is called from `AccountSetupController` (interactive login) and `SmtpSender` (token refresh for XOAUTH2).
- `RateLimiter` and `SpamGuard` gate every send inside `EmailDispatcher`.
- `UnsubscribeManager` is checked by `EmailDispatcher` before each send and updated by `BounceHandler` on permanent bounce.
- `SecurityAuditLog` is held in `AppContext`; once wired, `SmtpSender` will call it after each delivery attempt.
