# model — Data Models

This package contains the plain data classes that flow between every layer of the application. All classes are serialisation-friendly (Jackson-compatible) and carry no business logic. Persistence classes (`ContactGroupStore`, `ImageLibraryStore`) are the exception — they are singletons responsible for loading and saving their respective data to the user-home directory.

## Classes

| Class | Description |
|---|---|
| `Contact` | A Microsoft Graph contact with `displayName`, `emailAddresses`, `companyName`, and `jobTitle`. |
| `EmailAccount` | Describes one authenticated Microsoft 365 account (address, display name, and role — SOURCE or SENDER). |
| `EmailJob` | The unit of work placed on the queue: recipient `Contact`, subject, rendered HTML body, priority level, retry count, and failure reason. Implements `Comparable` for priority ordering. |
| `EmailTemplate` | Stores a reusable template: name, subject, raw body text, and an `isHtml` flag. Jackson-serialised to `~/.outlookautoemailier/templates/<name>.json`. |
| `ContactGroup` | A named list of email addresses that can be targeted as a single unit in the Compose screen. |
| `ContactGroupStore` | Thread-safe singleton that loads and saves `ContactGroup` objects to `~/.outlookautoemailier/contact-groups.json`. |
| `ImageLibraryItem` | Metadata for a Google Drive image: local UUID, Drive file ID, public URL, thumbnail URL, tags list, and freeform notes. |
| `ImageLibraryStore` | Thread-safe singleton that loads and saves `ImageLibraryItem` objects to `~/.outlookautoemailier/image-library.json`. Provides `buildGeminiContext()` which serialises the library into a prompt fragment for `GeminiEmailAgent`. |

## Key Design Decisions

- `ImageLibraryStore.buildGeminiContext()` is the bridge between the image library and Gemini AI — it formats all image URLs, tags, and notes into a structured prompt section that the AI uses when deciding which images to embed.
- `EmailJob.compareTo()` orders jobs by priority descending so that high-priority jobs are dispatched first by `EmailQueue`.
- `ContactGroupStore` and `ImageLibraryStore` both follow the same singleton + disk-persistence pattern: load on construction, save on every mutation, fail silently on IO errors.

## Integration Points

- `EmailJob` is produced by `ComposeController` and consumed by `EmailDispatcher`.
- `EmailTemplate` is produced and consumed by `TemplateStudioController`.
- `ImageLibraryStore` is read by `TemplateStudioController` (to build the Gemini context) and by `ImageLibraryController` (to display the grid).
- `ContactGroup` is produced by `ContactListController` and read by `ComposeController` when addressing bulk sends.
