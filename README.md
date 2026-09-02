# Dogmatix

A fork of [Milou](https://github.com/santiifm/milou) — an Android app for discovering, downloading and managing retro games — focused on **UI/UX for handheld Android devices**, usable with a gamepad or with touch.

Milou indexes the contents of `.torrent` files and magnet links, tags every file automatically (console, region, languages, extension…) and handles downloads with automatic ZIP/7z extraction. Dogmatix keeps all of that logic intact and rebuilds the interface around it so the whole app can be driven from a D-pad and buttons, in landscape, on devices such as the Kinhank K56, Retroid or Anbernic Android handhelds.

> The scraping, indexing, download and extraction engine is Milou's work. Credit for all of that goes to [santiifm](https://github.com/santiifm). Dogmatix only changes what you see and how you interact with it.

## Why a fork

Milou was designed for phones and touch. On a handheld with a small landscape screen and a controller, that meant tapping a FAB to move between sections, opening full-screen overlays to filter, and reaching for the touchscreen for almost everything. The goal of this fork is:

- **Gamepad first**: every action reachable from D-pad, A/B/X/Y and shoulder buttons, with a visible focus ring and an on-screen legend.
- **Landscape first**: fixed filter panel next to the list, tabs on top, no wasted vertical space. Portrait still works, with a bottom bar and modal sheets.
- **Flat, minimal look**: a single accent colour, light/dark theme, Manrope typeface, no cards-inside-cards.
- **Works with or without a controller**: every flow is designed for D-pad/buttons first and then checked with touch.

## What changed from Milou

### First run (new)
- Onboarding wizard: what Dogmatix does → pick the root ROMs folder → import a sources JSON. Every step can be skipped; B goes one step back; it never shows again once finished.
- The app ships **without default sources** (`consoles.json` is empty). Import a JSON (see *Sources*) or add consoles by hand.

### Shell and navigation
- The floating "Menu" button is gone. Landscape shows numbered tabs on top (Library · Downloads · Sources · Settings); portrait shows a bottom bar with a downloads badge.
- Full-screen immersive mode with swipe-to-reveal system bars; status bar icons follow the theme.
- Free space of the download volume shown in the shell; a rescan indicator next to the tabs.

### Gamepad support (new)
- Controller detection via `InputManager`; the legend at the bottom only appears when a gamepad is connected and is contextual to the focused area.
- **ZL / ZR** (L2/R2, button or axis) switch section · **LB / RB** switch panel (filters ⇄ list) · **R3** folds / unfolds the filter panel (opens / closes the filter sheet in portrait) · **X** opens the game details popup on the focused row (B or X closes it) · **Y** focuses search · **A** confirms · **B** undoes one layer at a time (sheet/dropdown → search → text → filters → focus back to tabs) and never closes the app.
- The legend is kept short on the list and filter panels (B and LB / RB are left out) so it fits on one line even on 4:3 screens; it scrolls sideways if it still does not fit.
- Every interactive control has a visible focus ring; when changing section the focus parks and the first D-pad press lands on the active tab.

### Library
- Fixed filter panel (Console, Region, Language, Tag, Favourites, Source, Sort) with ◀ ▶ quick change and multi-select dropdowns; portrait uses console chips and a modal filter sheet. Sort offers A → Z, Z → A and size in both directions (ties broken by name, so paging never repeats a game).
- The panel folds into a thin rail (arrow at its bottom-right corner, or R3) to give the list the full width; the slide is animated at 60 fps on the K56, with names, search box and focus ring following the panel edge and ellipses appearing progressively.
- Rows show a short console chip (NES, SNES, PS2…), tags and file extension. When the list is narrower than 560 dp (4:3 screens, or the panel open on a narrow one) rows stack the name above the tags instead of the single-line table, so names are never squeezed out.
- ✓ mark on games that are already in the download folder (`LibraryIndexService`, matched by name with and without extension).
- ★ **Favourites**: Select (or the ★ button in the details popup) stars a game; a "Favourites" filter row shows only starred games. Stored in their own Room table keyed by console + file name, so they survive rescans.
- Lenient search: accents, punctuation and doubled letters are ignored, so "yugioh" finds *Yu-Gi-Oh!* and "virtual tenis" finds *Virtua Tennis* (`SearchNormalizer`).
- Favorite languages (set in Settings) appear first in the language filter.
- Game details popup (X on a row): cover art, year, developer, genres and synopsis from RAWG, falling back to TheGamesDB; results (hits and misses) are cached in Room. API keys go in a git-ignored `.env` (`RAWG_API_KEY`, `THEGAMESDB_API_KEY`); without them the popup just says "no data".

### Downloads
- Each row shows the same tags as the library, so repeated versions of a game can be told apart.
- The downloads list is persisted in Room (`DownloadHistory`), so it survives app restarts; in-flight items come back as *Stopped* and can be retried (debrid downloads resume where they left off, see below).
- Rows can also show the RomM upload state (*↑ RomM n%*, *Uploaded*, *failed* + retry).
- **Multi-selection**: Select (long press with touch) ticks rows and turns the summary line into an action bar with only the actions the ticked rows accept — retry, pause, stop, delete — with a single confirmation for the lot. While selecting, A ticks, Y ticks/unticks everything, X deletes and B drops the selection.
- **Pause / resume for torrents**: the pause button parks a download keeping its data; play resumes from the pieces already on disk, even after the app was closed in between.
- Direct HTTP and RomM downloads keep their partial file when they stop or fail and the retry continues it with a `Range` request instead of starting over.

### Sources
- Same structure as Milou, restyled (flat cards, tonal buttons) and navigable with the controller: dialogs open with the field focused, B closes them, deleting a console or URL asks for confirmation (focus starts on *Cancel*).
- URLs can be edited in place, not only added and removed. Deleting a manufacturer also deletes its consoles.
- **Export / import**: the share icon writes a `dogmatix-sources.json` and opens the Android share sheet; the import icon picks a JSON, replaces every source, rescans everything and re-indexes the library. The format is the one of upstream's `consoles.json` plus display names, a `short` label and folder `aliases` per console — export from any device to get one; no sources file is shipped in this repository.
- Each console stores its **short name** (the chip label: GBA, PS2…) and its **folder aliases** (names that count as its download folder). Empty values fall back to the built-in tables, and both are editable in the console dialog, so imported or hand-made consoles behave like the built-in ones.
- If a newer build ships additions in `consoles.json`, they are merged into the existing sources (nothing the user changed is touched) and only the new entries are scraped.
- Download-folder card with the resolved path per console.
- When "separate subfolders by console" is on and the chosen directory already contains a folder used by popular frontends (`gb`, `psx`, `snes`… — ES-DE, RetroArch, Batocera, EmulationStation naming), Dogmatix reuses it instead of creating a new one (`ConsoleFolderAliases`, `ConsoleDownloadPathResolver`).
- Merge folders dialog: when several folders for the same console live side by side (e.g. `gba` and `Gameboy Advance`), move everything into the one you pick.

### Debrid services (TorBox, Real-Debrid)
- Settings → *Debrid service* (Off / TorBox / Real-Debrid) + the matching *API key* row (with a *Test* button that reports the account). When a service is selected, torrent rows are handed to it: the magnet is submitted, the row shows *TorBox n%* / *Real-Debrid n%* while the service fetches it (cached torrents finish at once), then the file is downloaded over plain HTTP through the usual path (speed limit, per-console folder, auto-unzip). Cancelling deletes it from the account; the file is matched by name and size (`DebridMatcher`). Retrying a failed torrent re-reads the setting, so the service can be switched between attempts.
- [TorBox](https://torbox.app): key from *Settings → API*. [Real-Debrid](https://real-debrid.com/apitoken): needs a **premium** account (free accounts can validate the key but `addMagnet` answers `permission_denied`, which the row reports as *Failed*); the wanted file is selected with `selectFiles` before the service fetches it, and the link goes through `unrestrict/link`.
- **Resumable**: the service's torrent/file ids are kept in the download history, the partial file stays on disk, and a retry after the app was killed continues with an HTTP `Range` request instead of starting over (debrid links serve ranges; plain HTTP sources still restart from zero).
- **Limitation**: TorBox zips torrents with 100+ files and its cache is shared, so for the big Myrient/No-Intro sets it can only hand back one huge `.zip`, not single ROMs (`allow_zip=false` only affects torrents nobody has cached yet). When that happens Dogmatix removes the torrent from the account and silently falls back to the direct torrent download for that file, so the toggle is safe to leave on.

### RomM
- Settings → *RomM server*: URL, API token (`rmm_…` client token or `user:password`), *Test connection*, *Upload finished downloads*, and one stepper per console to pick the RomM platform it uploads to. Suggestions come from the same folder-alias table used for frontend folders (`gba`, `psx`, `snes`…); *Apply suggestions* maps every unmapped console at once.
- Every download that finishes while the switch is on (and whose console is mapped) is uploaded with RomM's chunked API (`/api/roms/upload/start` → chunks → `complete`); extracted archives upload each extracted file. The Downloads row shows *↑ RomM n%*, *Uploaded* or *failed* with a retry button. Uploads are not resumed after the process dies — use the retry button. Plain `http://` servers are allowed (cleartext traffic is enabled for the app); self-signed HTTPS is not supported.
- **RomM as a source**: in Sources → *Add URL*, pick one of the server's platforms (or type `romm://<slug>`) and the console indexes every ROM RomM has for it; downloads go through RomM's `/api/roms/{id}/content/…` with the account credentials, and files that came from RomM are never uploaded back.
- RomM only lists a ROM once it has scanned it, and scanning is not exposed through its REST API: either run a scan from the RomM web UI after uploading, or start RomM with `ENABLE_RESCAN_ON_FILESYSTEM_CHANGE=true` so uploaded files are picked up automatically.

### Settings
- Theme (System / Light / Dark / **True black**, a pure `#000000` background for AMOLED screens) and accent colour (5 presets), persisted in DataStore.
- Language (System / English / Spanish).
- Download directory, concurrent downloads and speed limit as steppers (◀ ▶ with the controller), switches for auto-unzip and per-console subfolders, favorite languages picker, "About & contact".
- *Maximum search results* stepper (50 / 100 / 250 / 500 / Unlimited, default 100): how many games a library search loads at once; *Load more* fetches the next batch, *Unlimited* lists everything the filters match.
- *Metadata timeout* stepper (10–180 s, default 20): how long a rescan or a direct torrent download waits for a magnet's file list before giving up — raise it on slow trackers/DHT.
- *Debrid service* stepper (Off / TorBox / Real-Debrid) with a single *API key* row for the selected service (dialog with *Test*); the key is masked in the row and stored only on the device.
- *Gamepad layout* stepper (Xbox / Nintendo / PlayStation): draws the button legend the way your pad is printed — Xbox A/B/X/Y with `LB · RB` / `LT · RT`, Nintendo the same letters in Super Famicom colours (A red, B yellow, X blue, Y green) with `L · R` / `ZL · ZR`, PlayStation ✕ ○ □ △ each in its own colour with `L1 · R1` / `L2 · R2`. Names and colours only: A (✕) always accepts and B (○) always goes back.
- *Swap A/B and X/Y* switch, for pads that report their face buttons the other way round: it moves the actions and leaves the legend untouched, dialogs and the filter sheet included.
- **Frontends**: *Frontend shortcuts* drops a `.dgmtx` shortcut into every console folder; *Configure ES-DE* and *Configure iiSU* do the whole frontend setup in one button, and *Set up Daijishō* hands over the values its emulator form needs — see [FRONTENDS.md](FRONTENDS.md) and the *Deep links* section below.
- Two-column layout in landscape.

### Under the hood
- Package renamed to `com.cortinadev.dogmatix` (upstream: `com.santiifm.milou`); application class, database and theme renamed accordingly. The app id changed, so Dogmatix installs as a separate app and does not update over a Milou install.
- Room schema at v9 with explicit migrations (search key, download history, game metadata cache, per-console short name and folder aliases, favourites, debrid resume ids).
- All source handling lives in `SourcesRepository`; the JSON format is read and written by one pure `SourcesJson` object shared by the bundled asset, the Room `urls` column and export/import. Sources are routed by URL: `magnet:`/`.torrent` → libtorrent, `romm://` → the RomM API, anything else → HTML directory scraping.
- Downloads are routed in `DownloadService.perform()`: debrid service (when selected; `DebridClient` implemented by `TorBoxClient` and `RealDebridClient`) → HTTP with resume; torrent → libtorrent; HTTP otherwise (with the RomM credentials when the file comes from the RomM server). Integrations talk to their APIs with a tiny `JsonHttp` helper over `HttpURLConnection` + Gson — no OkHttp.
- Secrets (TorBox / Real-Debrid keys, RomM token) live only in the app's DataStore; nothing is baked into the APK or the repository.
- Unit tests for the pure logic: search/parsing (`SearchNormalizer`, `ConsoleFolderAliases`, `GameTitleCleaner`, `LibraryKeys`), sources (`SourcesJson`), deep links and shortcuts (`DeepLinkParser`, `DeepLinkResolver`, `DgmtxFile`), frontends (`EsdeXml`, `IisuJson`, `DaijishoSetup`), debrid and RomM (`DebridMatcher`, `RommPlatformMapper`, `RommSource`), and the gamepad legend (`GamepadLayout`).
- Removed: FAB, `SearchSection`, old filter overlays/dropdowns, `RomList`, `SmallButtons`, `CommonButton`, `Spacing`/`Layout`.

## How it works (inherited from Milou)

A file called **Burnout Paradise (En,Es,Fr) (NTSC).zip** in a torrent becomes
**Name: Burnout Paradise · Tags: Languages [En, Es, Fr], Region [NTSC], Console: Xbox 360, Manufacturer: Microsoft, Extension: .zip**.

1. **First launch**: the app starts with no sources (`consoles.json` is empty). Add consoles by hand or import a sources JSON from the Sources tab (export one from a device that already has sources; source files are deliberately not part of this repository).
2. **Search**: filter by console, region, language, type or extension.
3. **Download**: press A / tap on a game; torrents and direct HTTP links are both supported.
4. **Manage**: follow progress in Downloads; archives are extracted automatically.
5. **Configure**: download folder, per-console paths, speed limit, concurrency, theme.

## Building

Requirements: JDK 17, Android SDK platform 36, build-tools 36.0.0.

```bash
git clone https://github.com/cortinadev/dogmatix.git
cd dogmatix

./gradlew assembleDebug      # debug APK (applicationId com.cortinadev.dogmatix.debug)
./gradlew assembleRelease    # release APK (minified, signed with the debug keystore)
./gradlew test               # JVM unit tests
./gradlew lint
```

The debug build uses the `.debug` application-id suffix so it can be installed next to a release build. The package is `com.cortinadev.dogmatix` (renamed from upstream's `com.santiifm.milou`).

### Project structure
```
app/src/main/java/com/cortinadev/dogmatix/
├── data/        Room entities/DAOs, repositories, services (scraping, torrents, downloads, extraction)
├── di/          Hilt modules
├── ui/
│   ├── common/      Gamepad detection and shortcut bus
│   ├── components/  AppShell, TagChip, FocusHighlight, Stepper, GamepadLegend
│   ├── screens/     onboarding, home (Library), download, sources, settings, contact
│   └── theme/       DogmatixTheme, palettes, ThemeMode, accent presets
└── util/        File-name parsing, folder aliases, search normalization, storage helpers
app/src/main/assets/consoles.json   Default sources (empty in Dogmatix)
app/src/test/                       JVM unit tests
```

## Tech stack
- **UI**: Jetpack Compose, Material 3, Navigation Compose
- **DI**: Hilt (KSP)
- **Persistence**: Room + FTS, DataStore Preferences
- **Torrents**: libtorrent4j · **HTTP**: HttpURLConnection, Jsoup
- **Archives**: 7-Zip-JBinding-4Android (ZIP/7z)
- **Concurrency**: Kotlin Coroutines + Flow

## Deep links

Other apps and frontends can open the library with filters already applied:

```
dogmatix://library?console=nintendo_snes&region=USA,Europe&lang=En&type=GAME&q=mario&fav=1
```

| Parameter | Meaning |
|---|---|
| `console` | Console ids as shown in Sources (comma-separated) |
| `region`, `lang`, `type`, `filetype`, `tag` | Library tags (comma-separated); the kind is inferred from the value |
| `q` | Search text |
| `fav` | `1`/`true` shows favourites only, `0`/`false` everything |

Try it with `adb shell am start -a android.intent.action.VIEW -d "dogmatix://library?console=nintendo_snes&q=mario"`. A link opened while the app is on another tab switches to the Library; one opened during onboarding is applied once onboarding finishes.

Dogmatix also opens `.dgmtx` shortcut files (a text file carrying one of these links), and
Settings → *Frontend shortcuts* drops one into every platform folder — that's how it shows up
as an "emulator" inside ES-DE, iiSU or Daijishō. ES-DE and iiSU also get a one-button setup
in Settings ("Configure ES-DE" / "Configure iiSU"); Daijishō has no importable emulator
configuration, so "Set up Daijishō" hands over the values to type into it. See
[FRONTENDS.md](FRONTENDS.md) for the frontend configuration.

## Controller cheat sheet

| Button | Action |
|---|---|
| D-pad | Move focus |
| A | Select / download |
| B | Back one layer (close sheet, clear search, back to tabs) |
| X | Game details popup (B or X closes) |
| Y | Focus search |
| Select | Star / unstar the focused game · in Downloads, tick rows for multi-selection |
| LB / RB | Switch panel |
| R3 | Fold / unfold the filter panel |
| ZL / ZR | Previous / next section |

Everything is also reachable by touch; the legend only appears while a controller is connected, and button names and colours follow the *Gamepad layout* setting (Xbox / Nintendo / PlayStation).

## Roadmap

Planned features, in no particular order:

- **RomM: mark games already in RomM** as owned in the library (today only uploads are supported).
- **More debrid providers** (AllDebrid, Premiumize) behind the *Debrid service* setting.
- **Favourites sync** across devices via the sources export.

Ideas and requests are welcome as [issues](https://github.com/cortinadev/dogmatix/issues).

## Disclaimer

This app is for educational purposes only. Users are responsible for ensuring they have the legal right to download any content.
