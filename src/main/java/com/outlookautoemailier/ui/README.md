# ui — JavaFX Controllers

This package contains all JavaFX FXML controllers. Every controller implements `Initializable` and registers itself with `AppContext` at the end of `initialize()` so that other controllers can reach it for programmatic navigation and data handoff without direct coupling.

## Screens and Controllers

| Controller | FXML | Purpose |
|---|---|---|
| `SplashController` | `Splash.fxml` | Displayed on startup during class loading; transitions to `MainView.fxml` automatically. |
| `MainController` | `MainView.fxml` | Root layout (1100×700). Owns 9 `ToggleButton`s in a `ToggleGroup`, one per screen. Wraps all content panes in `ScrollPane`s. Exposes `navigateToQueue()`, `navigateToCompose()`, `navigateToImageLibrary()`, `navigateToAnalytics()`, `navigateToDeadLetters()`. Manages sidebar badge counts for queue and contacts. |
| `AccountSetupController` | `AccountSetup.fxml` | Shows sign-in buttons for source and sender accounts. Calls `maybeInitBackend()` once both OAuth2 logins complete, which triggers `AppContext.initBackend()`. |
| `ContactListController` | `ContactList.fxml` | Displays the paginated contact list fetched from Microsoft Graph. Supports contact group creation/management and forwarding selected contacts to `ComposeController`. |
| `ComposeController` | `Compose.fxml` | Email composition form. Builds `EmailJob` objects and enqueues them via `AppContext.get().getEmailQueue().enqueue(job)`. Shows a confirmation alert after enqueue (navigating to Queue Dashboard is a known pending improvement). |
| `TemplateStudioController` | `TemplateStudio.fxml` | Rich template editor with live `WebView` preview. Integrates `GeminiEmailAgent` for AI-assisted generation with image library context. Saves and loads templates via Jackson to `~/.outlookautoemailier/templates/`. |
| `ImageLibraryController` | `ImageLibrary.fxml` | Google Drive image browser. Handles Drive authentication, file upload, thumbnail grid display, tag/notes editing, and copy-URL-to-clipboard. Images tagged here become available as Gemini context. |
| `ImageLibraryPickerDialog` | *(inline Stage)* | Modal dialog for selecting an image from the library to insert into the template editor. Launched from within `TemplateStudioController`. |
| `QueueDashboardController` | `QueueDashboard.fxml` | Polls `EmailQueue` on a `Timeline` and displays live job counts (pending, sending, sent, failed). Updates the sidebar badge via `MainController.updateQueueBadge()`. |
| `DeadLetterExplorerController` | `DeadLetterExplorer.fxml` | Shows jobs with `DEAD_LETTER` status. Provides text search, failure-reason filter, per-row retry, bulk delete, and CSV export. |
| `AnalyticsController` | `Analytics.fxml` | Renders Apache ECharts visualisations in a `WebView`. Charts include delivery funnel, hourly success rates, unsubscribe trends, contact reachability scores, and send time recommendations. Optionally invokes `GeminiEmailAgent.analyzePerformanceAsync()` for AI-generated campaign insights. |
| `SettingsController` | `Settings.fxml` | Reads and writes `~/.outlookautoemailier/application.properties`. Calls `AppConfig.reload()` on save. |
| `PreviewModalController` | `PreviewModal.fxml` | Full-screen `WebView` preview of a rendered email body. Opened from `ComposeController` and `TemplateStudioController`. |
| `EChartsTemplates` | *(no FXML)* | Static helper that generates self-contained HTML/JS strings (bundling Apache ECharts) for each chart type used by `AnalyticsController`. |

## Key Design Decisions

- **Scroll wrapping** — `MainController.wrapAllPanesInScrollPanes()` replaces each raw `Pane` in the `StackPane` with a `ScrollPane` at initialisation time. Navigation then toggles visibility on the `ScrollPane` wrappers, not the inner panes (which must always remain `visible=true` to avoid white-screen rendering bugs).
- **Self-registration pattern** — every controller calls `AppContext.get().setXxxController(this)` from `initialize()`. This avoids JavaFX FXML controller coupling while giving any component access to any other component through `AppContext`.
- **Badge updates** — `MainController.updateQueueBadge(active, failed)` and `updateContactsBadge(count)` are thread-safe: they dispatch to the JavaFX thread via `Platform.runLater()` when called from background threads.

## Integration Points

- `AccountSetupController` → `AppContext.initBackend()` — triggers all backend creation.
- `ComposeController` → `EmailQueue.enqueue()` — submits jobs for dispatch.
- `TemplateStudioController` → `GeminiEmailAgent.generateWithLibraryAsync()` + `ImageLibraryStore.buildGeminiContext()`.
- `QueueDashboardController` → `MainController.updateQueueBadge()`.
- `AnalyticsController` → `GeminiEmailAgent.analyzePerformanceAsync()` for AI insights.
