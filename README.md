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
- Onboarding wizard: what Dogmatix does → pick the root ROMs folder → preferred versions (region and languages, see *Library*) → import a sources JSON → connect ES-DE (only when it is installed). Every step can be skipped; B goes one step back; it never shows again once finished.
- The app ships **without default sources** (`consoles.json` is empty). Import a JSON (see *Sources*) or add consoles by hand.

### Shell and navigation
- The floating "Menu" button is gone. Landscape shows numbered tabs on top (Library · Downloads · Collection · Sources · Settings); portrait shows a bottom bar with a downloads badge.
- Full-screen immersive mode with swipe-to-reveal system bars; status bar icons follow the theme.
- Free space of the download volume shown in the shell; a rescan indicator next to the tabs, with the percentage and the time left.

### Gamepad support (new)
- Controller detection via `InputManager`; the legend at the bottom only appears when a gamepad is connected and is contextual to the focused area.
- **ZL / ZR** (L2/R2, button or axis — one press is one change, also on pads that send both) switch section · **LB / RB** switch panel (filters ⇄ list in the Library, chips ⇄ list in Downloads, tools ⇄ content in Collection, categories ⇄ rows in Settings) · **R3** folds / unfolds the filter panel (opens / closes the filter sheet in portrait) · **X** opens the game details popup on the focused row (B or X closes it) · **Y** focuses search · **START** on a queued download enters move mode · **◀ ▶** in the Library list jump to the next or previous letter · **A** confirms · **B** undoes one layer at a time (sheet/dropdown → search → text → filters → focus back to tabs) and never closes the app.
- While selecting in Downloads, and in the Collection tools, A ticks or picks, Y ticks everything and X acts on what is ticked (in Downloads, X deletes).
- The legend is kept short on the list and filter panels (B and LB / RB are left out) so it fits on one line even on 4:3 screens; it scrolls sideways if it still does not fit.
- Every interactive control has a visible focus ring; when changing section the focus parks and the first D-pad press lands on the active tab.

### Library
- Fixed filter panel (Console, Region, Language, Tag, Genre, Decade, Favourites, Source) with ◀ ▶ quick change and multi-select dropdowns; portrait uses console chips and a modal filter sheet. Whatever is filtered shows as a thin row of chips under the search box (A on a chip removes it; *Clear* appears with two or more).
- The sort button, to the right of the search box, offers A → Z, Z → A, size in both directions, console (when more than one is in play) and decade in both directions; ties are broken by name, so paging never repeats a game.
- The panel folds into a thin rail (arrow at its bottom-right corner, or R3) to give the list the full width; the slide is animated at 60 fps on the K56, with names, search box and focus ring following the panel edge and ellipses appearing progressively.
- Rows show a short console chip (NES, SNES, PS2…), tags and file extension. When the list is narrower than 560 dp (4:3 screens, or the panel open on a narrow one) rows stack the name above the tags instead of the single-line table, so names are never squeezed out.
- **Square covers** in front of every row (Settings → *Covers in the list*, on by default): box art from [libretro-thumbnails](https://github.com/libretro-thumbnails) — the covers RetroArch shows, no API key needed — found by the No-Intro / Redump file name, else an image the details popup already cached. Cropped to fill the square; games without a cover get a square in their console's colour with its short name. When the preferred version has no cover, up to three other versions are tried, and with a RomM server its covers come first. Lookups (misses included) are cached in Room and the images on disk, so each game costs at most one lookup. Downloads shows the same covers in front of its rows.
- **Coloured chips**: the console chip takes its console's colour (picked per console in Sources, else a default per family — Nintendo red, PlayStation blue…) and tags are tinted by group — region, language, revision, demo / beta / hack — with colours you can change in Settings → *Tag colours*. Extensions and other tags stay neutral. The same colours apply in the details popup and in Downloads.
- **One row per game**: the versions of a game (regions, revisions, demos, sources) are grouped into a single row, with a small `4+` badge saying how many more there are, and the discs of a multi-disc release travel together (`2 discs`, total size). A downloads the **preferred version** — every disc of it — picked from your *Preferred region* and favourite languages: releases before demos, betas, prototypes and bad dumps, then your region and its closest fallbacks (Spain → Europe → World → USA…), a version in one of your languages, the latest revision and verified `[!]` dumps. Nothing is hidden: the details popup lists every version, the preferred one first, in a box three rows tall that scrolls (LB / RB jump between it and the buttons, which always stay on screen), and A on any of them downloads it. Filters still apply (with *Region: Japan* the row downloads the best Japanese version), and *Load more* pages by game, so all of a game's versions arrive together.
- ✓ mark on games that are already in the download folder (`LibraryIndexService`, matched by name with and without extension).
- ★ **Favourites**: Select (or the ★ button in the details popup) stars a game; a "Favourites" filter row shows only starred games. Stored in their own Room table keyed by console + file name, so they survive rescans.
- Lenient search: accents, punctuation and doubled letters are ignored, so "yugioh" finds *Yu-Gi-Oh!* and "virtual tenis" finds *Virtua Tennis* (`SearchNormalizer`). A small ✕ clears the box with touch; with a controller, B does.
- **Search the whole app**: the same box also finds downloads, sources (console names, aliases, URLs and mirrors), settings, and Collection tools and collections, grouped under the games; "Also in: …" jumps to a group, A opens the result in its section and B comes back to the search.
- **Recent searches**: the last ten searches that found something show under the empty search box — ▼ reaches them, A runs one, X removes it, *Clear history* empties the list.
- Favorite languages (set in Settings or in the first-run tour) appear first in the language filter and, together with the preferred region, pick the version A downloads.
- Game details popup (X on a row): cover art, year, developer, genres and synopsis from RAWG, falling back to TheGamesDB; results (hits and misses) are cached in Room. API keys go in a git-ignored `.env` (`RAWG_API_KEY`, `THEGAMESDB_API_KEY`); without them the popup just says "no data" (it still shows the library cover when there is one). The title is always the library name, and a hit is only taken when its sequel number matches. *Collection* adds the game to one of your collections.
- **Similar games**: *Similar* in the details popup filters the library by the games similar to that one, on **every console**. First its franchise, found from the title — the words before the subtitle, weighted by how rare they are in your library, so *zelda* or *splinter cell* count and *super* or *world* do not — then related games (*Ghost Recon* for a *Splinter Cell*). When the franchise turns up fewer than eight games, games of the same genre follow: those whose details you looked up before and, with a RAWG key, the most popular games of that genre on your consoles. The region, language, tag, favourites and source filters still apply; a "Similar to …" chip sits under the search box, and A on it (or B) goes back to the full list.
- **Recommendations**: with no search, filters or sort order, the Library opens on shelves picked from what you star, download and keep on disk — *For you* (the genres you play on the consoles you use, plus related games), *Complete the series*, *More for <console>* and, with a RAWG key, *Popular on your platforms*; with nothing to go on yet, *Highlights on <console>*. Games you already have, betas and BIOS files are left out, and games with a cover go first. A downloads the preferred version, X opens the details, Select stars; ▲ ▼ change shelf, ◀ ▶ move along it. *All games*, a search or a filter shows the list.
- **Genre and Decade**: genres and release years come from libretro-database's DATs (cached for a month) and from the details you open (RAWG, TheGamesDB, RomM). Disc-based systems with no such DAT (PlayStation, PS2, Saturn, Dreamcast, GameCube, Wii, Xbox…) only count the games whose details were looked up.
- **Jump by letter**: with the list sorted by name, ◀ ▶ jump to the next or previous letter (◀ on the first letter still goes to the filter panel), loading pages as needed.

### Downloads
- Each row shows the same tags (and colours) as the library, so repeated versions of a game can be told apart.
- The downloads list is persisted in Room (`DownloadHistory`), so it survives app restarts, and **so does the queue**: whatever was downloading when the app or the device went down starts again on the next launch, in its queue order (web and debrid downloads continue from their partial file). A paused web download comes back paused.
- **Queue**: START on a queued row enters move mode — ▲ ▼ move it, LB puts it next, Y holds it, A or B leave (with touch, the row's buttons do the same). A held download keeps its place but does not start until released. A search box and chips with counts (All · In progress · Queued · Held · Failed · Completed) narrow the list, and global buttons — *Pause all*, *Resume all*, *Retry failed*, *Clear completed* (the files stay) — appear when they have something to act on. *Retry failed* only retries the failed rows the list is showing (search and chip included).
- **Notifications**: the download notification shows how many are running and queued, progress and speed, with *Pause all* / *Resume* / *Cancel all*; tapping it opens Downloads. *Notice when downloads finish* (on by default) says how many completed and failed once the queue empties.
- Rows can also show the RomM upload state (*↑ RomM n%*, *Uploaded*, *failed* + retry).
- **Multi-selection**: Select (long press with touch) ticks rows and turns the summary line into an action bar with only the actions the ticked rows accept — retry, pause, stop, delete — with a single confirmation for the lot. While selecting, A ticks, Y ticks/unticks everything, X deletes and B drops the selection.
- **Pause / resume for every download**: the pause button (A on the row) parks it keeping its data — torrents their pieces, web and debrid downloads their partial file — and play resumes from there, even after the app was closed in between.
- **Why it failed**: a failed row says why — no connection, timed out, server error, not on the server any more, not enough space, folder not accessible, no torrent metadata, debrid account problem… — and the reason survives a restart.
- **Automatic retries** (Settings, default 3): failures that may go away on their own (network, server, rate limit, a torrent without peers yet) are retried after 1, 5 and 15 minutes; the row shows when. Missing files, full disks or account problems are left for you. A manual retry, stop or delete cancels the pending one.
- **Downloads per server** (Settings, default no limit): caps how many web downloads talk to the same server at once, for servers that throttle or ban clients with many connections. A download waiting for its server never holds one of the global download slots.
- **Multi-disc games**: once every disc of a release is in (as many as the source lists), they move into a `<Game>.m3u/` folder next to a `<Game>.m3u` playlist — ES-DE shows the folder as a single game and the emulator swaps discs from the playlist; a disc downloaded later joins the folder. Only for systems whose emulators read playlists (PlayStation, Saturn, Sega CD, Dreamcast, GameCube, PC Engine CD, 3DO, Neo Geo CD, Amiga, C64, MSX…; not PS2 or Xbox). Settings → *Multi-disc games (.m3u)* turns it off.
- Direct HTTP and RomM downloads keep their partial file when they stop or fail and the retry continues it with a `Range` request instead of starting over — only when the server confirms it is the same file (`If-Range` with its ETag or date). A transfer cut short is never taken as complete, and a web page served instead of the file fails at once. *Download again* always starts from zero.
- **Best address first**: Dogmatix keeps track of how each server, debrid service and torrent behaves (success rate and speed, older results counting less) and tries a source's main address and mirrors best first; a failure that will not go away by itself moves on to the next address.
- **Verification**: finished downloads are hashed in the background (CRC32 and SHA-1, inside kept ZIPs too) and checked against RomM's hashes for games downloaded from RomM, or against the console's No-Intro / Redump DAT (fetched from libretro-database when needed, or imported in Settings → *DAT files*). The row says *Verified · No-Intro*, *Does not match* (with the detail) or *Not verified* and why. Nothing is deleted because of it.
- Deleting a finished download moves its files to the trash (undo in Collection → *History*); partial files are deleted for good.
- Archives keep their folder structure when extracted (`Disc 1/…`, `Disc 2/…`); unsafe paths are refused and clashing names renamed.
- **Seed after download** (Settings, off by default): finished torrents keep uploading for 30 min, 1 hour, until a 1.0 ratio, or without limit; switching it off stops every seed in progress, and deleting a finished row stops its own.

### Collection (new)
A section between Downloads and Sources to look after the games already on disk. It analyses the download folders on demand (X, or *Analyse*), keeps the result and refreshes a console after each download.
- **Summary**: games on disk, coverage of your catalogue, favourites on disk, storage volumes, per-console figures, latest downloads, the biggest games, regions and languages; A opens the Library filtered.
- **Health**: twelve checks (lost folder permission, low space, empty files, broken `.cue` / `.m3u` / `.gdi` references, incomplete sets, games in another console's folder, leftover partial downloads, multi-disc sets without a playlist, archives already extracted, empty folders, foreign extensions, sources never scanned), each with its fix.
- **Duplicates**: the same game twice — same files, different versions, or same size and content — with a suggestion of which copy to keep.
- **Repair**: create missing `.m3u` playlists, move games to their console's folder, delete empty folders, leftovers and archives already extracted, with a preview.
- **Free up space**: the biggest items first, plus the torrent and cover caches; deletes for good.
- **SD storage**: a second library folder (typically on the SD card). Consoles can live there, suggestions say which to move when internal space runs low, and a download that does not fit goes to the same console folder on the other one.
- **Move library**: moves the whole library, or some consoles, to another folder, pausing downloads while it copies; it can be interrupted, resumed and undone.
- **History**: what was deleted, repaired, moved or imported, with undo. Deletions from Downloads, the Library, Duplicates and Repair go to a hidden trash (`.dogmatix-trash`, which ES-DE skips) that empties itself after 7 or 30 days.
- **Collections**: your own lists, by hand (*Collection* in the details popup) or by rules (console, region, language, tag, genre, decade, favourites, on disk, source, size, text), plus the RomM server's collections. A opens the Library showing the collection.

### Sources
- Same structure as Milou, restyled (flat cards, tonal buttons) and navigable with the controller: dialogs open with the field focused, B closes them, deleting a console or URL asks for confirmation (focus starts on *Cancel*).
- URLs can be edited in place, not only added and removed. Deleting a manufacturer also deletes its consoles.
- **Reserve addresses (mirrors)**: a web source can list other addresses that serve the same folder (one per line in the URL dialog). When a download or a rescan of the main address fails, the attempts go round the mirrors with the same file path. They travel in the sources export as `mirrors`. Under each URL, its server's success rate and speed ("97% successful · 4.1 MB/s").
- **Incremental rescans**: *Rescan all* only reads what changed — web listings ask the server first (ETag / Last-Modified), torrents already indexed with the same settings are not fetched again, and torrent file lists are cached on the device — and each source replaces only its own games, so one that fails keeps what it had. *Full rescan* reads everything again. Sources are scanned in parallel (two per server), busy servers are retried with growing waits (honouring `Retry-After`), mirrors stand in for a failing address, and an empty listing never wipes a source. The scan keeps going when you leave Sources.
- **Scan report**: a *Last scan* card lists every source with its result and a readable reason, and *Retry failed* scans only those again.
- **Automatic scan** (Settings, off by default): daily or weekly, on Wi-Fi only and, optionally, while charging; always incremental, with a notification of the new games it finds.
- **Export / import**: the share icon writes a `dogmatix-sources.json` and opens the Android share sheet; the import icon picks a JSON, replaces every source, rescans everything and re-indexes the library (the last import can be undone from Collection → *History*). The format is the one of upstream's `consoles.json` plus display names, a `short` label, folder `aliases`, an optional chip `color` (`#RRGGBB`), the starred files as `favourites` (an import adds them to yours) and, for older consoles, their `id` per console — export from any device to get one; no sources file is shipped in this repository.
- Each console stores its **short name** (the chip label: GBA, PS2…), its **folder aliases** (names that count as its download folder) and its **chip colour**. Empty values fall back to the built-in tables, and all three are editable in the console dialog (the colour with a gamepad-friendly picker), so imported or hand-made consoles behave like the built-in ones. A dot in each console card shows its colour.
- If a newer build ships additions in `consoles.json`, they are merged into the existing sources (nothing the user changed is touched) and only the new entries are scraped.
- Download-folder card with the resolved path per console.
- When "separate subfolders by console" is on and the chosen directory already contains a folder used by popular frontends (`gb`, `psx`, `snes`… — ES-DE, RetroArch, Batocera, EmulationStation naming), Dogmatix reuses it instead of creating a new one (`ConsoleFolderAliases`, `ConsoleDownloadPathResolver`).
- Merge folders dialog: when several folders for the same console live side by side (e.g. `gba` and `Gameboy Advance`), move everything into the one you pick.

### Debrid services (TorBox, Real-Debrid)
- Settings → *Debrid service* (Off / TorBox / Real-Debrid) + the matching *API key* row (with a *Test* button that reports the account). When a service is selected, torrent rows are handed to it: the magnet is submitted, the row shows *TorBox n%* / *Real-Debrid n%* while the service fetches it (cached torrents finish at once), then the file is downloaded over plain HTTP through the usual path (speed limit, per-console folder, auto-unzip). Cancelling deletes it from the account; the file is matched by name and size (`DebridMatcher`). Retrying a failed torrent re-reads the setting, so the service can be switched between attempts.
- [TorBox](https://torbox.app): key from *Settings → API*. [Real-Debrid](https://real-debrid.com/apitoken): needs a **premium** account (free accounts can validate the key but `addMagnet` answers `permission_denied`, which the row reports as *Failed*); the wanted file is selected with `selectFiles` before the service fetches it, and the link goes through `unrestrict/link`.
- **Resumable**: the service's torrent/file ids are kept in the download history, the partial file stays on disk, and a retry after the app was killed continues with an HTTP `Range` request instead of starting over.
- **Limitation**: TorBox zips torrents with 100+ files and its cache is shared, so for the big Myrient/No-Intro sets it can only hand back one huge `.zip`, not single ROMs (`allow_zip=false` only affects torrents nobody has cached yet). When that happens Dogmatix removes the torrent from the account and silently falls back to the direct torrent download for that file, so the toggle is safe to leave on.

### RomM
- Settings → *RomM server*: URL, API token (`rmm_…` client token or `user:password`), *Test connection*, *Upload finished downloads*, and one stepper per console to pick the RomM platform it uploads to. Suggestions come from the same folder-alias table used for frontend folders (`gba`, `psx`, `snes`…); *Apply suggestions* maps every unmapped console at once.
- Every download that finishes while the switch is on (and whose console is mapped) is uploaded with RomM's chunked API (`/api/roms/upload/start` → chunks → `complete`). Games made of several files (cue/bin, gdi, extracted folders, multi-disc `.m3u` folders) go up as one `<game>.zip`, built on the fly without compression or a copy on disk; a disc of an incomplete multi-disc game waits for the others. The Downloads row shows real progress (*↑ RomM n%*), *Uploaded* or *failed* with a retry button.
- **Resumable uploads**: an upload continues from its last confirmed chunk after a failure or when the app opens again (while RomM keeps the session, about a day), and retries on its own with the downloads' *Automatic retries* setting.
- **Self-signed HTTPS**: when *Test connection* fails because of the certificate, Dogmatix shows it (issued to, issued by, validity, SHA-256, whether the name matches) and *Trust this certificate* accepts it for that server and port only, for every request. A changed certificate is refused and shown again; *Trusted certificate* → *Forget* drops it. Plain `http://` servers are allowed too (cleartext traffic is enabled for the app).
- **Server catalogue**: Dogmatix reads what the server has for the mapped consoles and marks those games in the library with a server icon next to ✓ and ★; its covers come first in the list and the details popup. Settings → RomM adds *Upload what's missing* (games on disk the server lacks), *Sync favourites with RomM* (★ and RomM's Favourites both ways; off by default) and *RomM covers to ES-DE*.
- **In the details popup**: rating and genres, your play status (◀ ▶) and the RomM collections the game is in (A to add or remove it).
- **RomM as a source**: in Sources → *Add URL*, pick one of the server's platforms (or type `romm://<slug>`) and the console indexes every ROM RomM has for it; downloads go through RomM's `/api/roms/{id}/content/…` with the account credentials, and files that came from RomM are never uploaded back.
- RomM only lists a ROM once it has scanned it, and scanning is not exposed through its REST API: either run a scan from the RomM web UI after uploading, or start RomM with `ENABLE_RESCAN_ON_FILESYSTEM_CHANGE=true` so uploaded files are picked up automatically.

### Settings
- Organised in five categories — *General* (language, updates, gamepad; Settings opens here), *Library* (folder, sources, preferences), *Appearance* (colours, game list), *Downloads* (queue, when finished, torrents) and *Integrations* (frontends, servers) — with a panel on the left in landscape (LB / RB between the panel and the rows) and tabs on top in portrait. *About & contact* sits at the foot of the panel.
- Theme (System / Light / Dark / **True black**, a pure `#000000` background for AMOLED screens) and accent colour (5 presets), persisted in DataStore.
- *Covers in the list* switch and *Tag colours* (one colour per tag group, with *Reset*). The colour picker works with the D-pad: preset swatches (A takes one), hue / saturation / brightness rows that ◀ ▶ adjust, *Default* to go back to the built-in colour.
- Language (System / English / Spanish / German / French / Portuguese / Italian).
- **Updates**: Dogmatix looks for a new release on GitHub when it starts (at most every six hours; *Check for updates on start* turns it off) and announces it once per version. *Update* downloads the APK, checks it is a Dogmatix build signed like the installed one and opens Android's installer (the first time Android asks to allow installs from Dogmatix). The *Updates* row shows where things stand and checks on demand.
- Download directory, concurrent downloads and speed limit (one budget shared by every download, torrents included, applied at once to running ones) as steppers (◀ ▶ with the controller), switches for auto-unzip and per-console subfolders, favorite languages picker, "About & contact".
- *Maximum search results* stepper (50 / 100 / 250 / 500 / Unlimited, default 100): how many games a library search loads at once; *Load more* fetches the next batch, *Unlimited* lists everything the filters match.
- *Metadata timeout* stepper (10–180 s, default 20): how long a rescan or a direct torrent download waits for a magnet's file list before giving up — raise it on slow trackers/DHT.
- *Debrid service* stepper (Off / TorBox / Real-Debrid) with a single *API key* row for the selected service (dialog with *Test*); the key is masked in the row and stored only on the device.
- *Automatic retries* (Off / 1 / 3 / 5, default 3), *Downloads per server* (Unlimited / 1–4), *Notice when downloads finish*, and the *Multi-disc games (.m3u)* and *Verify downloads* switches (both on by default).
- *DAT files*: automatic download from libretro-database (on by default), the list of stored DATs, *Import DAT…* (.dat, .xml or .zip) and remove.
- *Automatic scan* (Off / Daily / Weekly), with *on Wi-Fi only* and *while charging only*.
- *Preferred region* stepper (Europe, Spain, France, Germany, Italy, United Kingdom, USA, Japan, Brazil, Korea, China, Australia; the default follows the device): with the favourite languages, it picks the version of a game that A downloads.
- *Seed after download* stepper (Disabled / 30 min / 1 hour / Until ratio 1.0 / Unlimited).
- *Gamepad layout* stepper (Xbox / Nintendo / PlayStation): draws the button legend the way your pad is printed — Xbox A/B/X/Y with `LB · RB` / `LT · RT`, Nintendo the same letters in Super Famicom colours (A red, B yellow, X blue, Y green) with `L · R` / `ZL · ZR`, PlayStation ✕ ○ □ △ each in its own colour with `L1 · R1` / `L2 · R2`. Names and colours only: A (✕) always accepts and B (○) always goes back.
- *Swap A/B and X/Y* switch, for pads that report their face buttons the other way round: it moves the actions and leaves the legend untouched, dialogs and the filter sheet included.
- **Frontends**: *Frontend shortcuts* drops a `.dgmtx` shortcut into every console folder; *Configure ES-DE* and *Configure iiSU* do the whole frontend setup in one button, and *Set up Daijishō* hands over the values its emulator form needs; *Sync favourites with ES-DE* keeps ★ and ES-DE's favourites together, and *Check frontends* reads the whole setup back and repairs what is missing — see [FRONTENDS.md](FRONTENDS.md) and the *Deep links* section below.

### Under the hood
- Package renamed to `com.cortinadev.dogmatix` (upstream: `com.santiifm.milou`); application class, database and theme renamed accordingly. The app id changed, so Dogmatix installs as a separate app and does not update over a Milou install.
- Room schema at v19 with explicit migrations (search key, download history, game metadata cache, per-console short name and folder aliases, favourites, debrid resume ids, per-console colour and the cover cache, an index on file names, the game grouping key and failure reasons, source scans, download resume / reliability / verification data, queue order, RomM uploads, genres and years, the RomM catalogue, and history, trash, collections and storage redirects). The v12 migration computes the grouping key of everything already indexed, so the library is grouped from the first launch.
- All source handling lives in `SourcesRepository`; the JSON format is read and written by one pure `SourcesJson` object shared by the bundled asset, the Room `urls` column and export/import. Sources are routed by URL: `magnet:`/`.torrent` → libtorrent, `romm://` → the RomM API, anything else → HTML directory scraping. `SourceScanService` runs the scans in parallel, each source replacing only its own rows, and the automatic scan is a `JobScheduler` job.
- Downloads are routed in `DownloadService.perform()`: debrid service (when selected; `DebridClient` implemented by `TorBoxClient` and `RealDebridClient`) → HTTP with resume; torrent → libtorrent; HTTP otherwise (with the RomM credentials and trusted certificate when the file comes from the RomM server, and the source's mirrors, best first, as fallback). Every job waits for its turn in `DownloadQueueService`. Integrations talk to their APIs with a tiny `JsonHttp` helper over `HttpURLConnection` + Gson; OkHttp only comes in with Coil.
- Around it, small services of their own: `SourceMirrors`, `SourceStatsService` (reliability), `DownloadAutoRetry`, `MultiDiscService` (which listens to finished downloads), and the RomM upload, `DownloadVerifier` and the ES-DE favourites sync, which listen to `MultiDiscService` once the discs are in place.
- Images (covers, details artwork) go through one Coil loader that keeps them on disk regardless of the servers' short cache headers. Cover lookups only read the first bytes of each libretro candidate; an offline or rate-limited lookup is retried later instead of being remembered as "no cover".
- Secrets (TorBox / Real-Debrid keys, RomM token) live only in the app's DataStore; nothing is baked into the APK or the repository.
- Unit tests for the pure logic (468 in 77 classes): updates (`AppUpdates`), search and parsing (`SearchNormalizer`, `SearchIndex`, `RecentSearches`, `ConsoleFolderAliases`, `GameTitleCleaner`, `LibraryKeys`, `ListingSize`), sources and scanning (`SourcesJson`, `SourcesJsonMirrors`, `SourcesJsonFavourites`, `SourceScanCheck`, `ScanRetry`, `ScanEta`, `ScanScheduleAndProgress`), deep links and shortcuts (`DeepLinkParser`, `DeepLinkResolver`, `DgmtxFile`), frontends (`EsdeXml`, `EsdeFindRules`, `EsdeFavouriteSync`, `FrontendCheck`, `IisuJson`, `DaijishoSetup`), debrid and RomM (`DebridMatcher`, `RommPlatformMapper`, `RommSource`, `CertPin`, `StoredZip`, `RommUploadResume`, `RommUploadSources`, `RommJson`, `RommMatching`, `RommPlayState`, `RommFavouritesMerge`, `RommCovers`), covers, similar games and recommendations (`LibretroThumbnails`, `SimilarGames`, `RawgCatalog`, `Recommender`), genres, years and letters (`LibraryGenre`, `LibretroMetadat`, `LetterJump`), chip colours (`ColorMath`, `TagColors`), seeding (`SeedingMode`), downloads (`DownloadFailures`, `MirrorUrls`, `MultiDisc`, `ArchivePaths`, `TokenBucket`, `HttpResume`, `SourceReliability`, `VersionOriginTieBreak`, `DownloadQueue`, `DownloadListFilter`, `DownloadNotices`), verification (`Checksums`, `DatParser`, `DownloadCheck`, `LibretroDats`), game versions (`VersionGrouping`), Collection (`CollectionGames`, `CollectionHealth`, `VolumeUsage`, `CollectionDuplicates`, `CollectionRepair`, `CollectionRules`, `GameTraitRules`, `SmartStorage`, `LibraryMoveJob`, `TrashRules`, `ActionHistoryRules`, `DocumentIds`) and the gamepad (`GamepadLayout`, `TriggerPress`).
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
├── data/        Room entities/DAOs, repositories, services (scraping, torrents, downloads, extraction, RomM, frontends, Collection), app-wide search, shared state
├── di/          Hilt modules
├── ui/
│   ├── common/      Gamepad detection and shortcut bus
│   ├── components/  AppShell, TopBar, TagChip + TagColors, GameCover, ColorPickerDialog, FocusHighlight, Stepper, SubTabs, GamepadLegend
│   ├── screens/     onboarding, home (Library), download, collection, sources, settings (+ RomM), contact
│   └── theme/       DogmatixTheme, palettes, ThemeMode, accent presets
└── util/        Pure logic with its tests: file-name parsing, folder aliases, search, cover / colour / similar-games / recommendation logic, game versions and multi-disc sets, scanning, download queue / resume / verification, RomM, frontends, Collection rules, storage helpers
app/src/main/assets/consoles.json   Default sources (empty in Dogmatix)
app/src/test/                       JVM unit tests
```

## Tech stack
- **UI**: Jetpack Compose, Material 3, Navigation Compose
- **DI**: Hilt (KSP)
- **Persistence**: Room + FTS, DataStore Preferences
- **Images**: Coil (disk-cached covers)
- **Torrents**: libtorrent4j · **HTTP**: HttpURLConnection, Jsoup
- **Archives**: 7-Zip-JBinding-4Android (ZIP/7z)
- **Concurrency**: Kotlin Coroutines + Flow; JobScheduler for the automatic scan

## Deep links

Other apps and frontends can open the library with filters already applied:

```
dogmatix://library?console=nintendo_snes&region=USA,Europe&lang=En&type=GAME&genre=platform&decade=1990&q=mario&fav=1
```

| Parameter | Meaning |
|---|---|
| `console` | Console ids as shown in Sources (comma-separated) |
| `region`, `lang`, `type`, `filetype`, `tag` | Library tags (comma-separated); the kind is inferred from the value |
| `genre` | Genres (comma-separated): ids such as `rpg`, `platform` or `shooter`, or database names such as `Role-playing` |
| `decade` | Decades (comma-separated): `1990`, `90s`, `80s`… |
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
| D-pad | Move focus · ◀ ▶ in the Library list jump to the next / previous letter (sorted by name) |
| A | Select / download (the game's preferred version) · pause a running download |
| B | Back one layer (close sheet, clear search, back to tabs) |
| X | Game details popup, with every version of the game (B or X closes) · in Downloads, delete · in the Collection tools, act on what is ticked |
| Y | Focus search |
| Select | Star / unstar the focused game · in Downloads, tick rows for multi-selection |
| START | On a queued download: move mode (▲ ▼ move, LB next, Y hold) |
| LB / RB | Switch panel (filters, chips, tools or categories ⇄ list) |
| R3 | Fold / unfold the filter panel |
| ZL / ZR | Previous / next section |

Everything is also reachable by touch; the legend only appears while a controller is connected, and button names and colours follow the *Gamepad layout* setting (Xbox / Nintendo / PlayStation).

## Roadmap

Ideas and requests are welcome as [issues](https://github.com/cortinadev/dogmatix/issues).

## Disclaimer

This app is for educational purposes only. Users are responsible for ensuring they have the legal right to download any content.
