# smtp — SMTP Sending Pipeline

This package is responsible for the final stage of email delivery: HTML normalisation, image hosting, link tracking, footer injection, and SMTP submission via Jakarta Mail. Every step runs synchronously within an `EmailDispatcher` worker thread.

## Classes

| Class | Description |
|---|---|
| `SmtpConfig` | Value object that holds SMTP connection parameters read from `AppConfig`: host, port, STARTTLS enabled flag, and the sender account's display name and address. |
| `SmtpSender` | Core delivery class. Accepts an `EmailJob`, runs it through `HtmlEmailNormalizer`, `EmailFooter`, `LinkTracker`, and `ImageHostingService`, then builds and sends a Jakarta Mail `MimeMessage` using XOAUTH2 SASL authentication (password fallback available). Calls `OAuth2Helper` to acquire a fresh access token for each send session. |
| `HtmlEmailNormalizer` | jsoup-based HTML post-processor. Inlines all CSS `<style>` rules as `style=""` attributes on every element, adds Microsoft Office (MSO) conditional comments for Outlook-specific layout fixes, and ensures the document has a proper `<html>` wrapper. Run on every HTML body before sending. |
| `ImageHostingService` | Uploads images to the imgbb REST API (`api.imgbb.com/1/upload`). Returns the permanent direct image URL. Used by `SmtpSender` to replace `data:image/...` inline base64 URIs with hosted URLs that email clients can fetch. |
| `ImageCache` | MD5-keyed deduplication cache for imgbb uploads. Persisted to `~/.outlookautoemailier/image-cache.json`. On each upload request, the image bytes are hashed; if the hash is already in the cache the stored URL is returned immediately without making an API call. |
| `EmailFooter` | Appends a branded HTML footer to every outgoing message. The footer contains the sender's organisation name, an unsubscribe link (pointing to a local handler that calls `UnsubscribeManager`), and optional social media links. |
| `LinkTracker` | Rewrites all `<a href="...">` links in the HTML body to pass through a tracking URL so click-through events can be recorded in `LinkClickRecord` and synced to Supabase. |

## Key Design Decisions

- **Normalisation order** — `HtmlEmailNormalizer` runs first (CSS inlining), then `EmailFooter` and `LinkTracker` add their content to an already-normalised document, then `ImageHostingService` replaces base64 URIs. This order ensures that footer and tracked link styles are also inlined.
- **imgbb for image hosting** — chosen because it provides a free, permanent direct URL with no authentication required for viewing. The `ImageCache` ensures each unique image is uploaded only once regardless of how many recipients receive the same email.
- **XOAUTH2 token freshness** — `SmtpSender` calls `OAuth2Helper.acquireToken()` at the start of each send session (not once per job). MSAL4J returns the cached token silently if it has not expired, so the overhead is minimal.
- **Jakarta Mail 2.0.1** — the Jakarta namespace (not the legacy `javax.mail`) is used throughout. XOAUTH2 is implemented via a custom SASL mechanism passed as a `Session` property.

## Integration Points

- `SmtpSender` is created by `AppContext.initBackend()` and called by `EmailDispatcher`.
- `ImageHostingService` and `ImageCache` are held as singletons in `AppContext`.
- `UnsubscribeManager` (in `security/`) is not called directly from this package; the check happens in `EmailDispatcher` before `SmtpSender` is invoked.
- `LinkTracker` and `EmailFooter` results feed into `SentEmailStore` and `LinkClickRecord` (in `analytics/`) via callbacks in `EmailDispatcher`.
