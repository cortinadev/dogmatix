# Testing and validation

How Dogmatix is checked before a release: what is tested, how to run it, what the last run said and
what can only be checked by hand. Unit tests cover the pure logic, instrumented tests cover Room,
storage and the gamepad on a real Android runtime, and a short manual checklist covers what no
script can see (controller feel, storage cards, frontends).

## Levels

| Level | Where it runs | Needs | Covers |
|---|---|---|---|
| Unit (JVM) | `app/src/test`, any machine | JDK 17 or Docker | Parsing, rules, queue and scan logic, no Android runtime |
| Instrumented | `app/src/androidTest`, emulator or device | Android emulator or a device | Room migrations, DAOs, storage provider, queue recovery, gamepad smoke test |
| Manual | a handheld, with a controller | Device, optional SD card | Gamepad flow, touch, frontends, big downloads, real servers |

## Unit tests (`app/src/test`)

Grouped by area (class names, in `…Test`):

- **Search and parsing**: `SearchNormalizer`, `SearchIndex`, `RecentSearches`, `ConsoleFolderAliases`, `GameTitleCleaner`, `LibraryKeys`, `ListingSize`, `ListingParsing` (15 sample directory listings: table, nginx, Apache, IIS), `ListingRenames`.
- **Sources and scanning**: `SourcesJson`, `SourcesJsonMirrors`, `SourcesJsonFavourites`, `SourcesJsonScrape`, `ScrapeRecipes` (CSS-selector sources), `ListingLinks`, `KnownConsoles`, `IaSource`, `IaFiles`, `IaCookies`, `IaAuthJson`, `AuthRedirects`, `IaAuth` (Internet Archive), `SourceScanCheck`, `ScanRequests`, `ScanRetry`, `ScanHostGate`, `ScanEta`, `ScanScheduleAndProgress`.
- **Deep links and shortcuts**: `DeepLinkParser`, `DeepLinkResolver`, `DgmtxFile`.
- **Frontends**: `EsdeXml`, `EsdeFindRules`, `EsdeFavouriteSync`, `FrontendCheck`, `IisuJson`, `DaijishoSetup`.
- **Debrid and RomM**: `DebridMatcher`, `RommPlatformMapper`, `RommSource`, `CertPin`, `StoredZip`, `RommUploadResume`, `RommUploadSources`, `RommJson`, `RommMatching`, `RommPlayState`, `RommFavouritesMerge`, `RommCovers`.
- **Covers, similar games, recommendations**: `LibretroThumbnails`, `SimilarGames`, `RawgCatalog`, `Recommender`, `ColorMath`, `TagColors`.
- **Library**: `LibraryGenre`, `LibretroMetadat`, `LetterJump`, `VersionGrouping`, `VersionOriginTieBreak`.
- **Downloads**: `DownloadRuns` (one run per file), `DownloadRestore` (queue and retries after a restart), `DownloadPartials`, `DownloadQueue`, `DownloadListFilter`, `DownloadNotices`, `DownloadFailures`, `HttpResume`, `SourceReliability`, `MirrorUrls`, `TokenBucket`, `SeedingMode`, `AppUpdates` (APK checks of the updater).
- **Verification**: `Checksums`, `DatParser`, `DownloadCheck`, `LibretroDats`.
- **Files and storage**: `ArchivePaths`, `CopyCheck`, `StorageProblems`, `MultiDisc`, `DocumentIds`, `DiscSetCleanup`.
- **Collection**: `CollectionGames`, `CollectionHealth`, `VolumeUsage`, `CollectionDuplicates`, `CollectionRepair`, `CollectionDeletionPlan`, `CollectionRules`, `GameTraitRules`, `SmartStorage`, `LibraryMoveJob`, `TrashRules`, `ActionHistoryRules`.
- **Gamepad**: `GamepadLayout`, `TriggerPress`.

New pure logic comes with its test.

## Instrumented tests (`app/src/androidTest`)

Eight classes, 45 tests. They use their own test runner and application, which redirect the app's
files, caches and databases to a throw-away folder (`instrumentation-sandbox/`, deleted before and
after), so the installed app's settings, library and downloads are never touched.

- `MigrationTest`: databases created as in 1.0 and 1.1–1.2 with data in them are opened through every migration; the schema, integrity checks, the full-text index and the migrated data are verified, and every migration must be registered.
- `SettingsCompatTest`: old setting values still read correctly (debrid switch, collection tool, unknown values).
- `DaoTest`: replacing one source's games (two scans do not duplicate, a failure midway keeps the old rows), download history, resume data, favourites.
- `LargeCatalogTest`: 20,000 games across five consoles — paging in every sort order without repeats or gaps, search, letter jump; timings go to logcat (`DogmatixPerf`).
- `QueueRecoveryTest`: the download queue rebuilt from the history after a restart (order, held and paused rows, no duplicates).
- `StorageProviderTest` (13 tests): copy, move, rename, delete, trash and *Move library* cycles against a test documents provider that behaves like an external one (name clashes get ` (1)`, moving onto an existing name fails).
- `GamepadSmokeTest`: B never closes the app, ZL / ZR change section, LB / RB switch panel, A on a failed download retries it. The most fragile of the set.

## Running

### Unit tests, lint and builds

```bash
# native (JDK 17 + Android SDK platform 36, build-tools 36.0.0)
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest

# in Docker (image `dogmatix-build`; the host needs neither JDK nor SDK)
docker run --rm -v "$PWD":/workspace -v dogmatix-gradle:/root/.gradle dogmatix-build \
  ./gradlew --no-daemon --no-watch-fs testDebugUnitTest
```

Use `--no-watch-fs` in Docker so the Gradle daemon sees edits made on the host. A file edited a
moment ago can be read truncated by the container (Gradle fails in seconds with "Unexpected end of
input"); compare sizes with `wc -c` on both sides and run it again.

### `scripts/validate.sh`

Runs the whole chain and exits 1 if something fails (2 for a usage or environment problem). Steps, in order:

| Step | What it does |
|---|---|
| `changelog` | `CHANGELOG.md` has `## [<versionName>]` with a `YYYY-MM-DD` date and at least one entry |
| `unit` | `testDebugUnitTest` |
| `lint` | `lintDebug` (new errors fail it, see below) |
| `build` | `assembleDebug assembleDebugAndroidTest` |
| `release` | `assembleRelease` (R8) |
| `apkcheck` | copies the release APK to `build/validate/dogmatix-<version>.apk` and runs `release-check.sh --pre-tag` |
| `instrumented` | `connectedDebugAndroidTest`, only on emulators (or with `--instrumented`) |

```bash
scripts/validate.sh                        # everything that applies
scripts/validate.sh --only unit,lint       # chosen steps
scripts/validate.sh --skip instrumented    # all but these
scripts/validate.sh --fail-fast --list --help
DOGMATIX_DOCKER=1 scripts/validate.sh      # each step inside `dogmatix-build`
```

Environment variables: `DOGMATIX_DOCKER=1` (use Docker), `DOGMATIX_GRADLE_VOLUME` (Gradle cache
volume), `DOGMATIX_DOCKER_MEMORY` (default 3300m), `DOGMATIX_DOCKER_GRADLE_ARGS`,
`DOGMATIX_ANDROID_VOLUME` (keeps the debug key so installs update in place) and
`DOGMATIX_ADB_CONNECT` (device address for instrumented runs in Docker, which also need
`--instrumented`). Requires bash 3.2+, awk, sed, grep and git. A cold run takes 15 minutes or more,
a warm one about 7.

Output: `build/validate/report.txt` (step, result, duration, test counts, lint errors and warnings)
and one `build/validate/<step>.log` per step.

### `scripts/release-check.sh`

```bash
scripts/release-check.sh dist/dogmatix-1.4.apk --pre-tag     # before tagging
scripts/release-check.sh dist/dogmatix-1.4.apk --tag v1.4    # after tagging
```

Prints ✓ / ✗ / ⚠ for: package name (a `.debug` build is "not publishable"), version name and code
against `app/build.gradle.kts`, not debuggable, signature (verifies, one signer, the release
certificate — or warns when it is the Android debug key), the `v<version>` tag, a version code higher
than the last tag's, and writes `<apk>.sha256` and `<apk>.md5` for the release notes. Only ✗ fails.
The release is signed with `keystore/dogmatix-release.jks` (not in the repository); without it Gradle
falls back to the debug key without a warning, and this script is what notices.

### On a device or emulator

Never `connectedAndroidTest` on a real device: it uninstalls the debug app and with it its data and
folder permissions. Install both APKs and run the instrumentation directly:

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  com.cortinadev.dogmatix.debug.test/com.cortinadev.dogmatix.testing.DogmatixTestRunner
# one class:   -e class com.cortinadev.dogmatix.data.local.MigrationTest
# skip gamepad: -e notClass com.cortinadev.dogmatix.ui.GamepadSmokeTest
adb uninstall com.cortinadev.dogmatix.debug.test
```

Keep the screen on and unlocked. It takes 2–3 minutes. The app's process is killed when the run
starts, and the folder picker shows a "Dogmatix tests" root while the test APK is installed.

### Continuous integration (GitHub Actions)

- `validate.yml` (push to `master`, pull requests, no secrets): job `build` (changelog, unit tests, lint, debug and test APKs, reports as artifacts) and job `instrumented` (Android emulator, API 30).
- `release.yml` (manual, input `version`): checks version and changelog, builds the signed release from repository secrets, runs `release-check.sh --pre-tag` and uploads the APK with its checksums. It does not publish.

Neither workflow has run on GitHub yet.

### Lint

`lintDebug` compares against `app/lint-baseline.xml` (the issues known when it was created, 235 entries) and
fails on any new error. Warnings do not fail. To accept the current state after a deliberate change:
`./gradlew updateLintBaseline`.

## Results of 23 October 2026

Run in Docker with `DOGMATIX_DOCKER=1 scripts/validate.sh --skip changelog,instrumented`:

| Step | Result |
|---|---|
| Unit tests | 594 tests in 87 classes, 0 failures (679 in 98 on 27 October, see below) |
| Lint | 0 errors; 6 warnings outside the baseline (baseline: 10 errors, 223 warnings, 1 hint) |
| Debug and test APKs | built |
| Release (R8) | built, 59.8 MB |
| APK check | version 1.4 / 14, not debuggable, checksums verified; the signature check failed only because the build tree had no release keystore |
| Strings | the 14 new English strings exist in Spanish; no placeholder mismatch in the six languages |

(The 594 comes from the unit-test result files of the last Docker run, after the second round of fixes.)

Not run on 23 October: the instrumented tests, any device check and the GitHub workflows (checked only with actionlint and shellcheck, not run on GitHub).

## Results of 24 October 2026 (handheld)

Debug build 1.3 (database v19) updated in place to 1.4 (v20) on an Android 14 handheld, driven with test servers on the build machine. No SD card was inserted that day.

- **Update and migration**: OK. After the first start the database is at v20, `integrity_check` is ok, and 60,740 files and 13 history rows are intact (no pending retries); Downloads and Library look right in landscape and portrait.
- **Instrumented tests**: OK. 42 tests (everything but the gamepad class) in 46 s, and `GamepadSmokeTest` (3 tests) in 26 s. Device timings (logcat `DogmatixPerf`): inserting 20,003 rows (9,709 games) 16.0 s; one A → Z page of 250 games 2.8 s; the other sort orders about 3 s; resolving 1,501 references 1.06 s; letter buckets 49 ms and index 53 ms; searches 27–613 ms ("dragons" 613, "quest" 345, "Crystal" 336, the rest under 60). The real database and settings were not touched.
- **Downloads and queue**: OK. `.dogmatix-part` while downloading and no ✓; pause freezes it and resume continues with `Range` + `If-Range` (206); stop keeps the partial; delete on a paused or failed row removes row, partial and resume data; a double press makes one request and one partial; an automatic retry survives a force-stop and fires once at the saved time (and is dropped when the setting is 0); a queue of four with a held and a paused row comes back unchanged; a cut transfer resumes; a changed ETag restarts from zero; an HTML answer fails with its reason and no retry; *Download again* followed by delete keeps the original copy.
- **Extraction and storage**: partly OK. A corrupt archive fails with its reason and keeps the archive, *Retry* extracts again without downloading; a name clash saves `Game (2).sfc` with the original intact; Health offers to complete a multi-disc set with a loose disc and Repair does. **Not verified**: lost folder permission in Collection, a root under *Documents*, the delete confirmation with tracks and playlists, deleting a `.cue` from *Free up space*, and move SD ⇄ internal (no SD).
- **Sources and scanning**: OK for 503 with `Retry-After` (short waits retried, a 300 s wait fails at once), empty and HTML answers (games kept), nginx `<pre>` listings (8 files with exact sizes), unreachable server (*Could not connect*, no retries), a scan queued behind another, switching a source off, on and deleting it, and mirrors. **Not observed**: the re-read of web sources on the first *Rescan* after updating (the device only had magnet sources).
- **Gamepad and regressions**: OK in landscape and portrait (focus, panels, B layer by layer without closing the app, ZL / ZR, retry, delete confirmation, filter sheet), details popup, ★, Summary, *Updates* ("up to date"), German and French screens.

Fixed after this run: the scan report showed "(HTTP 192)" for an empty or missing listing, and the legend of Downloads offered *X Delete* on a running row, where X does nothing.

Findings still open: Duplicates and *Free up space* do not detect an identical cue/bin set in another console's folder (identical loose files in the same folder are found); an incremental scan of 29 magnet sources takes 53–142 s; the automatic-scan job leaves no trace in logcat; the Collection snapshot keeps pointing to deleted folders until the next analysis.

## Results of 27 October 2026 (known issues closed)

The CSS-selector and Internet Archive sources and the wider dialogs were merged into the 1.4 tree, and the findings of 24 October were fixed. Docker: 679 unit tests in 98 classes, 0 failures; `lintDebug` green (15 warnings outside the baseline, 0 errors). The instrumented set (45 tests in 8 classes) was not run again.

Checked by hand on the handheld, landscape and portrait unless noted:
- **Merge**: a database with the selector / Internet Archive data migrates from v19 to v20; the first *Rescan* re-reads the web sources once, a source with selectors reads its pages and reports *unchanged*, an `ia://` source reports `unchanged (item_last_updated)`, and the second rescan is all 304 / unchanged.
- **Duplicates and Free up space** find an identical cue/bin set in another console's folder and say which copy is kept.
- **Rescan** with 29 unchanged magnets: 0.3 s (was 53–142 s); a full pass from the cache takes 50 s without asking the swarm; changing a source's setup or its rows makes it re-read. The scan and the automatic-scan job now leave log lines.
- **Sources**: deleting a manufacturer without consoles.
- **Downloads**: pausing a download the server cannot resume (the Archive's ZIP viewer) shows *Will restart from the beginning* and resumes from zero without `Range`; normal files from the same source keep resuming with `Range` + `If-Range` (206), also with *Pause all* / *Resume all* and after a force-stop.
- **Collection**: lost folder permission (shown in Health after analysing), a root under *Documents* (download, ✓, analysis, trash), deleting a `.cue` from *Free up space* (with its tracks, for good, noted in History), moving the library between two internal roots with undo (identical MD5s).
- **Collection, unreachable roots and confirmations**: checked on the handheld on 27 October 2026 (landscape and portrait). With a library folder deleted outside the app and no new analysis, Duplicates is empty, Summary shows 0 and Health reports the folder the app can no longer open; a delete over it shows a snackbar with the count and the reason ("7 could not be done · access to a library folder was lost"), and with only one console folder gone the other copy goes to the trash. The confirmations count files per copy ("3 files (including 1 tracks and playlists)"), Free up space deletes for good and says so in History, and Repair says how many `.m3u` lists are rewritten (undoable).

**Not verified**: move SD ⇄ internal (no SD card), cancel and resume of a move inside one volume (it ends in under three seconds), signing in to Internet Archive in the in-app browser, ▼ from the *Or a new manufacturer* field to the console chips with a real controller, and the GitHub workflows (they need a push).

Findings still open: with *Source: Direct link* a row keeps its *2+* badge; a download from an item can show the chip of a torrent with a file of the same name; web scans send a generic browser User-Agent (only Archive requests use Dogmatix's own); a collapsed console card in Sources closes again after each scan; in the Duplicates confirmation single-file copies show no file count while multi-file ones do; with focus on the bulk buttons of Collection tools the legend is still the rows' one.

## Limits and untested areas

- Everything that depends on the Android runtime — file providers on real cards, SAF behaviour, notifications, JobScheduler, foreground-service limits in the background — is exercised by the instrumented set and by hand.
- Copies are verified by size and sampled blocks (head, tail and six blocks in between), not by full hash.
- A web download without `Content-Length` cannot detect a silent cut.
- *Move library* deletes `*.dogmatix-part` files in the source folder, so a paused web download restarts from zero if it was in the moved folder; `.dogmatix-part` files left by a dead process are not cleaned up.
- A `.cue` whose file was renamed to `(2)` still points to the old file; undoing a delete that rewrote a `.m3u` does not restore the earlier list.
- A torrent parked after an extraction failure keeps its cache until its row is deleted.
- RomM scans are not retried; a RomM server that is off fails at the first attempt.
- Real servers, a physical controller, the SD card, ES-DE / iiSU / Daijishō and large downloads are manual only.

## Release checklist

1. **Prepare**: `versionName` and `versionCode` in `app/build.gradle.kts`; a `## [x.y] – date` entry in `CHANGELOG.md` whose first line reads well on its own (the in-app updater shows it); copy `keystore/` and `.env` into the tree that builds.
2. **Validate**: `scripts/validate.sh` all green (instrumented in CI or on an emulator); commit, rebase on `origin/master`, `clean testDebugUnitTest assembleRelease` from that commit; `release-check.sh dist/dogmatix-x.y.apk --pre-tag` all ✓.
3. **Install and update on a handheld**: clean install (use a temporary application id so the installed release is untouched) and update over the previous published release, checking downloads, ★, ✓, settings and the database integrity; build with a lower `versionName` to see the updater announce the new release.
4. **Gamepad**: onboarding; one ZL / ZR press = one change; LB / RB; B undoes layer by layer and never closes the app; A downloads directly; X details; Y search; Select ★; START move mode; initial focus in dialogs; legends in Xbox, Nintendo and PlayStation layouts; A/B and X/Y swap.
5. **Touch**: long press in Downloads, ✕ in the search boxes, the folder picker, the filter sheet.
6. **Screen**: landscape, portrait, emulated notch, 4:3.
7. **External storage** (with the SD card; back up the frontend's `gamelists` and `custom_systems` first): download to the card, a download that does not fit going to the second root, move SD ⇄ internal and undo, trash on the card, ES-DE check 17/17, ★ ⇄ `<favorite>`, `.dgmtx` launched from cold, folder permissions after a reboot.
8. **Network**: resume an HTTP download after force-stop, torrent, RomM over HTTPS, incremental scan.
9. **Publish**: push, tag `vX.Y`, `release-check.sh` without `--pre-tag`, create the GitHub release with a single `.apk` and its SHA-256 in the notes, check that the previously installed version detects it, restore the device (orientation, screen timeout, overlays, test files).
