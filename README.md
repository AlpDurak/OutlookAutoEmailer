# OutlookAutoEmailer

> A Java 17 + JavaFX 21 desktop application for Microsoft 365 mass emailing — authenticate two accounts via OAuth2, import contacts from Microsoft Graph, compose AI-assisted HTML emails, and deliver them at scale with a priority queue, rate limiting, and Supabase cloud analytics.

![Java](https://img.shields.io/badge/Java-17-blue?logo=openjdk)
![JavaFX](https://img.shields.io/badge/JavaFX-21.0.2-orange)
![Maven](https://img.shields.io/badge/build-Maven-red?logo=apachemaven)
![Version](https://img.shields.io/badge/version-1.2.0-green)

---

## Table of Contents

1. [Overview](#overview)
2. [Features](#features)
3. [Prerequisites](#prerequisites)
4. [Quick Start](#quick-start)
5. [Configuration](#configuration)
6. [Project Structure](#project-structure)
7. [Architecture Overview](#architecture-overview)
8. [Building a Windows EXE](#building-a-windows-exe)
9. [Running Tests](#running-tests)
10. [Known Limitations](#known-limitations)
11. [Screenshots](#screenshots)

---

## Overview

OutlookAutoEmailer is a Windows desktop tool for sending personalised mass emails from a Microsoft 365 account. It uses **two separate Microsoft accounts**:

- **Source account** — read-only access to Outlook contacts via Microsoft Graph API
- **Sender account** — SMTP delivery via Office 365 SMTP relay (XOAUTH2 bearer token)

Both accounts authenticate independently using MSAL4J PKCE OAuth2 flows (no passwords stored). Email templates are composed in a rich Template Studio with an optional **Google Gemini 2.5-flash AI assistant** that generates HTML email bodies from a natural-language prompt. Sends are dispatched through a thread-safe priority queue with token-bucket rate limiting, exponential-backoff retry, and spam guard validation. Delivery analytics are persisted locally and optionally synced to a **Supabase** cloud backend.

---

## Features

### Authentication and Accounts
- **Dual OAuth2 authentication** — independent MSAL4J PKCE flows for source and sender accounts; tokens cached to `~/.outlookautoemailier/msal-cache-{type}.json`
- **Microsoft Graph contact import** — paginated retrieval from Outlook contacts (`GraphApiClient`, `ContactFetcher`); supports contact groups with local and Supabase persistence
- **SMTP delivery with XOAUTH2** — Office 365 SMTP relay using bearer tokens; password fallback available

### Email Composition
- **AI-powered template generation** — `GeminiEmailAgent` calls Gemini 2.5-flash with a structured RAG system prompt to produce inline-CSS, table-layout HTML email bodies
- **IEEE-branded generation** — system prompt encodes Isik University IEEE CS brand colours (IEEE Blue `#00629B`, Gold `#F7A800`) and layout rules
- **Image Library** — upload images to Google Drive (`GoogleDriveService`), tag and annotate them, and inject them as context so Gemini embeds them in generated templates
- **Template Studio** — full FXML editor with live WebView preview; templates saved as JSON to `~/.outlookautoemailier/templates/`
- **Personalisation variables** — `{{firstName}}`, `{{lastName}}`, `{{email}}`, `{{company}}`, `{{jobTitle}}` are substituted per recipient

### Email Delivery Pipeline
- **HTML email normalisation** — `HtmlEmailNormalizer` (jsoup 1.17.2) inlines CSS rules, adds MSO conditional comments for Outlook rendering compatibility
- **Image hosting with cache** — `ImageHostingService` uploads embedded images to imgbb; `ImageCache` deduplicates uploads using MD5 hashing, persisted to `image-cache.json`
- **Unsubscribe footer** — `EmailFooter` appends a branded unsubscribe footer to every outgoing HTML email
- **Link tracking** — `LinkTracker` rewrites links for click-through tracking
- **Email queue with priority and retry** — `EmailQueue` (thread-safe priority blocking queue), `EmailDispatcher` (2 worker threads), `RetryPolicy` (up to 3 attempts, x2 exponential backoff)
- **Rate limiting and spam guard** — token-bucket `RateLimiter` (default 100 emails/hour, configurable jitter delay), `SpamGuard` pre-flight checks
- **Suppression list** — `UnsubscribeManager` maintains `unsubscribed.txt`; suppressed addresses are silently skipped before every send

### Monitoring and Analytics
- **Queue Dashboard** — real-time queue status with job list, active/failed counts, and sidebar badge
- **Dead Letter Explorer** — browse, filter, retry, and export emails that exhausted all retry attempts
- **Analytics dashboard** — Apache ECharts charts rendered in JavaFX WebView; batch-level stats with send time optimisation recommendations and contact reachability scoring
- **Supabase cloud sync** — `SupabaseAnalyticsSync`, `SupabaseContactGroupSync`, `SupabaseTemplateSync`, `SupabaseUnsubscribeSync` push/pull data on a fire-and-forget basis; no-ops gracefully when `SUPABASE_SERVICE_ROLE_KEY` is absent
- **Audit trail** — `SecurityAuditLog` writes JSON Lines to `audit.jsonl`

### Infrastructure
- **Secure credential storage** — `CredentialStore` encrypts OAuth tokens with AES-256/GCM
- **DKIM/SPF checking** — `DkimSpfChecker` performs DNS-based domain verification
- **Bounce handling** — `BounceHandler` records and reacts to delivery bounce events
- **Windows EXE packaging** — `jpackage` Maven profile bundles a self-contained EXE (no JRE required on target machine)

---

## Prerequisites

| Requirement | Notes |
|---|---|
| Java 17+ | JDK 17 or later must be on `PATH` |
| Maven 3.8+ | Used for build and dependency management |
| Microsoft 365 accounts | One for contacts (source), one for sending (sender) |
| Azure AD app registration | See [Azure setup](#azure-ad-app-registration) below |
| imgbb account | Free API key from [imgbb.com](https://imgbb.com) for image hosting |
| Google Cloud project | Optional — required only for Gemini AI and Google Drive image library |
| Supabase project | Optional — required only for cloud analytics sync |

### Azure AD App Registration

1. Go to [portal.azure.com](https://portal.azure.com) and open **App registrations**.
2. Create a new registration with a **public client** redirect URI of `http://localhost`.
3. Under **API permissions**, add:
   - `Contacts.Read` (Microsoft Graph, delegated)
   - `Mail.Send` (Microsoft Graph, delegated)
   - `User.Read` (Microsoft Graph, delegated)
4. Copy the **Application (client) ID** and **Directory (tenant) ID** into your `.env` file.

---

## Quick Start

```bash
# 1. Clone the repository
git clone https://github.com/AlpDurak/OutlookAutoEmailer.git
cd OutlookAutoEmailer

# 2. Copy the example environment file and fill in your credentials
cp .env.example .env
# Edit .env with your Azure client ID, tenant ID, and optional API keys

# 3. Run the application
mvn javafx:run
```

On first launch the app shows an Account Setup screen. Click **Sign in** for each account — a browser window will open for the interactive OAuth2 flow. After both accounts are authenticated the full UI becomes available.

---

## Configuration

### `.env` file (project root — highest priority)

Copy `.env.example` to `.env` and supply the required values. The `.env` file takes precedence over all other configuration sources.

```dotenv
# Required — Azure App Registration (portal.azure.com)
AZURE_CLIENT_ID=your-azure-client-id-here
AZURE_TENANT_ID=your-azure-tenant-id-here

# Required for image embedding in emails
IMGBB_API_KEY=your_imgbb_key_here

# Optional — Google (Drive image library + Gemini AI template generation)
GOOGLE_CLIENT_ID=your_google_client_id_here
GOOGLE_CLIENT_SECRET=your_google_client_secret_here
GEMINI_API_KEY=your_gemini_api_key_here

# Optional — Supabase cloud sync (analytics, templates, contact groups)
SUPABASE_SERVICE_ROLE_KEY=your_service_role_key_here

# SMTP (defaults work for Office 365)
SMTP_HOST=smtp.office365.com
SMTP_PORT=587
SMTP_STARTTLS_ENABLED=true

# Rate limiting
RATE_LIMIT_EMAILS_PER_HOUR=100
RATE_LIMIT_DELAY_MIN_MS=3000
RATE_LIMIT_DELAY_MAX_MS=8000

# Retry policy
RETRY_MAX_ATTEMPTS=3
RETRY_BACKOFF_MULTIPLIER=2

# Logging
LOGGING_LEVEL=INFO
```

All optional integrations (Gemini, Supabase, Google OAuth) degrade gracefully — the app runs fully without them; those features simply become unavailable in the UI.

### `application.properties` (classpath + user override)

`AppConfig` loads configuration in this priority order:

1. `src/main/resources/application.properties` — checked-in defaults (Azure IDs, SMTP, rate limits)
2. `~/.outlookautoemailier/application.properties` — user overrides, written by the Settings screen
3. `.env` at project root — **highest priority**, wins over both above

The `SettingsController` writes changes to the user-home file; calling `AppConfig.reload()` picks up those changes at runtime without restarting.

### User-home data directory

All runtime data is stored under `~/.outlookautoemailier/` (created automatically on first run):

| File / Directory | Purpose |
|---|---|
| `credentials.enc` | AES-256/GCM encrypted OAuth tokens (`CredentialStore`) |
| `msal-cache-source.json` | MSAL4J token cache for the source account |
| `msal-cache-sender.json` | MSAL4J token cache for the sender account |
| `google-drive-tokens.json` | Google Drive OAuth2 token cache (`GoogleDriveService`) |
| `image-library.json` | Image library metadata — Drive file IDs, tags, notes |
| `templates/*.json` | Saved email templates (Jackson-serialised `EmailTemplate`) |
| `image-cache.json` | MD5-keyed imgbb upload cache (`ImageCache`) |
| `audit.jsonl` | JSON Lines audit trail of every send/fail/skip event |
| `unsubscribed.txt` | Suppression list — one email address per line |
| `application.properties` | User settings overrides written by the Settings screen |

### Supabase schema

If you enable Supabase sync, run the following SQL once in your Supabase SQL editor:

```sql
CREATE TABLE email_batches (
  id TEXT PRIMARY KEY,
  batch_name TEXT, subject TEXT, template_name TEXT,
  sent_at TIMESTAMPTZ, total_recipients INT DEFAULT 0,
  sent_count INT DEFAULT 0, failed_count INT DEFAULT 0,
  open_count INT DEFAULT 0, link_click_count INT DEFAULT 0,
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE email_sends (
  tracking_id TEXT PRIMARY KEY,
  batch_id TEXT REFERENCES email_batches(id),
  recipient_email TEXT, recipient_name TEXT, subject TEXT,
  sent_at TIMESTAMPTZ, status TEXT DEFAULT 'SENT',
  failure_reason TEXT, opened_at TIMESTAMPTZ,
  contact_group_id TEXT,
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE contact_groups (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  emails JSONB DEFAULT '[]'::jsonb,
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE email_templates (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL, subject TEXT, body TEXT,
  is_html BOOLEAN DEFAULT false,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE link_clicks (
  id BIGSERIAL PRIMARY KEY,
  batch_id TEXT, tracking_id TEXT,
  original_url TEXT,
  clicked_at TIMESTAMPTZ DEFAULT now()
);
```

---

## Project Structure

```
OutlookAutoEmailer/
├── .env.example                          # Template for credentials and settings
├── .env                                  # Your local credentials (git-ignored)
├── pom.xml                               # Maven build (v1.2.0)
├── package-windows.bat                   # Convenience script for EXE packaging
├── CLAUDE.md                             # AI agent memory and architecture notes
├── CHANGELOG.md                          # Version history
│
├── src/main/java/com/outlookautoemailier/
│   ├── Main.java                         # Entry point — shows Splash then MainView
│   ├── AppContext.java                   # Double-checked-locking singleton; holds all backend + controller refs
│   │
│   ├── api/
│   │   ├── GraphApiClient.java           # Paginated Microsoft Graph v1.0 REST client
│   │   └── ContactFetcher.java           # Orchestrates contact retrieval from Graph API
│   │
│   ├── analytics/
│   │   ├── SentEmailRecord.java          # Per-recipient delivery record
│   │   ├── SentEmailStore.java           # In-memory store with addIfAbsent deduplication
│   │   ├── EmailBatch.java               # Batch-level stats (sent, failed)
│   │   ├── BatchStore.java               # In-memory batch registry
│   │   ├── LinkClickRecord.java          # Aggregated link-click data per URL per batch
│   │   ├── ContactReachabilityScorer.java # Scores contacts 0-100 by delivery history
│   │   ├── SendTimeAnalyser.java         # Computes hourly success rates; recommends send windows
│   │   ├── UnsubscribeAnalyser.java      # Surfaces unsubscribe trends by week/campaign
│   │   └── TrackingPixelServer.java      # No-op stub (open tracking removed by design)
│   │
│   ├── config/
│   │   ├── AppConfig.java                # Typed singleton; 3-tier load order
│   │   ├── EnvLoader.java                # Loads system environment variables
│   │   └── DotEnvLoader.java             # Parses the .env file at project root
│   │
│   ├── integration/
│   │   ├── GeminiEmailAgent.java         # Calls Gemini 2.5-flash to generate HTML email bodies
│   │   ├── GoogleDriveService.java       # Google Drive API v3 — upload, list, delete images
│   │   ├── SupabaseClient.java           # Centralised Supabase REST API singleton
│   │   ├── SupabaseAnalyticsSync.java    # Pushes/pulls email_batches and email_sends
│   │   ├── SupabaseContactGroupSync.java # Syncs contact groups to/from Supabase
│   │   ├── SupabaseTemplateSync.java     # Syncs email templates to/from Supabase
│   │   └── SupabaseUnsubscribeSync.java  # Syncs the suppression list to/from Supabase
│   │
│   ├── model/
│   │   ├── Contact.java                  # Microsoft Graph contact (name, email, company, jobTitle)
│   │   ├── EmailAccount.java             # Account descriptor (address, display name, type)
│   │   ├── EmailJob.java                 # Unit of work enqueued for delivery (priority-aware)
│   │   ├── EmailTemplate.java            # Template data model (subject, body, isHtml)
│   │   ├── ContactGroup.java             # Named group of contact email addresses
│   │   ├── ContactGroupStore.java        # JSON-persisted registry of contact groups
│   │   ├── ImageLibraryItem.java         # Image metadata — Drive file ID, public URL, tags, notes
│   │   └── ImageLibraryStore.java        # Singleton JSON store for image library items
│   │
│   ├── queue/
│   │   ├── EmailQueue.java               # Thread-safe priority blocking queue
│   │   ├── EmailDispatcher.java          # 2-thread consumer loop; forwards jobs to SmtpSender
│   │   └── RetryPolicy.java              # Exponential backoff (max 3 attempts, x2 multiplier)
│   │
│   ├── security/
│   │   ├── OAuth2Helper.java             # MSAL4J PKCE flow; token cache persistence
│   │   ├── GoogleOAuth2Helper.java       # Google PKCE OAuth2; refresh token auto-restore
│   │   ├── CredentialStore.java          # AES-256/GCM encryption of OAuth tokens to disk
│   │   ├── RateLimiter.java              # Token-bucket rate limiter (configurable emails/hour)
│   │   ├── SpamGuard.java                # Pre-send content and recipient validation
│   │   ├── EmailValidator.java           # RFC-compliant email address validation
│   │   ├── UnsubscribeManager.java       # Loads/saves suppression list; checked before every send
│   │   ├── BounceHandler.java            # Records and reacts to delivery bounce events
│   │   ├── SecurityAuditLog.java         # Writes JSON Lines audit trail to audit.jsonl
│   │   └── DkimSpfChecker.java           # DNS-based DKIM/SPF domain verification
│   │
│   ├── smtp/
│   │   ├── SmtpConfig.java               # SMTP connection parameters (host, port, STARTTLS)
│   │   ├── SmtpSender.java               # Delivers email via Jakarta Mail with XOAUTH2
│   │   ├── HtmlEmailNormalizer.java      # jsoup CSS inlining + MSO conditional comments
│   │   ├── ImageHostingService.java      # Uploads images to imgbb REST API
│   │   ├── ImageCache.java               # MD5-keyed upload deduplication cache
│   │   ├── EmailFooter.java              # Appends unsubscribe footer to outgoing HTML
│   │   └── LinkTracker.java              # Rewrites links for click tracking
│   │
│   ├── ui/
│   │   ├── MainController.java           # Sidebar navigation (9 ToggleButtons + badge updates)
│   │   ├── SplashController.java         # Splash screen shown during initialisation
│   │   ├── AccountSetupController.java   # OAuth2 login UI; triggers AppContext.initBackend()
│   │   ├── ContactListController.java    # Paginated contact list with group management
│   │   ├── ComposeController.java        # Email composition; enqueues EmailJob objects
│   │   ├── TemplateStudioController.java # Template editor with live WebView preview
│   │   ├── ImageLibraryController.java   # Google Drive image browser; tag/annotate images
│   │   ├── ImageLibraryPickerDialog.java # Modal image picker for use inside Template Studio
│   │   ├── QueueDashboardController.java # Real-time queue status and job list
│   │   ├── DeadLetterExplorerController.java # Browse/retry/export permanently-failed jobs
│   │   ├── AnalyticsController.java      # Apache ECharts charts in JavaFX WebView
│   │   ├── SettingsController.java       # Edits and saves application.properties
│   │   ├── PreviewModalController.java   # Full-screen email preview modal
│   │   └── EChartsTemplates.java         # Java-side HTML/JS templates for ECharts charts
│   │
│   └── util/
│       └── StudentEmailParser.java       # Parses student email lists from plain text
│
└── src/main/resources/
    ├── application.properties            # Default configuration (overridden by user-home file and .env)
    ├── logback.xml                       # Logback logging configuration
    ├── env.sample                        # Sample .env for user-home placement
    ├── css/
    │   └── style.css                     # Global JavaFX stylesheet
    ├── fxml/
    │   ├── Splash.fxml                   # Splash screen (shown on startup)
    │   ├── MainView.fxml                 # Root layout (1100x700) with sidebar
    │   ├── AccountSetup.fxml             # OAuth2 account configuration screen
    │   ├── ContactList.fxml              # Contact browser and group management
    │   ├── Compose.fxml                  # Email composition screen
    │   ├── TemplateStudio.fxml           # Template editor with live preview
    │   ├── ImageLibrary.fxml             # Google Drive image library browser
    │   ├── QueueDashboard.fxml           # Dispatch queue monitor
    │   ├── DeadLetterExplorer.fxml       # Dead-letter job inspector
    │   ├── Analytics.fxml                # Analytics dashboard with ECharts
    │   ├── Settings.fxml                 # Application settings screen
    │   └── PreviewModal.fxml             # Full-screen email preview modal
    └── images/
        └── splash.png                    # Splash screen branding image
```

---

## Architecture Overview

### Startup sequence

```
Main.java
  └─ show Splash.fxml (SplashController)
       └─ load MainView.fxml (MainController + 9 content panes)
            └─ AccountSetup.fxml shown first
                 └─ user completes OAuth2 for both accounts
                      └─ AccountSetupController.maybeInitBackend()
                           └─ AppContext.initBackend(sourceAccount, senderAccount)
                                └─ creates GraphApiClient, EmailQueue,
                                   EmailDispatcher, SmtpSender, ...
```

### `AppContext` singleton

`AppContext` is the application's service locator. All backend components and UI controllers are registered here. Controllers self-register from their `initialize()` methods:

```java
AppContext.get().setComposeController(this);
```

Backend initialisation is deferred until both OAuth2 logins complete, then triggered once via `AppContext.initBackend()`.

### `AppConfig` — 3-tier configuration

```
classpath:  application.properties                         (lowest — checked-in defaults)
user-home:  ~/.outlookautoemailier/application.properties  (user overrides)
project:    .env                                           (highest — secrets and local overrides)
```

### Email dispatch pipeline

```
ComposeController.onSend()
  └─ builds EmailJob (recipient, subject, body, priority)
       └─ EmailQueue.enqueue(job)
            └─ EmailDispatcher (2 threads) polls EmailQueue.take()
                 └─ SpamGuard.check() + RateLimiter.acquire()
                      └─ UnsubscribeManager.isSuppressed() — skip if true
                           └─ SmtpSender.send()
                                ├─ HtmlEmailNormalizer.normalize() (CSS inline + MSO)
                                ├─ EmailFooter.append() (unsubscribe link)
                                ├─ LinkTracker.rewriteLinks() (click tracking)
                                ├─ ImageHostingService.uploadIfNeeded() + ImageCache
                                ├─ Jakarta Mail XOAUTH2 delivery
                                └─ RetryPolicy on failure (max 3, x2 backoff)
```

### OAuth2 flow

```
OAuth2Helper.acquireToken(type)
  └─ MSAL4J PublicClientApplication
       └─ Interactive browser PKCE flow
            └─ token cached to ~/.outlookautoemailier/msal-cache-{type}.json
                 └─ CredentialStore.save() (AES-256/GCM encrypted)
```

### Contact fetching

```
ContactFetcher.fetchContacts()
  └─ OAuth2Helper.acquireToken(SOURCE)  ->  access token
       └─ GraphApiClient.getContacts()  (paginated, Graph v1.0)
            └─ returns List<Contact>
                 └─ ContactListController.populate()
```

### AI template generation

```
TemplateStudioController (user enters prompt)
  └─ ImageLibraryStore.buildGeminiContext()  (image URLs + tags)
       └─ GeminiEmailAgent.generateWithLibraryAsync(prompt, imageContext)
            └─ POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash
                 └─ returns inline-CSS HTML (body fragment only)
                      └─ HtmlEmailNormalizer.normalize()
                           └─ populates editor for review + save
```

---

## Building a Windows EXE

The `package-exe` Maven profile uses `jpackage` to bundle a self-contained Windows application image with an embedded JRE — no Java installation required on the target machine.

```bash
# Option 1 — Maven directly
mvn clean package -Ppackage-exe -DskipTests

# Option 2 — convenience script
package-windows.bat
```

Output is written to `target\installer\OutlookAutoEmailer\OutlookAutoEmailer.exe`.

**Requirements:** JDK 17+ (`jpackage` is included with the JDK). WiX Toolset is **not** required — the profile uses `APP_IMAGE` type, which produces a portable self-contained folder rather than an MSI installer.

---

## Running Tests

```bash
# Run all tests
mvn test

# Run a single test class
mvn -Dtest=EmailQueueTest test
```

Test reports are written to `target/surefire-reports/`.

---

## Known Limitations

- **`SecurityAuditLog` not wired** — the class exists and is instantiated in `AppContext`, but `SmtpSender` and `EmailDispatcher` do not call it yet. Audit entries are not written to `audit.jsonl`.
- **`DkimSpfChecker` results not surfaced** — DKIM/SPF checks run but the results are not displayed in `AccountSetup.fxml`.
- **Post-enqueue navigation** — `ComposeController` shows an informational alert after enqueuing instead of navigating to the Queue Dashboard via `MainController.navigateToQueue()`. This is a one-line fix.
- **Windows only for EXE packaging** — the `package-exe` Maven profile includes Windows-native JavaFX JARs. Running `mvn javafx:run` works cross-platform.
- **Open tracking removed by design** — `TrackingPixelServer` is a no-op stub. `SentEmailStore` records delivery counts only; per-email open timestamps are populated from Supabase if configured.
- **Google Drive image library requires public sharing** — uploaded images are shared as "anyone with link can view" to enable embedding in emails. Do not upload sensitive images.

---

## Screenshots

<!-- TODO: Add screenshots of the main UI screens -->

*Account Setup, Compose, Template Studio, Image Library, Queue Dashboard, Dead Letter Explorer, and Analytics screens coming soon.*
