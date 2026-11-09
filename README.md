# Dogmatix

<img src="icon.png" width="110" align="right" alt="Dogmatix icon">

Dogmatix is an Android app for discovering, downloading, verifying and looking after a retro game collection, made for **Android handhelds** (Kinhank K56, Retroid, Anbernic, Odin and the like): every action works from a gamepad in landscape, and everything also works by touch, in portrait too.

It began in April 2026 as a fork of [Milou](https://github.com/santiifm/milou), by [santiifm](https://github.com/santiifm), and keeps its idea: point the app at torrents, magnets or web folders full of ROMs, let it index and tag every file (console, region, languages, revision, extension…) and download what you want, with archives extracted for you. Since then the interface has been rebuilt around the controller and most of the engine behind it has been rewritten or extended: a download queue that survives restarts, resumable and verified downloads, incremental parallel scans, new kinds of sources (RomM servers, Internet Archive, web pages described with CSS selectors, debrid services), a *Collection* section that looks after what is already on disk, and one-button integrations with ES-DE, iiSU, Daijishō and RomM. [Compared with Milou](#compared-with-milou) lists the differences side by side.

## Download

APKs are on the [releases page](https://github.com/cortinadev/dogmatix/releases). Android 10 or newer; arm64-v8a, armeabi-v7a and x86_64. Since 1.3.1 the app checks GitHub for a newer release when it starts and installs it in place (Settings → General → *Updates*).

Dogmatix ships **without sources**. On first run, import a sources JSON exported from another device, or add manufacturers, consoles and URLs by hand in *Sources*. No sources file is part of this repository.

## Highlights

- **Gamepad first.** Every screen, dialog and setting is reachable with the D-pad and face buttons; a focus ring shows where you are and a legend at the bottom says what each button does right now. B always undoes one layer and never closes the app.
- **One row per game.** Regions, revisions, demos and sources of the same game are grouped into one row with square box art and coloured chips; A downloads the version that fits your preferred region and languages, every disc of it, and the details popup lists all the others.
- **Downloads you can trust.** A persistent queue you can reorder and hold, pause and resume for every kind of download, partial files that never pass for finished games, readable failure reasons, automatic retries that survive a restart, and a checksum against No-Intro / Redump DATs, RomM or Internet Archive when a download completes.
- **Sources of every kind.** Torrents and magnets, plain web folders (Apache, nginx, IIS, h5ai…), pages described with CSS selectors, Internet Archive items and their per-system ZIPs, RomM servers, and TorBox / Real-Debrid in front of any torrent. Rescans are incremental, parallel and polite to servers.
- **A Collection section** that analyses what is on disk: health checks, duplicates, repairs, free-up-space, a trash with undo, a second storage root (SD card) with smart routing, moving the whole library, and your own collections by hand or by rules.
- **Frontend integrations.** Shortcuts, find rules and gamelist entries for ES-DE, an emulator entry for iiSU, values for Daijishō, favourites and covers kept in sync, and a *Check frontends* button that repairs what is missing.
- **Six languages** (English, Spanish, German, French, Portuguese, Italian), light / dark / true-black themes, five accents, and button legends drawn the Xbox, Nintendo or PlayStation way.

## Features

### First run

An onboarding wizard explains what Dogmatix does, asks for the root ROMs folder, your preferred region and languages, a sources JSON to import, and, when ES-DE is installed, offers to configure it. Every step can be skipped, B goes back one step, and the wizard never shows again once finished.

### Library

- **Filters and sort.** A fixed filter panel in landscape (Console, Region, Language, Tag, Genre, Decade, Favourites, Source) with ◀ ▶ quick change and multi-select dropdowns; console chips and a modal filter sheet in portrait. Active filters show as chips under the search box (A removes one, *Clear* removes all). The sort button offers A → Z, Z → A, size, console and decade. The panel folds into a thin rail (R3, or its arrow) so the list gets the full width.
- **One row per game.** Versions are grouped with a `4+` badge and multi-disc releases travel together (`2 discs`, total size). A downloads the **preferred version**: releases before demos, betas, prototypes and bad dumps, then your region and its fallbacks (Spain → Europe → World → USA…), a version in one of your languages, the latest revision, verified `[!]` dumps. Nothing is hidden: the details popup lists every version, preferred first, and A on any of them downloads it.
- **Covers and colours.** Square box art from [libretro-thumbnails](https://github.com/libretro-thumbnails) (the covers RetroArch shows, no API key) found by the No-Intro / Redump file name, with RomM's covers first when a server is configured and up to three other versions tried when the preferred one has none. The console chip takes its console's colour and tags are tinted by group (region, language, revision, demo / beta / hack), all editable.
- **Marks.** ✓ on games already on disk, ★ on favourites (Select stars; favourites survive rescans), a server icon on games your RomM server already has.
- **Search.** Lenient matching ("yugioh" finds *Yu-Gi-Oh!*, "virtual tenis" finds *Virtua Tennis*), the last ten searches that found something under the empty box, and results from the **whole app** under the games: downloads, sources, settings, Collection tools and collections, each opening in its section with B coming back to the search.
- **Details popup** (X on a row): cover, year, developer, genres and synopsis from RAWG, falling back to TheGamesDB, cached on the device; RomM adds rating, play status and the server's collections. *Similar* filters the library by franchise and genre across every console; *Collection* adds the game to one of yours.
- **Recommendations.** With no search, filter or sort, the Library opens on shelves built from what you star, download and keep on disk: *For you*, *Complete the series*, *More for <console>*, *Popular on your platforms* (with a RAWG key) or *Highlights* when there is nothing to go on yet. A downloads, X opens details, Select stars.
- **Genre and Decade** filters fed by libretro-database's DATs and by the details you open; **jump by letter** with ◀ ▶ when sorted by name.

### Downloads

- **Persistent queue.** The list and the queue live in the database: whatever was running when the app or the device went down starts again on the next launch, in order; paused downloads come back paused. START on a queued row enters move mode (▲ ▼ move, LB puts it next, Y holds it); a held download keeps its place but waits. Chips with counts (All · In progress · Queued · Held · Failed · Completed), a search box, and global *Pause all* / *Resume all* / *Retry failed* / *Clear completed*.
- **Pause and resume everything**: torrents keep their pieces, web and debrid downloads their partial file, and a resume continues with a `Range` request only when the server confirms it is the same file (`If-Range`). Servers that cannot resume are told apart and the row says *Will restart from the beginning*.
- **Partial files are partial.** Downloads are written as `<name>.dogmatix-part` and renamed only when whole, so a half file never shows up as a game in the Library, in Collection or in a frontend; *Download again* keeps the old copy until the new one is complete; a transfer cut short is never taken as finished, and a web page served instead of a file fails at once.
- **Why it failed.** No connection, timed out, server error, not on the server any more, not enough space, folder permission lost, SD card removed, no torrent metadata, debrid account problem, damaged archive… shown on the row and kept across restarts.
- **Automatic retries** (default 3) after 1, 5, 15, 30 and 60 minutes for failures that may go away on their own; they survive a restart and, when one comes due in the background, run as soon as the app is open again. **Downloads per server** caps how many web downloads talk to one host; **best address first** orders a source's main URL and mirrors by their measured success rate and speed.
- **Verification.** Finished downloads are hashed in the background (CRC32 and SHA-1, inside kept ZIPs too) and checked against RomM's hashes, Internet Archive's hashes or the console's No-Intro / Redump DAT (fetched from libretro-database or imported in Settings). The row says *Verified · No-Intro*, *Does not match* or *Not verified* and why. Nothing is deleted because of it.
- **Multi-disc games** move into a `<Game>.m3u/` folder with a `<Game>.m3u` playlist once every disc is in, for systems whose emulators read playlists (PlayStation, Saturn, Sega CD, Dreamcast, GameCube, PC Engine CD, 3DO, Neo Geo CD, Amiga, C64, MSX…; never PS2 or Xbox). A disc that arrives later joins the folder.
- **Extraction** keeps folder structure, refuses unsafe paths, never overwrites (an identical file is reused, a different one becomes `name (2).ext`), reports why it failed, keeps the archive and unpacks it again on *Retry* without downloading twice.
- **Notifications** with *Pause all* / *Resume* / *Cancel all*, progress and speed; a notice when the batch finishes with how many completed and failed.
- **Multi-selection** (Select, or long press) with an action bar offering only what the ticked rows accept: retry, pause, stop, delete, *next*, hold, release.
- **Seed after download** (off by default): 30 min, 1 hour, until ratio 1.0, or unlimited.
- Rows show the same covers, tags and colours as the Library, the RomM upload state (*↑ RomM n%*) and the verification result. Deleting a finished download moves it to the trash (undo in Collection → *History*).

### Collection

A section between Downloads and Sources for the games already on disk. It analyses the download folders on demand (X, or *Analyse*), keeps the result and refreshes a console after each download.

- **Summary**: games on disk, coverage of your catalogue, favourites on disk, storage volumes, per-console figures, latest downloads, biggest games, regions and languages; A opens the Library filtered.
- **Health**: twelve checks (lost folder permission, low space, empty files, broken `.cue` / `.gdi` / `.m3u` references, incomplete sets, games in another console's folder, leftover partial downloads, sets without a playlist, archives already extracted, empty folders, foreign extensions, sources never scanned), each with its fix.
- **Duplicates**: the same game twice (same files, different versions, or same size and content, cue/bin and multi-disc sets included, also across console folders) with a suggestion of which copy to keep.
- **Repair**: create missing `.m3u` playlists, move games to their console's folder, delete empty folders, leftovers and archives already extracted, with a preview.
- **Free up space**: the biggest items first, plus the torrent and cover caches. Deletes for good.
- **SD storage**: a second library root. Consoles can live there, suggestions say which to move when internal space runs low, and a download that does not fit goes to the same console folder on the other root.
- **Move library**: the whole library or some consoles to another folder, pausing downloads while it copies; it can be interrupted, resumed and undone. Every copy is verified (size and sampled blocks) before the original is deleted.
- **History and trash**: what was deleted, repaired, moved or imported, with undo. Deletions go to a hidden `.dogmatix-trash` (which ES-DE skips) that empties itself after 7 or 30 days.
- **Collections**: your own lists, by hand or by rules (console, region, language, tag, genre, decade, favourites, on disk, source, size, text), plus your RomM server's collections. A opens the Library showing the collection.
- Storage problems are told apart (no space, permission lost, SD card removed), whatever cannot be reached is left alone, and delete confirmations count the tracks and playlists that go with a `.cue`, `.gdi` or `.m3u`.

### Sources

- **Manufacturers → consoles → URLs**, each URL with its content type (games, miscellaneous, RetroAchievements), optional folder filter, mirrors and an on/off switch (off removes its games at once; on rescans it). *Add console* offers about 50 predefined consoles across 16 manufacturers with name, chip label and folder aliases filled in; everything stays editable, including the chip colour.
- **Kinds of source**: `.torrent` files and magnets; web folder listings (Apache, nginx, IIS, h5ai…, tables or plain links); **pages with CSS selectors** (element, link, name, size, default extension, `{page}` ranges such as `1-50` or `A-Z`, with a *Test* button that shows what matched); **Internet Archive** items (`ia://<item>`, or any `archive.org/details/…` address; a ZIP's address lists the files inside it through the Archive's viewer, so per-system ZIPs can be browsed one game at a time); **RomM** platforms (`romm://<slug>`).
- **Incremental rescans**: web listings ask the server first (ETag / Last-Modified), torrents already indexed with the same settings are not fetched again and their file lists are cached on the device, Internet Archive items are asked only whether they changed, and each source replaces only its own games, so one that fails keeps what it had. *Full rescan* reads everything again.
- **Steady scans**: sources are read in parallel (two per server), busy servers are retried with growing waits honouring `Retry-After`, mirrors stand in for a failing address, an empty listing never wipes a source, scans requested while one runs are queued and merged, and the scan keeps going when you leave the screen. A *Last scan* card lists every source with a readable result and *Retry failed*. Under each URL, its server's success rate and speed.
- **Automatic scan** (off by default): daily or weekly, on Wi-Fi only and optionally while charging, with a notification of the new games it finds.
- **Export / import**: a `dogmatix-sources.json` carrying manufacturers, consoles (names, short labels, folder aliases, colours, favourites), URLs, mirrors and selector recipes. Import replaces every source, rescans and re-indexes; it can be undone from *History*.
- **Folders**: per-console download paths (useful for frontends), reuse of folders named the way ES-DE, RetroArch, Batocera or EmulationStation name them (`gba`, `psx`, `snes`…), and a merge dialog when several folders of the same console sit side by side.

### Integrations

- **RomM** (Settings → Integrations → *RomM server*): URL and token, *Test connection*, self-signed HTTPS accepted per server after showing the certificate, a stepper per console to pick the platform. Finished downloads are **uploaded** with RomM's chunked API, resumable from the last confirmed chunk; games made of several files (cue/bin, gdi, extracted folders, multi-disc sets) go up as one uncompressed `<game>.zip` built on the fly. The **server catalogue** marks what RomM already has, provides covers, *Upload what's missing*, *Sync favourites with RomM* and *RomM covers to ES-DE*; the details popup shows rating, genres, play status and collections. RomM can also be a **source**: a console indexes everything RomM has for a platform and downloads it with the account credentials.
- **Debrid services**: *TorBox* or *Real-Debrid* in front of any torrent. The magnet is handed to the service, the row shows its progress, and the file comes down over HTTP through the usual path, resumable. When TorBox can only return one big ZIP of a 100+ file torrent, Dogmatix removes it from the account and falls back to the direct torrent for that file. Real-Debrid needs a premium account.
- **Internet Archive account**: some items need a signed-in account. Settings → Integrations → *Internet Archive* opens archive.org's own sign-in page inside the app; Dogmatix keeps only the two session cookies, never your password, sends them only to archive.org (data nodes included) and identifies itself with its own User-Agent, as the Archive's automated-access page asks. Accounts created with Google must set a password on archive.org first.
- **Frontends**: *Frontend shortcuts* drops a `★ Search for more games....dgmtx` file into every console folder, which ES-DE, iiSU and Daijishō show as a game that opens Dogmatix filtered to that console; *Configure ES-DE* writes the find rule, system overrides, gamelist entries and preview banner in one button; *Configure iiSU* edits its emulator list; *Set up Daijishō* hands over the values its form needs; *Sync favourites with ES-DE* keeps ★ and `<favorite>` together both ways; *Check frontends* reads the whole setup back and repairs it. See [FRONTENDS.md](FRONTENDS.md).

### Settings

Five categories (General, Library, Appearance, Downloads, Integrations) with a panel on the left in landscape and tabs on top in portrait, searchable from the Library's search box. Among them: language; updates; gamepad layout (Xbox / Nintendo / PlayStation legends) and A/B · X/Y swap; download folder and per-console subfolders; automatic scan; preferred region and favourite languages; results per page; theme (System / Light / Dark / True black) and accent; covers in the list and tag colours with a D-pad colour picker; concurrent downloads, downloads per server, speed limit (web and torrent), automatic retries, finish notice; auto-extract, multi-disc playlists, verification and DAT files; metadata timeout, seeding, debrid service and key; frontends and servers.

## Compared with Milou

Dogmatix was forked from Milou's `master` in April 2026. Milou is a phone app driven by touch, with a floating menu button, full-screen filter overlays and a list of files; its engine indexes torrents, magnets and web folder listings, downloads over HTTP or libtorrent with a concurrency limit and a speed limit, and extracts archives. Everything below was added or rewritten in Dogmatix.

### Interface

| Area | Milou | Dogmatix |
|---|---|---|
| Input | Touch | Gamepad first (focus ring, contextual legend, Xbox / Nintendo / PlayStation legends, button swap, analogue triggers) and touch |
| Navigation | Floating menu button, full-screen overlays | Tabs on top in landscape, bottom bar in portrait, immersive mode, B undoes one layer at a time |
| First run | Bundled sources, settings by hand | Onboarding wizard (folder, region and languages, sources import, ES-DE); no bundled sources |
| Library | One row per file, filter overlays | One row per game with versions and discs grouped, square covers, coloured chips, foldable filter panel, active-filter chips, sort button, recommendations, similar games, genre / decade filters, jump by letter, favourites, ✓ / ★ / RomM marks |
| Details | None | Popup with artwork, year, developer, genres, synopsis (RAWG / TheGamesDB / RomM), every version, similar games, collections, play status |
| Search | Name only | Lenient matching, recent searches, results from downloads, sources, settings and Collection |
| Downloads | Progress list | Queue with move / hold, chips and search, pause / resume, failure reasons, retry timers, verification result, covers and tags, multi-selection, upload state |
| Collection | None | New section: summary, health, duplicates, repair, free up space, SD storage, move library, history and trash, collections |
| Sources | Manufacturer / console / URL lists | Restyled cards, editable URLs, mirrors, switches, selector recipes with *Test*, predefined consoles, console colours, scan report, reliability per server, export / import |
| Settings | One list | Five categories, steppers and switches for the controller, searchable rows, update checker |
| Look | Material defaults | Flat palette, one accent (5 presets), light / dark / true black, Manrope |
| Languages | English | English, Spanish, German, French, Portuguese, Italian |
| Dialogs | Touch | Initial focus, B closes, wide dialogs for forms, confirmations that start on *Cancel* |

### Engine

| Area | Milou | Dogmatix |
|---|---|---|
| Source kinds | `.torrent`, magnets, HTML directory tables | Plus plain-link listings (nginx, IIS, Apache), pages with CSS selectors, Internet Archive items and ZIP viewers, RomM platforms, debrid services |
| Rescan | Everything, one source after another | Incremental (ETag / Last-Modified, fingerprints, cached torrent metadata, Internet Archive change check), parallel with per-server limits, retries with `Retry-After`, mirrors, per-source replacement, queued and merged requests, cancel-safe, scan report, automatic daily / weekly scan |
| Listing parser | Tables | Tables and `<pre>` listings, root-relative and scheme-relative links, thousands separators, truncated names, unsafe links filtered |
| Download list | In memory | Room history with queue order, hold flag, failure reason, retry timer and debrid ids; restored on launch |
| Download control | Start, retry, cancel; concurrency semaphore | Reorderable queue with global and per-server slots, pause / resume for HTTP, torrent and debrid, one lifecycle per file (no double starts, no ghost rows), bulk actions, notifications with actions |
| Resume | From zero | `Range` + `If-Range` with stored validators, `.dogmatix-part` files promoted when whole, servers that cannot resume detected |
| Failures | Retry | Classified reasons, automatic retries with persistent timers, mirrors ordered by measured reliability, transient vs permanent errors |
| Verification | None | CRC32 / SHA-1 / MD5 against No-Intro / Redump DATs (fetched or imported), RomM and Internet Archive hashes |
| Extraction | Extract, swallow errors | Reasons for failures, no overwrites, folder structure kept, unsafe paths refused, cancellable, archive kept for retry |
| Post-download | None | Multi-disc `.m3u` folders, RomM upload, ES-DE favourite sync, verification, library index refresh |
| Storage | Write and delete | Trash with undo and retention, action history, verified copies and moves, second root with smart routing, library move with resume and undo, storage problems told apart |
| Metadata | File-name tags | Plus RAWG / TheGamesDB details, libretro covers, genres and years from libretro-database, franchise and genre similarity, recommendations |
| Persistence | Room v2, 5 tables | Room v20 with explicit migrations from v1 (some twenty tables), migration tests from 1.0 and 1.1 schemas |
| Integrations | None | RomM (catalogue, uploads, favourites, covers, self-signed TLS), TorBox, Real-Debrid, Internet Archive session, ES-DE, iiSU, Daijishō |
| Updates | Toast on new version | In-app download and install, APK checked for package, version and signature |
| Tests | Two placeholder tests | 679 unit tests in 98 classes, 45 instrumented tests in 8 classes, lint baseline, `validate.sh`, `release-check.sh`, GitHub Actions |

### In numbers

| | Milou (April 2026) | Dogmatix 1.4 |
|---|---|---|
| Kotlin files / lines | 100 / ~8,300 | 487 / ~63,500 |
| Screens | 5 (Library, Downloads, Sources, Settings, Contact) | 7 (+ Onboarding, Collection) |
| Room schema | v2 | v20 |
| UI languages | 1 | 6 |
| Tests | 2 placeholders | 679 unit + 45 instrumented |

The package is `com.cortinadev.dogmatix` (Milou's is `com.santiifm.milou`), so Dogmatix installs as a separate app and does not update over a Milou install.

## How it works

A file called **Burnout Paradise (En,Es,Fr) (NTSC).zip** found in a source becomes **Burnout Paradise · Languages: En, Es, Fr · Region: NTSC · Console: Xbox 360 · Manufacturer: Microsoft · Extension: .zip**, grouped with its other versions under one game.

1. **Sources** are read by kind: a torrent or magnet gives its file list (from the swarm, a local `.torrent` or the on-device metadata cache); a web folder is parsed as a listing; a selector recipe is applied page by page; an Internet Archive item is read from its metadata API; a RomM platform from its REST API. Each file is tagged, keyed by game and written in one transaction that replaces only that source.
2. **The Library** pages by game, groups versions, ranks them by your region and languages, and decorates rows with covers, colours, ✓, ★ and RomM marks.
3. **A download** takes a slot in the queue (global and per server), goes through a debrid service, libtorrent or HTTP with resume and mirrors, is written as a `.dogmatix-part`, promoted when whole, extracted if needed, moved into its multi-disc folder, uploaded to RomM, verified against hashes and, if starred, pushed to ES-DE's favourites.
4. **Collection** scans the folders, compares with the catalogue and offers repairs; every deletion goes through the trash and the history.

## Deep links

Other apps and frontends can open the library with filters applied:

```
dogmatix://library?console=nintendo_snes&region=USA,Europe&lang=En&type=GAME&genre=platform&decade=1990&q=mario&fav=1
```

| Parameter | Meaning |
|---|---|
| `console` | Console ids as shown in Sources (comma-separated) |
| `region`, `lang`, `type`, `filetype`, `tag` | Library tags (comma-separated); the kind is inferred from the value |
| `genre` | Genres: ids such as `rpg`, `platform`, `shooter`, or database names such as `Role-playing` |
| `decade` | Decades: `1990`, `90s`, `80s`… |
| `q` | Search text |
| `fav` | `1` / `true` shows favourites only |

Try it with `adb shell am start -a android.intent.action.VIEW -d "dogmatix://library?console=nintendo_snes&q=mario"`. Dogmatix also opens `.dgmtx` files (a text file carrying one of these links), which is how it appears as an "emulator" inside ES-DE, iiSU or Daijishō. See [FRONTENDS.md](FRONTENDS.md).

## Controller cheat sheet

| Button | Action |
|---|---|
| D-pad | Move focus · ◀ ▶ in the Library list jump to the next / previous letter (sorted by name) |
| A | Select / download the preferred version · pause or resume a download |
| B | Back one layer (close sheet, clear search, back to tabs); never closes the app |
| X | Game details · in Downloads, delete · in Collection, act on what is ticked or analyse |
| Y | Focus search · in selection mode, tick everything |
| Select | Star / unstar · in Downloads, tick rows |
| START | On a queued download: move mode (▲ ▼ move, LB next, Y hold) |
| LB / RB | Switch panel (filters, chips, tools or categories ⇄ list) |
| R3 | Fold / unfold the filter panel |
| ZL / ZR | Previous / next section |

The legend only appears while a controller is connected; names and colours follow the *Gamepad layout* setting.

## Building

Requirements: JDK 17, Android SDK platform 36, build-tools 36.0.0.

```bash
git clone https://github.com/cortinadev/dogmatix.git
cd dogmatix

./gradlew assembleDebug      # debug APK (applicationId com.cortinadev.dogmatix.debug)
./gradlew assembleRelease    # release APK (minified)
./gradlew test               # JVM unit tests
./gradlew lint               # fails on new errors (known ones are in app/lint-baseline.xml)
scripts/validate.sh          # changelog, tests, lint, builds and release check in one go
```

Optional API keys for the details popup and recommendations go in a git-ignored `.env` at the repository root (`RAWG_API_KEY`, `THEGAMESDB_API_KEY`); without them the app builds and the popup says "no data". The release APK is signed with `keystore/dogmatix-release.jks`, which is not in the repository; without it Gradle falls back to the debug key and `scripts/release-check.sh <apk>` flags the APK as not publishable.

### Testing and validation

Unit tests cover the pure logic, instrumented tests cover migrations, DAOs, storage providers, queue recovery and the gamepad on a device or emulator, and a manual checklist covers the rest. [TESTING.md](TESTING.md) has the inventory, the Docker commands, the CI workflows and the per-release checklist.

### Project structure

```
app/src/main/java/com/cortinadev/dogmatix/
├── data/
│   ├── local/       Room database (v20), entities, DAOs, DataStore settings
│   ├── model/       Domain models (sources, games and versions, tags, regions)
│   ├── repository/  Settings, downloads, files, consoles, favourites, sources, collections
│   ├── search/      App-wide search providers
│   ├── service/     Scanning, torrents, downloads, queue, extraction, verification, multi-disc,
│   │                debrid, RomM, Internet Archive, frontends, Collection tools, updates
│   └── state/       Shared state (scan progress, pending navigation and filters)
├── di/              Hilt modules
├── ui/
│   ├── common/      Gamepad detection, shortcut bus, layouts, trigger handling
│   ├── components/  Shell, tabs, legend, chips and colours, covers, dialogs, steppers, focus ring
│   ├── screens/     onboarding, home (Library), download, collection, sources, settings (+ RomM, IA), contact
│   └── theme/       Palettes, ThemeMode, accents, typography
└── util/            Pure logic with its tests: file-name parsing, listings, selector recipes, Internet
                     Archive, scan checks, download queue / resume / failures / verification, versions,
                     multi-disc sets, covers, similarity, recommendations, RomM, frontends, Collection rules
app/src/main/assets/consoles.json   Default sources (empty in Dogmatix)
app/src/test/                       JVM unit tests
app/src/androidTest/                Instrumented tests (sandboxed runner)
scripts/                            validate.sh, release-check.sh
```

## Tech stack

- **UI**: Jetpack Compose, Material 3, Navigation Compose
- **DI**: Hilt (KSP)
- **Persistence**: Room + FTS, DataStore Preferences
- **Images**: Coil, with covers kept on disk
- **Torrents**: libtorrent4j · **HTTP**: HttpURLConnection, Jsoup · **JSON**: Gson
- **Archives**: 7-Zip-JBinding-4Android (ZIP / 7z)
- **Concurrency**: Kotlin Coroutines and Flow; JobScheduler for the automatic scan

## Credits

Dogmatix is a fork of [Milou](https://github.com/santiifm/milou) by [santiifm](https://github.com/santiifm), who wrote the original indexer, tagger, downloader and extractor that this app grew from. Covers come from [libretro-thumbnails](https://github.com/libretro-thumbnails), genres, years and DATs from [libretro-database](https://github.com/libretro/libretro-database), and game details from [RAWG](https://rawg.io) and [TheGamesDB](https://thegamesdb.net).

## Roadmap

Ideas and requests are welcome as [issues](https://github.com/cortinadev/dogmatix/issues). The [CHANGELOG](CHANGELOG.md) lists what each release brought and what is still known to be rough.

## Disclaimer

This app is for educational purposes only. Users are responsible for ensuring they have the legal right to download any content.
