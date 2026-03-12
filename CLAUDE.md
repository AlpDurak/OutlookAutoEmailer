# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
mvn javafx:run                                    # Run the app
mvn test                                          # Run all tests
mvn compile                                       # Check compilation
mvn -Dtest=EmailQueueTest test                    # Run a single test class
mvn clean package -Ppackage-exe -DskipTests       # Build Windows EXE installer
```

Test output goes to `target/surefire-reports/`. EXE installer is written to `target/installer/OutlookAutoEmailer/`.

## Architecture

**Entry point:** `Main.java` shows `Splash.fxml` first, then `MainView.fxml` (1100×700).

**Global wiring:** `AppContext` is a double-checked-locking singleton that holds every backend component and every UI controller. All controllers call `AppContext.get().setXxxController(this)` from their `initialize()` methods. Backend components are created lazily via `AppContext.initBackend(sourceAccount, senderAccount)`, which is triggered by `AccountSetupController.maybeInitBackend()` once both OAuth2 logins complete.

**Configuration:** `AppConfig` is a typed singleton with a 3-tier load order: classpath `application.properties` → `~/.outlookautoemailier/application.properties` (user overrides) → `.env` file at project root (highest priority). Calling `AppConfig.reload()` picks up changes written by `SettingsController`.

**UI navigation:** `MainController` owns 7 `ToggleButton`s in a `ToggleGroup`, each mapped to a content pane. It also exposes `navigateToQueue()` for programmatic navigation.

**Email dispatch pipeline:**
1. `ComposeController` builds `EmailJob` objects and enqueues them via `AppContext.get().getEmailQueue().enqueue(job)`.
2. `EmailDispatcher` (2 worker threads) calls `EmailQueue.take()` in a loop and forwards each job to `SmtpSender`.
3. `SmtpSender` authenticates with XOAUTH2 (or password fallback), passes the HTML through `HtmlEmailNormalizer` (jsoup-based CSS inlining + MSO conditionals), uploads embedded images via `ImageHostingService`/`ImageCache`, and delivers via Jakarta Mail.
4. `SpamGuard` and `RateLimiter` (token bucket, default 100 emails/hour) gate every send. `RetryPolicy` handles exponential backoff on failure (max 3 attempts, ×2 multiplier).

**Contact fetching:** `ContactFetcher` calls `GraphApiClient` (paginated Microsoft Graph v1.0) using a token from `OAuth2Helper` (MSAL4J interactive browser PKCE flow). Token cache is persisted to `~/.outlookautoemailier/msal-cache-{type}.json`.

**Persistence (all under `~/.outlookautoemailier/`):**
- `credentials.enc` — AES-256/GCM encrypted OAuth tokens (`CredentialStore`)
- `templates/*.json` — email templates (Jackson, `TemplateStudioController`)
- `image-cache.json` — MD5-keyed imgbb upload cache (`ImageCache`)
- `audit.jsonl` — JSON Lines audit trail
- `unsubscribed.txt` — suppression list (`UnsubscribeManager`)

**Analytics:** Open-tracking is intentionally removed. `TrackingPixelServer` is a no-op stub. `SentEmailStore` records only delivery counts; `AnalyticsController` shows "Emails Sent" + a 3-column table.

## Known Gaps (not yet wired)
- `SecurityAuditLog` is created but not called from `SmtpSender`/`EmailDispatcher`.
- `DkimSpfChecker` results are not surfaced in `AccountSetup.fxml`.
- `ComposeController` shows an `INFORMATION` alert after enqueue instead of calling `MainController.navigateToQueue()`.
