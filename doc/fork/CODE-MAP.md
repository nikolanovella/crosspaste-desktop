# Source map

[Fork overview](../../FORK.md) · [Development](DEVELOPMENT.md)

## Modules

| Directory | Responsibility |
| --- | --- |
| [`app/`](../../app/) | Compose desktop app, clipboard monitoring, device pairing, sync, settings, and native OS integration |
| [`core/`](../../core/) | Cross-platform core primitives, with JVM, JavaScript, and native source sets |
| [`shared/`](../../shared/) | Shared services and SQLDelight database definitions |
| [`shared-ui/`](../../shared-ui/) | Shared Compose UI code |
| [`cli/`](../../cli/) | Native command-line client for the running desktop app or headless instance |
| [`web/`](../../web/) | Browser extension |
| [`e2e/`](../../e2e/) | End-to-end test module |
| [`gradle/`](../../gradle/) | Wrapper and dependency version catalog |
| [`scripts/`](../../scripts/) | Existing packaging, update-testing, and publishing helpers |
| [`doc/en/`](../en/) | Upstream user/developer documentation |
| [`doc/fork/`](./) | Notes specific to this fork |

## Where to change a feature

| Change | Starting point |
| --- | --- |
| Which clipboard types may synchronize | [`PasteSyncPolicy.kt`](../../app/src/commonMain/kotlin/com/crosspaste/sync/PasteSyncPolicy.kt) |
| How synchronization is coordinated | [`GeneralSyncManager.kt`](../../app/src/commonMain/kotlin/com/crosspaste/sync/GeneralSyncManager.kt), [`SyncResolver.kt`](../../app/src/commonMain/kotlin/com/crosspaste/sync/SyncResolver.kt) |
| Watching local clipboard changes | [`PasteboardMonitor.kt`](../../app/src/commonMain/kotlin/com/crosspaste/paste/PasteboardMonitor.kt) |
| Windows clipboard reads/writes | [`WindowsPasteboardService.kt`](../../app/src/desktopMain/kotlin/com/crosspaste/paste/WindowsPasteboardService.kt) |
| Mac clipboard reads/writes | [`MacosPasteboardService.kt`](../../app/src/desktopMain/kotlin/com/crosspaste/paste/MacosPasteboardService.kt), [`MacosApi.swift`](../../app/src/desktopMain/swift/MacosApi.swift) |
| Saved desktop preferences | [`DesktopAppConfig.kt`](../../app/src/desktopMain/kotlin/com/crosspaste/config/DesktopAppConfig.kt) |
| Clipboard settings UI | [`PasteboardSettingsContentView.kt`](../../app/src/commonMain/kotlin/com/crosspaste/ui/settings/PasteboardSettingsContentView.kt) |
| Native desktop UI behavior | [`desktopMain/ui/`](../../app/src/desktopMain/kotlin/com/crosspaste/ui/) |
| Shared UI behavior | [`commonMain/ui/`](../../app/src/commonMain/kotlin/com/crosspaste/ui/) |
| Device trust and encryption | [`commonMain/secure/`](../../app/src/commonMain/kotlin/com/crosspaste/secure/), [`desktopMain/secure/`](../../app/src/desktopMain/kotlin/com/crosspaste/secure/) |

Koin dependency-injection wiring lives in the app's `module/` source directories.
SQLDelight schemas live under the shared module's source sets; search for `.sq`
files before changing persistence.

## Tests

Desktop-specific tests live in
[`app/src/desktopTest/`](../../app/src/desktopTest/). Existing native clipboard and
sync tests are useful starting points; the common, core, shared, and CLI modules
also contain their own test source sets.

Use the fast desktop suite for normal changes. Pairing/process/network tests are
tagged as integration tests and require the explicit Gradle flag documented in
the [development guide](DEVELOPMENT.md).
