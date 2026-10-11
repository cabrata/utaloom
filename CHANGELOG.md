# Changelog

## [v1.4.0] - 2026-10-11

### Added
- **desktop:** Add equalizer, crossfade, scrobbling, backups, lyrics, statistics, and desktop integrations (`341ee64`)
- **audio:** Add native VLC pitch support and AudioEffects integration (`2ddb24c`)
- **lyrics:** Add a free Google Translate provider (`0ba9134`)
- **ci:** Automatically generate AI changelogs on push and release (`d5d1dfd`)

### Changed
- **lyrics:** Expand romanization to match the Android app (`08410a3`)
- **changelog:** Generate release-specific changelogs from scratch (`f4e0f1f`)

### Fixed
- **audio:** Resolve Windows libvlccore loading issues and Compose warnings (`774a4e1`)
- **changelog:** Unwrap placeholder links and deduplicate overlapping entries (`122cd5f`)
- **ci:** Harden AI changelog generation against chatty model output (`56ff246`)
- **ci:** Install VLC before running Ubuntu release tests (`2624bc2`)

## [v1.3.0] - 2026-10-11

### Added
- **Draggable carousels** with navigation arrows and a scrollbar, so content can be browsed by drag, arrow click, or scrollbar.
- **Android-style lyrics view** that splits duet lines across the left and right sides.

### Fixed
- **Vertical scrolling on touchscreens** now works as expected.
- **Scrollbars** are now positioned on the right side.

## [v1.2.4] - 2026-10-11

### Fixed
- **Player (Windows):** The requested fullscreen placement is now re-applied on subsequent placement changes, so the window stays where it was asked to go instead of reverting to a stale position.
- **Player (Windows):** Exiting fullscreen is now reliable — the player restores its previous windowed size and position rather than getting stuck in fullscreen.

## [v1.2.3] - 2026-10-11

### Fixed
- **player:** Restore 1.2.0 fullscreen behaviour on Windows ([0f816a4])

## [v1.2.2] - 2026-10-11

### Fixed
- **player:** Use borderless fullscreen on Windows instead of AWT exclusive fullscreen mode.
- **player:** Avoid a crash when fullscreen is exited twice on Windows.
- Print full test failure output in CI logs.
- **player:** Restrict the graphical fullscreen test to Linux displays.

## [v1.2.1] - 2026-10-11

### Fixed
- **Player:** Corrected fullscreen window bounds restoration and the initial view so the player now returns to its previous window size and position after exiting fullscreen, and displays correctly when fullscreen is first entered.

## [v1.2.0] - 2026-10-10

### Added
- Native desktop fullscreen controls for the player, including correct display-bounds handling and window decoration restore. (`0827dd0`)

### Fixed
- Player no longer reopens when the fullscreen effect fires after the player has already been closed. (`070cfc1`)
- Added coverage verifying fullscreen display bounds and window decoration restore. (`cdd6780`)

## [v1.1.1] - 2026-10-10

### Fixed
- **import:** Stop the background import before the shutdown flush, so the importer can no longer write concurrently with — or after — the final flush during shutdown. ([add9d02])

## [v1.1.0] - 2026-10-10

### Added
- Import playlists from Spotify and YouTube Music links.
- Support for Spotify playlists with more than 100 tracks.
- Progress bar showing the status of an in-progress link import.
- Ability to cancel a running link import.
- Link imports now run in the background, with a tray notification when they finish.

### Fixed
- Guard background import cancellation and restart ownership so cancelled or interrupted imports don't leave stale state behind.

## [v1.0.7] - 2026-10-10

### Added
- **Desktop smart shuffle recommendations** — the player now suggests tracks intelligently when shuffling on desktop (`bd7bea4`).
- **Utaloom logo in the sidebar** — the new branding is now visible in the app's sidebar (`5e7ccae`).

### Fixed
- **Legacy Listen Together URL migration** — old Listen Together URLs are now migrated safely, and the migration runs on every start so existing users don't get stuck on stale links (`5e7ccae`, `6846adb`).
- Refreshed screenshots with the new Utaloom logo (`f5d31f5`).
- Updated the README to clarify the application structure (`20fe851`).
- Added project links to the README (`0a520ee`).

## [v1.0.6] - 2026-10-09

### Changed
- **New logo** — Replaced the Metrolist-derived icon with the original Utaloom "Woven U" mark.
- **Desktop-only** — Removed the Android module, related workflows, and dependencies, restoring Utaloom as a desktop-only application.

## [v1.0.5] - 2026-10-09

### Changed
- Made the library, settings, and browse screens responsive in portrait orientation.
- Kept portrait artwork and player controls visible while preserving page and keyboard space.
- Polished portrait responsiveness across screens.

### Fixed
- Restored native Android notification transport controls.
- Corrected the repeat-all transition in notification handling.
- Matched native Media3 accessibility labels for notification shade skip controls.
- Targeted exact notification shade controls and captured failures for diagnostics.
- Verified Android notification transport controls end to end.

## [v1.0.4] - 2026-10-09

### Fixed
- **Android: lyrics regex initialization.** The regex patterns used for parsing lyrics were not being initialized correctly on Android, which could leave lyrics parsing broken or falling back to unparsed text. Initialization now happens properly, so timestamped and plain lyrics parse and sync as expected on Android.

## [v1.0.3] - 2026-10-09

### Added
- Native Utaloom Android host with shared sources, a Media3 playback service, and a signed APK release (`6b0f915`)
- Full media session event forwarding on Android, with JUnit4 device acceptance tests covering playback, notification controls, background playback, and live YouTube (`c7bb365`)
- Shared Utaloom Compose UI, including adaptive phone navigation and an adaptive player (`ee67c87`)
- Continuous integration for the Android app (`f751610`)

### Changed
- Extracted the platform audio backend out of the shared `Player` (`87d289d`)
- Browse headers and Together controls now remain readable on phones (`de9c499`)

### Removed
- Vendored Metrolist copy. The Android port reuses Utaloom code instead of the imported app, which was dropped after the initial import (`ee17ca4`, superseding `f751610`)

## [v1.0.2] - 2026-10-08

### Added
- **libVLC is now bundled with installers** — the Windows MSI ships libVLC, while the deb/rpm packages declare a dependency on the distribution's VLC package.

### Changed
- **Renamed the project to Utaloom.** Existing `metrodesk` data directories are migrated automatically.
- **Removed Metrolist branding** — packages moved to `com.utaloom` and documentation reworded. GPL attribution is retained.
- **Listen Together** now defaults to `utaloom.caliph.dev`; previously saved server URLs are migrated to the new default.
- deb packages are now maintained by `utaloom@caliph.dev`.

## [v1.0.1] - 2026-10-08

### Changed
- Enabled ProGuard for release builds, shrinking the installer from **107 MB to 73 MB** (~32% smaller).
- Refreshed screenshots for full-screen lyrics, explore, artist, and queue views.
- Isolated the test data directory to prevent tests from touching local app data.

## [v1.0.0] - 2026-10-08

### Added
- Production release workflow with in-app update check (`31808e7`)
- Apple Music–style lyrics view (`3993785`)
- Metrolist lyrics providers with fallback and word-synced karaoke (`ba4c549`)
- Linux and Windows Metrodesk client (`a554158`)

### Fixed
- Lyrics interlude gaps now take focus so the sung line dims; dots remain level (`d567208`)
- Clear buffering after stream refill (`f85743d`)
- Align VLC stream capabilities and verify offline playback (`79d7464`)
- Auto-release when `appVersion` in `gradle.properties` changes (`2aebac4`)
- Retry VLC install with direct download fallback on Windows (`99a6cb6`)
- Robust process handling for headless Windows runner (`bd1a282`)
- Remove `Set-StrictMode` in `verify-windows.ps1` (`6449695`)
- Fix `verify-windows` syntax and hermetic test suite on CI (`027b449`)
- Add full workflow and ListenTogether acceptance suites, plus Windows MSI CI check (`f92c51a`)
- Resolve `Peer` redeclaration in `TogetherAcceptance` (`7f616ac`)
- Add screenshots to README (`40eb89b`)
- Scaffold Metrodesk with JVM Innertube port and stream resolver (`c993933`)
