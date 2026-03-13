# integration — External Service Integrations

This package contains all clients for external cloud services: Google Gemini AI, Google Drive, and Supabase. All integrations are designed to fail gracefully — the application runs fully without any of them; each service either becomes unavailable in the UI or silently no-ops when its required credentials are absent from `.env`.

## Classes

| Class | Description |
|---|---|
| `GeminiEmailAgent` | Calls the Google Gemini 2.5-flash REST API to generate HTML email bodies from a natural-language prompt. Has two generation modes: `generateAsync(prompt)` (no image context) and `generateWithLibraryAsync(prompt, imageContext)` (injects image library metadata so Gemini can embed available images). Also provides `analyzePerformanceAsync(contextJson)` for AI-generated campaign performance insights. Returns a `CompletableFuture<String>`. No-ops (throws `IllegalStateException`) if `GEMINI_API_KEY` is absent from `.env`. |
| `GoogleDriveService` | Google Drive API v3 client (REST, no SDK). Double-checked-locking singleton. Manages a dedicated "OutlookAutoEmailer Images" folder. Supports `authenticate()` (interactive PKCE browser flow on port 8767), `tryRestoreSession()` (silent token refresh from saved file), `uploadFile()`, `uploadBase64Image()`, `listImages()`, and `deleteFile()`. Tokens are persisted to `~/.outlookautoemailier/google-drive-tokens.json`. Uploaded files are shared as "anyone with link can view" automatically. |
| `SupabaseClient` | Centralised singleton for Supabase REST API calls. Reads `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` from `.env`. Provides low-level `upsert()`, `select()`, and `delete()` helpers used by all `SupabaseXxxSync` classes. No-ops gracefully when credentials are absent. |
| `SupabaseAnalyticsSync` | Pushes `EmailBatch` and `SentEmailRecord` objects to Supabase `email_batches` and `email_sends` tables on a fire-and-forget basis after each batch completes. |
| `SupabaseContactGroupSync` | Syncs `ContactGroup` objects (from `ContactGroupStore`) to/from the Supabase `contact_groups` table. Push and pull operations are called from `ContactListController`. |
| `SupabaseTemplateSync` | Syncs `EmailTemplate` objects (from the templates directory) to/from the Supabase `email_templates` table. Called from `TemplateStudioController`. |
| `SupabaseUnsubscribeSync` | Syncs the suppression list (`UnsubscribeManager`) to/from Supabase. Used to share the unsubscribe list across multiple machines or backup to the cloud. |

## Key Design Decisions

- **No SDK dependencies for Google or Supabase** — all API calls use Java's built-in `java.net.http.HttpClient`, keeping the dependency tree lean. Jackson is used for JSON parsing.
- **Graceful degradation** — every `SupabaseXxxSync` class checks for the service role key before making any network call. `GeminiEmailAgent.isConfigured()` can be called before any UI interaction to conditionally show or hide AI features.
- **PKCE on port 8767** — `GoogleDriveService` uses port 8767 for its OAuth callback (distinct from any other local server). The OAuth2 flow blocks the calling thread for up to 5 minutes waiting for the browser redirect.
- **Image sharing model** — all files uploaded via `GoogleDriveService` are immediately shared with "anyone with link can view" permission so their public URLs (`https://drive.google.com/uc?export=view&id=FILE_ID`) are embeddable in HTML emails without authentication.

## Integration Points

- `GeminiEmailAgent` is called from `TemplateStudioController` (template generation) and `AnalyticsController` (campaign performance analysis).
- `GoogleDriveService` is the backend for `ImageLibraryController`.
- `SupabaseAnalyticsSync` is called by `AnalyticsController` and/or `EmailDispatcher` after batch completion.
- All Supabase sync classes use `SupabaseClient` as their HTTP layer.
