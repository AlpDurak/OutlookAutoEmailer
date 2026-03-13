# Changelog

All notable changes to OutlookAutoEmailer are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [1.2.0] — 2026-03-13

### Added
- **Google Drive Image Library** — `GoogleDriveService` (integration package) manages a dedicated "OutlookAutoEmailer Images" folder in the user's Google Drive with full PKCE OAuth2 authentication, session persistence, and automatic public sharing
- `ImageLibraryItem` and `ImageLibraryStore` (model package) — local metadata store for Drive images with tags, notes, and upload timestamps, persisted to `~/.outlookautoemailier/image-library.json`
- `ImageLibraryController` and `ImageLibrary.fxml` — full JavaFX screen for connecting Drive, uploading images, viewing thumbnails in a `FlowPane` grid, editing tags and notes, and copying URLs
- `ImageLibraryPickerDialog` — modal image picker launched from Template Studio for inline image insertion
- **Gemini AI template generation** — `GeminiEmailAgent` calls Gemini 2.5-flash via REST API; generates inline-CSS table-layout HTML emails from a natural-language prompt with Isik IEEE CS brand guidelines baked into the system prompt
- `GeminiEmailAgent.generateWithLibraryAsync()` — injects image library context so the AI embeds available Drive images in generated templates
- `GeminiEmailAgent.analyzePerformanceAsync()` — submits campaign metrics JSON to Gemini for actionable performance insights displayed in Analytics
- **Supabase cloud sync** — `SupabaseClient`, `SupabaseAnalyticsSync`, `SupabaseContactGroupSync`, `SupabaseTemplateSync`, `SupabaseUnsubscribeSync`; all sync classes are graceful no-ops when `SUPABASE_SERVICE_ROLE_KEY` is absent
- **Dead Letter Explorer** — `DeadLetterExplorerController` and `DeadLetterExplorer.fxml` — browse, filter by failure reason, retry selected jobs, export to CSV, and delete permanently-failed jobs
- `PreviewModalController` and `PreviewModal.fxml` — full-screen `WebView` email preview modal launched from Compose and Template Studio
- **Analytics enhancements** — `ContactReachabilityScorer`, `SendTimeAnalyser`, `UnsubscribeAnalyser` added to `analytics` package; `EChartsTemplates` generates Apache ECharts visualisations for all new metrics
- `EmailFooter` — branded unsubscribe footer automatically appended to all outgoing HTML emails
- `LinkTracker` — rewrites outgoing links for click-through tracking; events stored in `LinkClickRecord` and synced to Supabase `link_clicks` table
- `BatchStore` and `LinkClickRecord` added to `analytics` package
- **Ikonli icons** — `ikonli-javafx` 12.3.1 + `ikonli-fontawesome5-pack` added to `pom.xml`; sidebar and button icons throughout the UI
- `MainController` now manages 9 screens (added Image Library and Dead Letters) with sidebar badges for queue job counts and selected contact counts
- `navigateToImageLibrary()`, `navigateToAnalytics()`, `navigateToDeadLetters()` added to `MainController`
- All content panes wrapped in `ScrollPane` at runtime by `MainController.wrapAllPanesInScrollPanes()` to fix white-screen rendering and enable vertical scrolling

### Changed
- `MainController` sidebar expanded from 7 to 9 `ToggleButton`s
- `Analytics.fxml` root changed from `StackPane` to `VBox` to resolve ECharts timing issues
- Scheduled-send duplication bug fixed in `ComposeController`
- `HtmlEmailNormalizer` now preserves existing inline styles rather than overwriting them

### Fixed
- White screen on navigation — inner panes must remain `visible=true`; visibility is now controlled by `ScrollPane` wrappers
- App crash on Analytics screen caused by incorrect `fx:controller` placement
- Infinite schedule duplication caused by timeline re-registering on every navigation

---

## [1.1.0] — 2025-Q4

### Added
- **Template Studio** — `TemplateStudioController` and `TemplateStudio.fxml` with live `WebView` preview, save/load from `~/.outlookautoemailier/templates/`
- **Contact groups** — `ContactGroup`, `ContactGroupStore` (model), group management in `ContactListController`; persisted to `~/.outlookautoemailier/contact-groups.json`
- **Scheduled sending** — ability to schedule email batches for future delivery via `ComposeController`
- **`HtmlEmailNormalizer`** — jsoup 1.17.2 CSS inlining and MSO conditional comments for cross-client compatibility
- **`ImageHostingService` + `ImageCache`** — imgbb image upload with MD5-based deduplication cache
- **`GoogleOAuth2Helper`** — Google PKCE OAuth2 with refresh token persistence
- **MSAL token cache persistence** — `OAuth2Helper` serialises the MSAL4J token cache to disk for silent re-authentication
- **`package-exe` Maven profile** — jpackage-based Windows EXE packaging with embedded JRE (no WiX required); `package-windows.bat` convenience script
- jsoup 1.17.2 added to `pom.xml`
- `DotEnvLoader` — parses `.env` file at project root for highest-priority configuration

### Changed
- `AppConfig` load order formalised to 3-tier: classpath → user-home → `.env`

---

## [1.0.0] — 2025-Q3

### Added
- Initial release
- **Dual OAuth2 authentication** — independent MSAL4J PKCE flows for source (contacts) and sender accounts
- **Microsoft Graph contact import** — `GraphApiClient` (paginated v1.0) + `ContactFetcher`
- **Email composition** — `ComposeController` builds `EmailJob` objects with personalisation variable substitution
- **Priority queue dispatch** — `EmailQueue` (priority blocking queue), `EmailDispatcher` (2 worker threads), `RetryPolicy` (exponential backoff, max 3 attempts)
- **SMTP delivery with XOAUTH2** — `SmtpSender` via Jakarta Mail 2.0.1
- **Rate limiting** — token-bucket `RateLimiter` (default 100 emails/hour)
- **Spam guard** — `SpamGuard` pre-flight content and recipient validation
- **Suppression list** — `UnsubscribeManager` with `unsubscribed.txt` persistence
- **Secure token storage** — `CredentialStore` with AES-256/GCM encryption
- **Basic analytics** — `SentEmailRecord`, `SentEmailStore`, delivery count display
- **Audit trail** — `SecurityAuditLog` (JSON Lines) — instantiated, wiring pending
- **DKIM/SPF checker** — `DkimSpfChecker` DNS verification — runs, UI surfacing pending
- **Settings screen** — `SettingsController` reads/writes user-home `application.properties`
- **Sidebar navigation** — `MainController` with `ToggleButton` group
- JavaFX 21.0.2, MSAL4J 1.15.1, Microsoft Graph SDK 5.77.0, Jackson 2.16.1, Logback 1.4.14
