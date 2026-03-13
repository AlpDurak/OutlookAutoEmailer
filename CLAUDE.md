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

**UI navigation:** `MainController` owns 9 `ToggleButton`s in a `ToggleGroup`, each mapped to a content pane wrapped in a `ScrollPane`. The 9 screens are: AccountSetup, ContactList, Compose, TemplateStudio, ImageLibrary, QueueDashboard, DeadLetterExplorer, Analytics, Settings. Programmatic navigation methods: `navigateToQueue()`, `navigateToCompose()`, `navigateToImageLibrary()`, `navigateToAnalytics()`, `navigateToDeadLetters()`.

**ScrollPane wrapping:** `MainController.wrapAllPanesInScrollPanes()` replaces each content pane in the `StackPane` at `initialize()` time with a `ScrollPane` wrapper. Navigation toggles visibility on the wrappers, NOT on the inner panes. Inner panes must always have `visible=true` — setting them invisible causes the white-screen rendering bug (fixed in v1.2.0).

**Email dispatch pipeline:**
1. `ComposeController` builds `EmailJob` objects and enqueues them via `AppContext.get().getEmailQueue().enqueue(job)`.
2. `EmailDispatcher` (2 worker threads) calls `EmailQueue.take()` in a loop and forwards each job to `SmtpSender`.
3. `SmtpSender` authenticates with XOAUTH2 (or password fallback), passes the HTML through `HtmlEmailNormalizer` (jsoup-based CSS inlining + MSO conditionals), appends `EmailFooter`, rewrites links via `LinkTracker`, uploads embedded images via `ImageHostingService`/`ImageCache`, and delivers via Jakarta Mail.
4. `SpamGuard` and `RateLimiter` (token bucket, default 100 emails/hour) gate every send. `RetryPolicy` handles exponential backoff on failure (max 3 attempts, ×2 multiplier). Jobs that exhaust retries get `DEAD_LETTER` status and are available to `DeadLetterExplorerController`.

**Contact fetching:** `ContactFetcher` calls `GraphApiClient` (paginated Microsoft Graph v1.0) using a token from `OAuth2Helper` (MSAL4J interactive browser PKCE flow). Token cache is persisted to `~/.outlookautoemailier/msal-cache-{type}.json`.

**AI template generation:** `GeminiEmailAgent.generateWithLibraryAsync(prompt, imageContext)` posts to `gemini-2.5-flash` REST API. `imageContext` comes from `ImageLibraryStore.buildGeminiContext()`. Returns inner-body HTML only (no `<html>`/`<head>` wrappers). `GeminiEmailAgent.analyzePerformanceAsync(contextJson)` provides campaign performance insights in `AnalyticsController`.

**Google Drive Image Library:** `GoogleDriveService` singleton manages the "OutlookAutoEmailer Images" folder. Uses PKCE OAuth2 on localhost port 8767. Tokens persisted to `~/.outlookautoemailier/google-drive-tokens.json`. All uploaded files are shared "anyone with link can view". `ImageLibraryStore` persists metadata to `~/.outlookautoemailier/image-library.json`.

**Supabase sync:** `SupabaseAnalyticsSync`, `SupabaseContactGroupSync`, `SupabaseTemplateSync`, `SupabaseUnsubscribeSync` all use `SupabaseClient` singleton. All are graceful no-ops when `SUPABASE_SERVICE_ROLE_KEY` is absent from `.env`.

**Persistence (all under `~/.outlookautoemailier/`):**
- `credentials.enc` — AES-256/GCM encrypted OAuth tokens (`CredentialStore`)
- `msal-cache-{source|sender}.json` — MSAL4J token caches
- `google-drive-tokens.json` — Google Drive OAuth2 tokens + cached folder ID
- `templates/*.json` — email templates (Jackson, `TemplateStudioController`)
- `image-cache.json` — MD5-keyed imgbb upload cache (`ImageCache`)
- `image-library.json` — image library metadata (`ImageLibraryStore`)
- `contact-groups.json` — contact group registry (`ContactGroupStore`)
- `audit.jsonl` — JSON Lines audit trail
- `unsubscribed.txt` — suppression list (`UnsubscribeManager`)

**Analytics:** Open-tracking is intentionally removed. `TrackingPixelServer` is a no-op stub. `SentEmailStore` records delivery counts; `BatchStore` holds batch-level aggregations. `AnalyticsController` renders Apache ECharts via JavaFX `WebView` using HTML/JS strings from `EChartsTemplates`. New analytics classes: `ContactReachabilityScorer` (0–100 score per address), `SendTimeAnalyser` (hourly success rates + best-window recommendation), `UnsubscribeAnalyser` (weekly unsubscribe trends + per-campaign rates).

**Sidebar badges:** `MainController.updateQueueBadge(active, failed)` shows a red danger badge when failed > 0. `updateContactsBadge(count)` shows selected recipient count. Both methods are thread-safe (dispatch to JavaFX thread internally).

## Package Map

| Package | Key classes |
|---|---|
| root | `Main`, `AppContext` |
| `api/` | `GraphApiClient`, `ContactFetcher` |
| `analytics/` | `SentEmailRecord`, `SentEmailStore`, `EmailBatch`, `BatchStore`, `LinkClickRecord`, `ContactReachabilityScorer`, `SendTimeAnalyser`, `UnsubscribeAnalyser`, `TrackingPixelServer` |
| `config/` | `AppConfig`, `DotEnvLoader`, `EnvLoader` |
| `integration/` | `GeminiEmailAgent`, `GoogleDriveService`, `SupabaseClient`, `SupabaseAnalyticsSync`, `SupabaseContactGroupSync`, `SupabaseTemplateSync`, `SupabaseUnsubscribeSync` |
| `model/` | `Contact`, `EmailAccount`, `EmailJob`, `EmailTemplate`, `ContactGroup`, `ContactGroupStore`, `ImageLibraryItem`, `ImageLibraryStore` |
| `queue/` | `EmailQueue`, `EmailDispatcher`, `RetryPolicy` |
| `security/` | `OAuth2Helper`, `GoogleOAuth2Helper`, `CredentialStore`, `RateLimiter`, `SpamGuard`, `EmailValidator`, `UnsubscribeManager`, `BounceHandler`, `SecurityAuditLog`, `DkimSpfChecker` |
| `smtp/` | `SmtpConfig`, `SmtpSender`, `HtmlEmailNormalizer`, `ImageHostingService`, `ImageCache`, `EmailFooter`, `LinkTracker` |
| `ui/` | `MainController`, `SplashController`, `AccountSetupController`, `ContactListController`, `ComposeController`, `TemplateStudioController`, `ImageLibraryController`, `ImageLibraryPickerDialog`, `QueueDashboardController`, `DeadLetterExplorerController`, `AnalyticsController`, `SettingsController`, `PreviewModalController`, `EChartsTemplates` |
| `util/` | `StudentEmailParser` |

## FXML Files

All under `src/main/resources/fxml/`: `Splash.fxml`, `MainView.fxml`, `AccountSetup.fxml`, `ContactList.fxml`, `Compose.fxml`, `TemplateStudio.fxml`, `ImageLibrary.fxml`, `QueueDashboard.fxml`, `DeadLetterExplorer.fxml`, `Analytics.fxml`, `Settings.fxml`, `PreviewModal.fxml`.

## Known Gaps (not yet wired)
- `SecurityAuditLog` is created in `AppContext` but not called from `SmtpSender`/`EmailDispatcher` — audit entries are not written.
- `DkimSpfChecker` results are not surfaced in `AccountSetup.fxml`.
- `ComposeController` shows an `INFORMATION` alert after enqueue instead of calling `MainController.navigateToQueue()`.
