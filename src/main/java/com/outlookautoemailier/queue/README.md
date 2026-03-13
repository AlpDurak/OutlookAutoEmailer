# queue — Email Queue and Dispatch

This package implements the asynchronous email delivery pipeline: a thread-safe priority queue, a multi-threaded dispatcher, and a retry policy with exponential backoff.

## Classes

| Class | Description |
|---|---|
| `EmailQueue` | Thread-safe priority blocking queue backed by `PriorityBlockingQueue<EmailJob>`. `enqueue(job)` is non-blocking; `take()` blocks until a job is available. Jobs are ordered by `EmailJob.compareTo()` (highest priority first). |
| `EmailDispatcher` | Starts 2 daemon worker threads on `initBackend()`. Each thread loops on `EmailQueue.take()`, applies `SpamGuard` and `RateLimiter`, checks `UnsubscribeManager`, then hands the job to `SmtpSender`. On failure the job is either re-enqueued (with incremented retry count) or moved to `DEAD_LETTER` status when `RetryPolicy.isExhausted()` returns true. Updates `QueueDashboardController` counts and `MainController` sidebar badge after every state change. |
| `RetryPolicy` | Stateless helper. `shouldRetry(job)` returns true if `job.getAttemptCount() < maxAttempts` (default 3). `getDelayMs(job)` returns `baseDelayMs * (multiplier ^ attemptCount)` — a 2× exponential backoff starting at a configurable base delay. |

## Key Design Decisions

- **2 worker threads** — chosen as a balance between throughput and the Office 365 SMTP connection rate limit. Increasing beyond 2 risks triggering throttling on the Microsoft side.
- **Priority ordering** — `EmailJob` implements `Comparable` so the `PriorityBlockingQueue` naturally dequeues high-priority jobs (e.g. manually-triggered single sends) ahead of large batch jobs without any additional logic in the dispatcher.
- **Dead-letter on exhaustion** — when all retry attempts are consumed, the job's status is set to `DEAD_LETTER` and it is stored in a separate list accessible by `DeadLetterExplorerController`. This prevents lost jobs and allows manual inspection and re-queuing.
- **Badge updates are thread-safe** — `EmailDispatcher` calls `MainController.updateQueueBadge()` from worker threads; `MainController` dispatches to the JavaFX thread internally via `Platform.runLater()`.

## Integration Points

- `EmailQueue` is created by `AppContext.initBackend()` and held as a singleton in `AppContext`.
- `ComposeController` calls `AppContext.get().getEmailQueue().enqueue(job)` to submit work.
- `EmailDispatcher` calls `SmtpSender.send()` for each job.
- `QueueDashboardController` reads queue state on a `Timeline` pulse and calls `MainController.updateQueueBadge()`.
- `DeadLetterExplorerController` reads the dead-letter list from `EmailQueue` and can call `EmailQueue.requeue(job)` to retry.
