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
- **ZL / ZR** (L2/R2, button or axis) switch section · **LB / RB** switch panel (filters ⇄ list) · **X** opens the game details popup on the focused row (B or X closes it) · **Y** focuses search · **A** confirms · **B** undoes one layer at a time (sheet/dropdown → search → text → filters → focus back to tabs) and never closes the app.
- Every interactive control has a visible focus ring; when changing section the focus parks and the first D-pad press lands on the active tab.

### Library
- Fixed filter panel (Console, Region, Language, Type, File type, Sort) with ◀ ▶ quick change and multi-select dropdowns; portrait uses console chips and a modal filter sheet.
- Rows show a short console chip (NES, SNES, PS2…), tags and file extension.
- ✓ mark on games that are already in the download folder (`LibraryIndexService`, matched by name with and without extension).
- Lenient search: accents, punctuation and doubled letters are ignored, so "yugioh" finds *Yu-Gi-Oh!* and "virtual tenis" finds *Virtua Tennis* (`SearchNormalizer`).
- Favorite languages (set in Settings) appear first in the language filter.
- Game details popup (X on a row): cover art, year, developer, genres and synopsis from RAWG, falling back to TheGamesDB; results (hits and misses) are cached in Room. API keys go in a git-ignored `.env` (`RAWG_API_KEY`, `THEGAMESDB_API_KEY`); without them the popup just says "no data".

### Downloads
- Each row shows the same tags as the library, so repeated versions of a game can be told apart.
- The downloads list is persisted in Room (`DownloadHistory`), so it survives app restarts; in-flight items come back as *Stopped* and can be retried.

### Sources
- Same structure as Milou, restyled (flat cards, tonal buttons) and navigable with the controller: dialogs open with the field focused, B closes them, deleting a console or URL asks for confirmation (focus starts on *Cancel*).
- URLs can be edited in place, not only added and removed. Deleting a manufacturer also deletes its consoles.
- **Export / import**: the share icon writes a `dogmatix-sources.json` and opens the Android share sheet; the import icon picks a JSON, replaces every source, rescans everything and re-indexes the library. The format is the one of upstream's `consoles.json` plus display names, a `short` label and folder `aliases` per console — export from any device to get one; no sources file is shipped in this repository.
- Each console stores its **short name** (the chip label: GBA, PS2…) and its **folder aliases** (names that count as its download folder). Empty values fall back to the built-in tables, and both are editable in the console dialog, so imported or hand-made consoles behave like the built-in ones.
- If a newer build ships additions in `consoles.json`, they are merged into the existing sources (nothing the user changed is touched) and only the new entries are scraped.
- Download-folder card with the resolved path per console.
- When "separate subfolders by console" is on and the chosen directory already contains a folder used by popular frontends (`gb`, `psx`, `snes`… — ES-DE, RetroArch, Batocera, EmulationStation naming), Dogmatix reuses it instead of creating a new one (`ConsoleFolderAliases`, `ConsoleDownloadPathResolver`).
- Merge folders dialog: when several folders for the same console live side by side (e.g. `gba` and `Gameboy Advance`), move everything into the one you pick.

### Settings
- Theme (System / Light / Dark) and accent colour (5 presets), persisted in DataStore.
- Download directory, concurrent downloads and speed limit as steppers (◀ ▶ with the controller), switches for auto-unzip and per-console subfolders, favorite languages picker, "About & contact".
- Two-column layout in landscape.

### Under the hood
- Package renamed to `com.cortinadev.dogmatix` (upstream: `com.santiifm.milou`); application class, database and theme renamed accordingly. The app id changed, so Dogmatix installs as a separate app and does not update over a Milou install.
- Room schema at v6 with explicit migrations (search key, download history, game metadata cache, per-console short name and folder aliases).
- All source handling lives in `SourcesRepository`; the JSON format is read and written by one pure `SourcesJson` object shared by the bundled asset, the Room `urls` column and export/import.
- Unit tests for the pure logic (`SearchNormalizerTest`, `ConsoleFolderAliasesTest`, `GameTitleCleanerTest`, `SourcesJsonTest`).
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

## Controller cheat sheet

| Button | Action |
|---|---|
| D-pad | Move focus |
| A | Select / download |
| B | Back one layer (close sheet, clear search, back to tabs) |
| X | Game details popup (B or X closes) |
| Y | Focus search |
| LB / RB | Switch panel |
| ZL / ZR | Previous / next section |

Everything is also reachable by touch; the legend only appears while a controller is connected.

## Roadmap

Planned features, in no particular order:

- **Favourites**: mark games as favourites and filter the library by them.
- **RomM integration**: sync with a [RomM](https://github.com/rommapp/romm) server.
- **Debrid / TorBox support**: download through debrid services instead of (or in addition to) direct torrenting.
- **Deep-link API**: open the app with filters pre-applied (console, region, search term…) from other apps or frontends.

Ideas and requests are welcome as [issues](https://github.com/cortinadev/dogmatix/issues).

## Disclaimer

This app is for educational purposes only. Users are responsible for ensuring they have the legal right to download any content.
