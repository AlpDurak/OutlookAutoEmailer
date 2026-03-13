# analytics — Delivery Analytics Pipeline

This package provides the data structures and analysis utilities that power the Analytics dashboard. All analyser classes are stateless utility classes (private constructors, static methods only) — they accept pre-loaded data and return computed results, making them trivially testable. Mutable state lives only in `SentEmailStore` and `BatchStore`.

## Classes

| Class | Description |
|---|---|
| `SentEmailRecord` | Per-recipient delivery record: `trackingId`, `batchId`, `recipientEmail`, `status` (`SENT` / `FAILED`), `sentAt` timestamp, and optional `failureReason`. |
| `SentEmailStore` | In-memory store of `SentEmailRecord` objects with `addIfAbsent` deduplication by `trackingId`. Thread-safe via `CopyOnWriteArrayList`. |
| `EmailBatch` | Batch-level aggregation: `id`, `batchName`, `subject`, `sentAt`, `totalRecipients`, `sentCount`, `failedCount`. |
| `BatchStore` | In-memory registry of `EmailBatch` objects keyed by ID. |
| `LinkClickRecord` | Aggregated click-through data: `batchId`, `originalUrl`, click count, and last-clicked timestamp. |
| `ContactReachabilityScorer` | Scores each contact address 0–100 using a weighted formula: 60% delivery rate + 30% suppression status + 10% recent activity bonus (90-day window). Provides `categorize()` which buckets scores into Reachable (80-100), At Risk (50-79), and Unreachable (0-49). |
| `SendTimeAnalyser` | Computes per-hour delivery success rates from `SentEmailRecord` history (minimum 10 sends per hour for a valid rate). `getRecommendedWindows()` returns the top 3 non-overlapping 2-hour windows by success rate. |
| `UnsubscribeAnalyser` | Groups unsubscribe events by ISO week (`weeklyUnsubscribeCounts`), computes per-campaign unsubscribe rates (`campaignUnsubscribeRates`), and counts suppression events in the current calendar month (`countThisMonth`). |
| `TrackingPixelServer` | **No-op stub.** Open tracking was intentionally removed. The class compiles and starts but emits no responses and records no opens. |

## Key Design Decisions

- Open tracking (pixel-based open detection) was removed by design to avoid privacy concerns. `TrackingPixelServer` is retained as a stub so the codebase compiles without changes. `SentEmailRecord` has no `openedAt` field on the Java side; the Supabase `email_sends` table retains the column for potential future use via `SupabaseAnalyticsSync`.
- All three analyser classes are pure functions of their inputs, making them safe to call from background threads without synchronisation.
- `ContactReachabilityScorer` accepts a `Set<String>` of suppressed addresses rather than reading `UnsubscribeManager` directly, keeping the analytics package free from dependency on the security package.

## Integration Points

- `SentEmailStore` and `BatchStore` are held in `AppContext` and updated by `SmtpSender` after each delivery attempt.
- `AnalyticsController` reads `SentEmailStore`, `BatchStore`, and `UnsubscribeManager` to populate ECharts charts via `EChartsTemplates`.
- `SupabaseAnalyticsSync` (in `integration/`) mirrors `EmailBatch` and `SentEmailRecord` objects to Supabase in the background after each batch completes.
