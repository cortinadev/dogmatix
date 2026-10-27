# Changelog

All notable changes to Dogmatix are listed here. Dogmatix is a fork of
[Milou](https://github.com/santiifm/milou) focused on UI/UX for Android handhelds.

## [1.4] – 2026-10-27

Downloads, source scans and file handling are sturdier: an interrupted download no longer leaves a half-written file that looks like a game, and Dogmatix tells you why something failed instead of silently stopping. Two new kinds of source arrive with it: Internet Archive items and web pages described with CSS selectors. Checked with the automated suite (679 unit tests, 45 on-device tests) and by hand on an Android 14 handheld in landscape and portrait.

### Added
- **Downloads that survive a restart**: automatic retries that were waiting when the app closed are picked up again when you reopen it. A retry that comes due while the app is in the background shows *Retry when the app is open* and runs as soon as you come back.
- **Reasons for failed extractions**: a damaged or unsupported archive, an unsafe path, no room to unpack, a lost folder permission or a removed SD card now show up in the download row as the actual reason. The archive is kept, and *Retry* unpacks it again without downloading it a second time.
- **Clearer storage problems** in Collection and *Move library*: *not enough space*, *folder permission lost* and *SD card removed* are told apart, and whatever cannot be reached is left alone.
- **Better confirmations** when deleting from Collection (Duplicates, Repair, Free up space): the dialog counts the files, including the tracks of a `.cue` / `.gdi` that go with them and the playlists that will be rewritten, and shows the space it really frees.
- **Source lists from more servers**: nginx, IIS and Apache listings that use plain links (not a table) are now read, and links that start with `/` resolve against the server.
- **Source checks**: switching a source back on rescans it by itself, and a server that is down is reported as *Could not connect* instead of *Connection was cut*.
- **Testing and validation**: `scripts/validate.sh` runs the whole check (changelog, unit tests, lint, builds, release APK), `scripts/release-check.sh` inspects a release APK before publishing, GitHub workflows run both, and a new set of tests runs on a device or emulator. See [TESTING.md](TESTING.md).
- **Predefined manufacturers and consoles** in Sources → Add console: pick a manufacturer (or type a new one) and tap one of its consoles — about 50 across 16 manufacturers — to fill in the name, the chip label and the folder aliases, all still editable. Consoles you already have are not offered again.
- **Web pages with CSS selectors** as a source type. In Sources → Add URL, *Page with CSS selectors* lets you point at a site whose games are not in a folder listing: you give the selector of each element and, optionally, of its link, name and size, a default extension and, for addresses with `{page}`, which pages to read (`1-50`, `A-Z`…). *Test* reads the first page and shows how many elements matched and a sample of the files before you save. The card reads *Type: game · selectors*, the rescan stops at the first numbered page that brings nothing new, and the selectors travel in the sources export as `scrape`. Links must point straight at files; pages built with JavaScript or needing a click-through are not supported.
- **Internet Archive sources**: add `ia://<item>` or paste an `archive.org/details/…` or `…/download/…` address and the item's files are listed from the Archive's metadata API (generated files such as thumbnails and `_meta.xml` are skipped; a sub-folder in the address narrows the source). A rescan only asks whether the item changed. Pasting the address of a ZIP inside an item (or of its `view_archive.php` viewer) lists the files inside it, so the large per-system ZIPs can be browsed and downloaded one game at a time; those downloads cannot be resumed (pausing starts over, and the row says so) and the progress bar uses the size from the listing.
- **Honest pausing for downloads that cannot resume**: when a server cannot continue a download (the Internet Archive ZIP viewer), a paused row says *Will restart from the beginning* and Dogmatix no longer sends a useless `Range` request; servers that can resume are unaffected, also in *Pause all* / *Resume all*.
- **Empty manufacturers can be deleted** from Sources: a manufacturer left without consoles shows a card with a trash button and a confirmation.
- **Internet Archive verification**: downloads from an item are checked against the hashes the Archive publishes (*Verified · Internet Archive*); games taken from inside a ZIP are checked against the console's DAT, as before.
- **Internet Archive account** (Settings → Integrations → *Internet Archive*): sign in on archive.org's own page inside the app to scan and download items that need an account. Dogmatix keeps only the session, never your password, sends it only to archive.org (also when a download is redirected to one of the Archive's data nodes) and identifies itself with its own User-Agent as the Archive asks. *Check* tests the session and *Sign out* forgets it; if the session expires, a refused scan says so. Google's sign-in does not work inside apps: use your email and password or *Email me a code to log in*. In landscape the title and *Cancel* sit in a side panel so the page keeps the full height.

### Changed
- Downloads are written as `<name>.dogmatix-part` and take their final name only when complete. Partial files never show up as games, in the library check marks or in Collection, and deleting a stopped, failed or paused row removes its partial file.
- *Download again* keeps the copy you already have until the new one is complete.
- Tapping a download twice quickly no longer starts it twice; pausing, stopping and deleting while a download is starting now settle on the last request.
- Archives never overwrite existing files: an identical file is reused and a different one with the same name is saved as `name (2).ext`.
- Copies and moves of games between folders and storage volumes are verified (size and sampled blocks) before the original is deleted. If a copy does not match, the original stays where it was and the move says so.
- A `.m3u` playlist always lists the discs that are really in its folder, even when another disc could not be moved in, and Collection → *Health* offers to complete a playlist that misses one. Deleting a `.cue` or `.gdi` also removes the tracks it points to, unless another sheet uses them; a playlist that loses a disc is rewritten.
- Switching a source off, deleting it or changing its address removes its games from the library right away (before, they went at the next rescan). Switching it on rescans it.
- Scan requests that arrive while a scan is running are queued and merged instead of being dropped.
- The update check refuses an APK that is not newer than the installed version.
- The database moves to version 20 (pending automatic retries are stored with the download). Installs from 1.0 to 1.3.1 migrate in place; checked from 1.3 with 60 000 indexed games and the download history, favourites and settings intact.
- **The first *Rescan* after updating re-reads every web source once** (the listing reader changed); torrents and RomM sources are skipped as before. Starred games and download history of renamed web files are carried over.
- The *Add / Edit source* and *Add console* dialogs are wider (up to 720 dp), so the fields no longer feel squeezed; the selector fields sit side by side.

### Fixed
- A download that is paused, stopped or deleted at the same moment it finishes or fails can no longer leave a ghost row, a stuck slot in the queue or a started duplicate.
- A transfer cut short is never taken for a finished file, and a pending retry no longer disappears when Android restarts the app.
- A partial file no longer stays on disk with the final name after deleting a failed download.
- A trash batch that stopped halfway could delete the copy that was just saved; it now keeps exactly what was moved.
- Listings with empty, anchor, `mailto:` or `..` links, thousands separators in sizes (`12,345,678`) or truncated names (`..>`) no longer produce wrong or empty rows.
- A scan cancelled halfway, or one in which a source failed, no longer cleans up the games of the other sources.
- A source whose files were removed is rescanned instead of being treated as unchanged.
- A scan report no longer shows a made-up *(HTTP 192)* for an empty or missing listing, and Downloads only offers *X Delete* on rows that can be deleted (paused, stopped, failed or completed), not on one that is still running.
- Web listings with scheme-relative links (`//host/path`, as Internet Archive's ZIP viewer uses) now download: the link takes the listing's scheme and the file is named after its last segment.
- *Duplicates* and *Free up space* now find a cue/bin, `.gdi` or `.m3u` set that was copied into another console's folder, say which copy is kept, and no longer offer two different Dreamcast games as duplicates just because both have a `track01.bin`.
- *Rescan all* with many torrent sources that did not change now finishes in under a second (29 torrents on the test device, before 53 to 142 seconds): torrents that list no files are no longer asked from the swarm on every pass, and a pass that changes nothing no longer counts as a library update.
- Deleting or moving in a folder the app can no longer open shows the reason, and the Collection analysis stops offering that folder. The delete confirmations in Duplicates and *Free up space* count the files of each copy.

### Known issues
- Not yet checked on a device: moving the library between the SD card and internal storage, cancelling and resuming a move inside one volume, and signing in to Internet Archive inside the app on a real account. Collection after a lost folder permission, a library root under *Documents*, deleting a `.cue` from *Free up space*, moving between two internal roots with undo and the one-off re-read of web sources were checked.
- *Duplicates* and *Free up space* do not recognise a single loose disc copied next to a set kept in a `<game>.m3u/` folder.
- With the source filter on *Direct link*, a game that also has torrent versions still shows its *2+* badge; a download from an Internet Archive item can show the chip of a torrent that has a file with the same name.
- A collapsed console card in Sources closes again after each scan.
- In the Duplicates confirmation, copies of a single file show no file count while multi-file copies do; with focus on *Delete suggested* / *Repair*, the controller legend is still the one for the rows.
- The GitHub Actions workflows are included; `validate` runs for the first time with the push of this release and `release` (manual) has not been run yet.

## [1.3.1] – 2026-10-16

### Added
- **In-app updates**: Dogmatix looks for a new release on GitHub when it starts (at most every
  six hours) and announces it once per version. *Update* downloads the APK, checks that it is a
  Dogmatix build signed like the one installed, and opens Android's installer — the first time,
  Android asks you to allow installs from Dogmatix. *Later* (or B) puts that version off; B
  during the download hides it and the dialog comes back when the APK is ready. Settings →
  General → *Updates* shows where things stand (*Check*, *Update* or *Install*), and *Check for
  updates on start* turns the automatic check off. It works from 1.3.1 on: 1.3 and earlier
  have to be updated by hand once.

### Changed
- Settings opens on **General**, now the first category; Library comes second.
- The update check replaces the old start-up toast, which only named the new version.

### Fixed
- Collection → Summary, and the low-space warning in Health, named storage volumes in the
  device's language instead of the app's ("Almacenamiento interno compartido" with the app in
  English). They are now *Internal storage*, *SD card* or *External storage*, in the app's
  language.

## [1.3] – 2026-10-16

### Added

#### Library
- **Square covers in the library list**: every row starts with the game's box art, cropped
  to a square, from libretro-thumbnails (the covers RetroArch shows — no API key needed, found
  by the No-Intro / Redump file name) or, failing that, an image the details popup already
  cached. Games without a cover get a square in their console's colour with its short name.
  Lookups are cached (found covers for a month, misses for three days) and the images stay on
  disk; a lookup that fails because the device is offline or the server is busy is retried
  later. When the preferred version has no cover, up to three other versions of the game are
  tried. Downloads shows the same cover in front of each row. Settings → "Covers in the list"
  turns them off.
- **Coloured chips**: the console chip is drawn in its console's colour and the tags in the
  colour of their group — region, language, revision, demo / beta / hack — in the library,
  the details popup and Downloads. Each console's colour is picked in Sources (console dialog
  → "Chip colour"; a dot on every console card shows it) and travels in the sources export as
  `color`; without one, the console uses a default for its family (Nintendo red, PlayStation
  blue…). The group colours are picked in Settings → "Tag colours", with "Reset".
- **Colour picker made for the gamepad**: preset swatches (A takes one), hue / saturation /
  brightness rows that ◀ ▶ adjust, the hex code shown next to a preview chip, and "Default"
  to go back to the built-in colour. B closes it without changes.
- **One row per game, preferred version**: the library groups the versions of a game (regions,
  revisions, demos, the same release from two sources) into one row with a `4+` badge, and
  the discs of a multi-disc release travel together (`2 discs`, total size). A downloads the
  version that suits you — every disc of it — from your preferred region and favourite
  languages: releases before demos, betas and bad dumps, then your region and its closest
  fallbacks, a version in one of your languages, the latest revision and verified dumps. The
  details popup lists every version, the preferred one first, in a three-row box that
  scrolls — LB / RB jump between it and the buttons, which always stay on screen — and A on
  any of them downloads it. Filters still apply, and paging counts games, so a game's
  versions always arrive together.
- **Preferred region**: a new first-run step (after the ROMs folder) and a Settings stepper
  pick it together with the favourite languages; the default follows the device (Spain for a
  Spanish device, USA for en-US or Latin America, Europe for the rest of Europe…).
- **Similar games**: a "Similar" button in the details popup filters the library by the games
  similar to that one, on every console: its franchise first (found from the title, weighing
  each word by how rare it is in your library), then related games, and — when the franchise
  is short — games of the same genre (from the details you looked up before and, with a RAWG
  key, the most popular games of that genre on your consoles). Other filters (region,
  language, tag, favourites, source) still apply; A on the "Similar to …" chip under the
  search box, or B, goes back to the list.
- **Recommendations**: with no search and no filters, the Library shows shelves of games
  picked from what you star, download and keep on disk instead of the bare list — "For you"
  (the genres you play on the consoles you use, plus related games), "Complete the series"
  (more of the franchises you have), "More for <console>" and, with a RAWG key, "Popular on
  your platforms"; with nothing to go on yet, "Highlights on <console>". Games you already
  have, betas and BIOS files are left out, and games with a cover go first. A downloads the
  preferred version, X opens the details and SELECT stars; ▲ ▼ change shelf and ◀ ▶ move
  along it. "All games", a search, a filter or another sort order show the list.
- **Genre and Decade filters**: two new rows in the filter panel and the filter sheet
  (multi-select, ◀ ▶ quick change). Genres and years come from libretro-database's DATs
  (cached for a month) and from the details you open (RAWG, TheGamesDB, RomM); disc-based
  systems with no such DAT (PlayStation, PS2, Saturn, Dreamcast, GameCube, Wii, Xbox…) only
  count the games whose details were looked up. Deep links take `genre=` and `decade=`
  (`genre=rpg,platform&decade=1990,80s`).
- **Sort by console and by decade**, besides name and size: *Console* (offered while more than
  one console is in play) and *Decade* in both directions.
- **Jump by letter**: with the list sorted by name, ◀ ▶ jump to the next or previous letter
  (◀ on the first letter still goes to the filter panel; holding repeats), loading pages as
  needed, and the letter flashes in the corner.
- **Search the whole app**: the Library search also finds downloads, sources (console names,
  aliases, URLs and mirrors), settings, and Collection tools and collections, grouped under
  the games. "Also in: …" jumps to a group, A opens the result in its section, and B from
  there comes back to the search.
- **Recent searches**: the last ten searches that found something show under the empty search
  box — ▼ reaches them, A runs one, X removes it, and "Clear history" empties the list.

#### Downloads
- **The download queue survives a restart**: whatever was downloading when the app or the
  device went down starts again on the next launch, in its queue order, continuing web and
  debrid downloads from their partial file.
- **A queue you can arrange**: START on a queued row enters move mode — ▲ ▼ move it, LB puts it
  next, Y holds it, A or B leave (with touch, the row's buttons do the same). A held download
  keeps its place but does not start until it is released. A search box and chips with counts
  (All · In progress · Queued · Held · Failed · Completed) narrow the list, and global buttons
  — Pause all, Resume all, Retry failed, Clear completed (the files stay) — appear when they
  have something to act on. Retry failed only retries the failed rows the list is showing, so
  a search or a chip narrows it and an old failure is not downloaded again by surprise.
- **Notification with buttons**: the download notification shows how many downloads are
  running and queued, their progress and speed, and offers Pause all / Resume / Cancel all;
  tapping it opens Downloads. "Notice when downloads finish" (Settings, on by default) posts
  how many completed and how many failed once the queue empties. On Android 13+ the
  notification permission is asked once, with the first download.
- **Pause for web and debrid downloads**, not only torrents: the partial file stays and play
  continues it.
- **Safer resuming**: a download continues from its partial file only when the server confirms
  it is the same file (`If-Range` with its ETag or date) and otherwise starts over; a transfer
  cut short is no longer taken as complete, and a web page served instead of the file fails
  at once ("The server sent a web page, not the file") instead of being saved. "Download
  again" always starts from zero.
- **Best address first**: Dogmatix keeps track of how each server, debrid service and torrent
  behaves (success rate and speed, older results counting less) and tries a source's main
  address and its mirrors best first. A failure that will not go away by itself moves on to
  the next address instead of repeating the same one. Sources shows the figures under each
  URL ("97% successful · 4.1 MB/s").
- **Download verification**: finished downloads are hashed in the background (CRC32 and SHA-1,
  inside kept ZIPs too) and checked against RomM's hashes for games downloaded from RomM, or
  against the console's No-Intro / Redump DAT. The row says *Verified · No-Intro*, *Does not
  match* (with the detail) or *Not verified* and why. DATs are fetched from libretro-database
  when a console needs one, or imported by hand (.dat, .xml or .zip) in Settings → "DAT
  files"; "Verify downloads" turns the check off. Nothing is ever deleted because of it.
- **Failure reasons**: failed rows say why (no connection, timed out, server error, not on the
  server any more, not enough space, folder not accessible, no torrent metadata, debrid
  account problem…), and the reason is kept across restarts.
- **Automatic retries**: a Settings stepper (Off / 1 / 3 / 5, default 3) retries downloads that
  failed for a reason that may go away on its own after 1, 5 and 15 minutes; the row shows
  when. A manual retry, stop or delete cancels it.
- **Downloads per server**: a Settings stepper (Unlimited / 1–4) caps how many web downloads
  talk to the same server at once.
- **Multi-disc games**: when every disc of a release is in, they move into a `<Game>.m3u/`
  folder next to a `<Game>.m3u` playlist — ES-DE shows it as one game and the emulator swaps
  discs from the playlist; a disc downloaded later joins it. Only for systems whose emulators
  read playlists (not PS2 or Xbox). Settings → "Multi-disc games (.m3u)", on by default.
- **Seed after download**: an optional Settings stepper (Disabled / 30 min / 1 hour / Until
  ratio 1.0 / Unlimited) keeps finished torrents uploading to other peers. Switching it off
  stops every seed in progress; deleting a finished row stops its own.

#### Sources and scanning
- **Incremental rescans**: "Rescan all" only reads what changed — web listings ask the server
  first (ETag / Last-Modified) and torrents already indexed with the same settings are not
  fetched again — and each source replaces only its own games, so a source that fails keeps
  what it had. "Full rescan" reads everything again. Sources are scanned in parallel (a few at a time, two per server) and the
  scan keeps going when you leave Sources.
- **Sturdier scans**: busy servers (429, 5xx, timeouts) are retried with growing waits, honouring
  `Retry-After`; a source's mirrors are tried when its main address fails; an empty listing
  never wipes a source that had games. A "Last scan" card in Sources lists every source with
  its result and a readable reason, and "Retry failed" scans only those again. The scan
  indicator next to the tabs shows the percentage and the time left.
- **Automatic scan**: Settings → "Automatic scan" (Off / Daily / Weekly), on Wi-Fi only (on by
  default) and while charging only (off by default). It is always incremental and notifies
  the new games it finds.
- **Torrent file lists are cached** on the device (up to 300 MiB), so rescans and direct
  downloads do not ask the swarm for a torrent's metadata again.
- **Reserve addresses for web sources**: the URL dialog in Sources takes mirrors of the same
  folder, one per line; downloads and rescans go round them when the main address fails. They
  travel in the sources export as `mirrors`.
- **Favourites in the sources export**: each console carries its starred files as
  `favourites`, and importing adds them to the ones you already have.
- **Undo an import**: the last sources import can be undone from Collection → History, which
  puts the previous sources back and rescans.

#### RomM
- **Self-signed HTTPS**: when "Test connection" fails because of the certificate, Dogmatix shows
  it (issued to, issued by, validity, SHA-256, whether the name matches) and "Trust this
  certificate" accepts it for that server and port only — for the catalogue, covers,
  downloads, uploads and hash checks alike. A certificate that changes is refused and shown
  again; Settings → RomM → "Trusted certificate" → "Forget" drops it.
- **Resumable uploads**: an upload continues from its last confirmed chunk after a failure or
  when the app opens again (while RomM keeps the session, about a day), retries on its own
  with the downloads' "Automatic retries" setting, and shows real progress.
- **Multi-file games upload as one ZIP**: cue/bin sets, gdi, extracted folders and multi-disc
  `.m3u` folders go up as a single `<game>.zip`, built on the fly without compression or a
  copy on disk. A disc of an incomplete multi-disc game waits for the others.
- **Server catalogue**: Dogmatix reads what the server has for the mapped consoles (when the
  settings change, when you come back to the app — at most every 30 minutes — and on demand)
  and marks those games in the library with a server icon next to ✓ and ★. Settings → RomM
  adds "Upload what's missing" (games on disk the server lacks, by console), "Sync favourites
  with RomM" (★ and RomM's Favourites both ways; off by default) and "RomM covers to ES-DE".
- **Covers from RomM** come first in the list and the details popup.
- **RomM in the details popup**: rating and genres, your play status (◀ ▶ to change it) and the
  RomM collections the game is in (A to add or remove it). RomM collections also show up in
  Collection → Collections.

#### Frontends
- **ES-DE favourites**: ★ in Dogmatix and favourites in ES-DE follow each other — a ★ changed
  here reaches ES-DE's gamelist a moment later, and a full two-way sync runs at start-up, after
  "Configure ES-DE" and from Settings → "Sync favourites with ES-DE". See FRONTENDS.md.
- **Check frontends**: a Settings row that reads back the ES-DE, iiSU and shortcut setup, marks
  each part ✓ or ⚠ and repairs what is missing with one button.
- A release and a debug build can both be set up in ES-DE: the second adds its own entry after
  the first instead of replacing it.

#### Collection (new section)
- A new section between Downloads and Sources to look after the games already on disk. It
  analyses the download folders on demand (X, or "Analyse"), keeps the result and refreshes a
  console after each download. Its tools:
  - **Summary**: games on disk, how much of your catalogue you have, favourites on disk,
    storage volumes, per-console figures, latest downloads, the biggest games, regions and
    languages; A opens the Library filtered.
  - **Health**: twelve checks — lost folder permission, low space, empty files, broken
    `.cue` / `.m3u` / `.gdi` references, incomplete sets, games in another console's folder,
    leftover partial downloads, multi-disc sets without a playlist, archives already
    extracted, empty folders, foreign extensions, sources never scanned — each with its fix.
  - **Duplicates**: the same game twice (the same files, different versions, or the same size
    and content), with a suggestion of which copy to keep.
  - **Repair**: create missing `.m3u` playlists, move games to their console's folder, delete
    empty folders, leftovers and archives already extracted, with a preview per kind.
  - **Free up space**: the biggest items first, plus the torrent and cover caches; it deletes
    for good, and says so.
  - **SD storage**: a second library folder (on the SD card, typically). Consoles can live
    there, suggestions say which ones to move when internal space runs low, and a download
    that does not fit goes to the same console folder on the other one.
  - **Move library**: moves the whole library, or some consoles, to another folder. Downloads
    pause while it copies and resume afterwards; it can be interrupted, resumed and undone.
  - **History**: what was deleted, repaired, moved or imported, with undo. Deleting from
    Downloads, the Library, Duplicates or Repair moves the files to a hidden trash
    (`.dogmatix-trash`, skipped by ES-DE) that empties itself after 7 or 30 days.
  - **Collections**: your own lists, by hand (the new "Collection" button in the details
    popup) or by rules (console, region, language, tag, genre, decade, favourites, on disk,
    source, size, text). A opens the Library showing just that collection.

#### Interface
- **German, French, Portuguese and Italian**: the whole interface is translated into four more
  languages. Settings → Language offers them next to English and Spanish, and Android 13+ lists
  them in the system's per-app language settings. Portuguese is written in European Portuguese
  and covers every Portuguese locale.

### Changed
- **Settings by category**: Library, General, Appearance, Downloads and Integrations, each with
  headed blocks, replace the two columns — a panel on the left in landscape (LB / RB move
  between the panel and the rows) and tabs on top in portrait. "About & contact" sits at the
  foot of the panel.
- **Sort moved next to the search box**: the Sort row left the filter panel and became a button
  to the right of the search box; A opens the list, ▲ ▼ pick, A applies. ◀ on it opens the
  search and ▶ from the search comes back. It is highlighted when the order is not A → Z.
- **Active filters under the search box**: whatever is filtered shows as a thin row of chips
  under the search box ("Region: Europe, Japan +1", "Similar to …", the open collection),
  with "Clear" when there are two or more; A on a chip removes it. It replaces the filter
  panel's "Filters · Clear" header and the Similar banner. The filter panel's selectors now
  line up in one column.
- The search boxes of the Library and Downloads show a small ✕ (touch) while they have text.
- "Rescan all" is incremental (see above); the new "Full rescan" button does what it used to.
- Deleting a game from Downloads or the Library moves it to the trash, undoable from
  Collection → History; partial downloads are still deleted for good.
- The details popup always titles a game with its library name, never with the name of the
  game-database hit, and shows the library cover when the databases have no image (or no API
  key is configured).
- The speed limit applies to torrents too, and a change reaches the downloads already running.
- Rescans no longer wait a second before every request and sub-folder, and their files are
  written in one transaction per batch.
- Database version 19 (per-console colour and the cover cache, an index on file names, the
  game grouping key and failure reasons, source scans, download resume / reliability /
  verification data, queue order, RomM uploads, genres and years, the RomM catalogue, and
  history, trash, collections and storage redirects); the update migrates it and groups what
  is already indexed.

### Fixed
- The "This game is already in your library" dialog opened with the focus on "Delete", so
  pressing A twice deleted the game from disk. It now opens on "Download again", and B
  closes it.
- On pads that report a trigger both as a button and as an axis (Retroid Pocket 6), one press
  of ZL / ZR switched several sections.
- Exporting and then importing the sources gave older consoles a new id, leaving their
  favourites, download history and folder behind; the export now writes each console's `id`.
- Game details for some consoles (SNES among them) were looked up under another platform and
  came back empty.
- The details popup could show another game of the series ("Naruto – Clash of Ninja 2" as
  "Naruto: Clash of Ninja"): sequel numbers have to match now, the best candidate wins over
  the first one, and wrong hits already cached are looked up again.
- The download speed limit did not limit: Settings saved KB/s but downloads read MB/s, a
  change never reached a running download, and every download got the whole limit to itself.
  It is now one budget shared by all web downloads (debrid and RomM included), and the torrent
  session gets the same limit.
- Stopping (or pausing) a web download did not cut the transfer: it kept downloading to the
  end and was then marked as stopped.
- Resuming a download whose partial file was already complete failed forever with HTTP 416;
  it now starts over.
- Freezes with long download queues: every finished download made the library index resolve
  all the completed ones again, by a file name without an index.
- Extracting an archive flattened its folders, so `Disc 1/track01.bin` and
  `Disc 2/track01.bin` overwrote each other. Folders are kept now (unsafe paths are refused
  and clashing names renamed), and deleting the download removes them again.
- File sizes from Apache and h5ai directory listings, and the sorting links of Apache
  listings indexed as files.
- Two files with the same name in different folders of a torrent could share their tags.
- The download notification, the update notice and the scan errors were always in English.
- The details popup could push its buttons off a landscape screen when it had a lot to show.

## [1.2] – 2026-09-02

### Added
- **Gamepad layout**: a Settings stepper (Xbox / Nintendo / PlayStation) draws the button
  legend the way the pad in your hands is printed. Xbox keeps A/B/X/Y with the by-role colours
  (green confirms, red goes back) and names the shoulders `LB · RB` / `LT · RT`; Nintendo keeps
  the same letters in the Super Famicom colours (A red, B yellow, X blue, Y green) and calls
  the shoulders `L · R` / `ZL · ZR`; PlayStation draws ✕ ○ □ △, each in the colour of its
  shape, with `L1 · R1` / `L2 · R2`. Only the drawing changes: A (✕) always accepts and B (○)
  always goes back, whichever layout is picked.
- **Swap A/B and X/Y**: a Settings switch for pads that report their face buttons the other way
  round — it moves the actions and leaves the legend exactly as it is, so what the legend says
  matches the button you press. It applies everywhere, dialogs and the filter sheet included.
- **Maximum search results**: a new Settings stepper (50 / 100 / 250 / 500 / Unlimited,
  default 100) sets how many games a library search loads at once; "Load more" still fetches
  the next batch, and "Unlimited" drops the limit and lists everything the filters match.
- **Multi-selection in Downloads**: tick several downloads and act on all of them at once.
  SELECT (long press with touch) ticks the row under the cursor and turns the summary line
  into an action bar — retry, pause, stop, delete — showing only the actions the ticked rows
  accept. A ticks rows while selecting, Y ticks / unticks everything, X deletes the selection
  (one confirmation for the lot when some of them finished) and B drops it. Retry, pause and
  stop keep the selection so actions can be chained.
- **Frontend integration (.dgmtx shortcuts)**: Dogmatix opens `.dgmtx` files — tiny text
  files carrying a `dogmatix://library?…` deep link — so frontends like ES-DE can list it
  as an "emulator" per platform. A new Settings row ("Frontend shortcuts") drops a
  `★ Search for more games....dgmtx` shortcut into every console's download folder, ready
  for the frontend to scan (the star keeps it at one end of the game list); `banner.png`
  in the repo serves as its preview image (see FRONTENDS.md).
- **One-button ES-DE setup**: Settings → "Configure ES-DE" picks the ES-DE data folder once
  and writes everything itself — shortcuts, find rule, per-platform system overrides (built
  from the es_systems.xml bundled inside the installed ES-DE, so nothing is lost or
  outdated), gamelist entries with `altemulator` (the system's default emulator is
  untouched), and the banner as cover art. Existing files are merged, never truncated.
  When ES-DE is detected, the first-run tour offers this same setup as its final step.
- **One-button iiSU setup**: Settings → "Configure iiSU" picks iiSU's `iiSULauncher` data
  folder once (inside `Android/media/com.iisulauncher/`, which SAF can reach) and adds
  Dogmatix as one more emulator in its `emuladores.json`: `.dgmtx` joins the console's
  accepted extensions and a `DOGMATIX` entry is appended last, so the console keeps its own
  default emulator and only the shortcut is pointed at Dogmatix with iiSU's per-ROM
  *Override Emulator*. Only consoles whose folder got a shortcut are touched and nothing is
  ever removed; on a fresh iiSU install the defaults bundled inside its APK are used as the
  base. Note that applying an `emuladores.json` update from iiSU's own updater drops these
  additions — running the setup again puts them back.
- **Assisted Daijishō setup**: Settings → "Set up Daijishō" deploys the shortcuts and shows
  the three values its *Add an emulator* form needs, each with a Copy button. Daijishō keeps
  its players in a private database and exposes no intent, deep link or importable emulator
  configuration, so that last step is typed in by hand; a custom emulator there is global, so
  one entry covers every console and it survives Daijishō's automatic platform updates.
- **Pause / resume for torrent downloads**: a pause button on active rows (A on the gamepad)
  parks the download keeping its data; play resumes from the pieces already on disk — even
  if the app was left and the torrent session restarted in between.
- Direct HTTP and RomM downloads keep their partial file when they stop or fail (connection
  drop included) and the retry continues it with a Range request instead of starting over.
- **Sort the library by size**: the "Sort" filter row gains "Size: big → small" and
  "Size: small → big" next to A → Z and Z → A, handy for spotting the heavyweights (or the
  quick downloads) of a console. Ties are broken by name so paging through a long list never
  repeats or skips a game.

- A tiny, faint version indicator under the app logo in both headers.
- **True black theme**: a fourth theme mode ("True black") with a pure `#000000` background
  for AMOLED screens, next to System / Light / Dark in the Settings stepper. Panels and
  cards sit barely above black so the layout still reads; text and accents reuse the dark
  palette.

### Fixed
- Per-file download speed: rows from the same torrent showed the torrent's total rate.
- A deep link (or .dgmtx shortcut) for NES also selected SNES: for bare console ids like
  `super_nintendo_entertainment_system` the derived folder name dropped the first word,
  so SNES's alias set contained NES's full name. Also fixes the default download folder
  for such consoles (`playstation_2` would have created a folder literally named "2").

## [1.1.4] – 2026-08-31

### Added
- Each source URL now has an on/off switch in Sources: disabled sources are kept (and
  exported/imported) but skipped when rescanning, so their games drop out of the library on
  the next rescan and come back when re-enabled.

### Fixed
- **Downloads marked "Completed" with nothing (or a 0-byte file) in the ROMs folder.** When
  writing to the download cache failed (e.g. the internal storage filled up mid-download),
  the truncated file was still copied into place and the row marked completed; a later
  progress tick could also overwrite a failed status with "completed". Incomplete data now
  fails the download honestly, failed/stopped rows stay failed until retried, and the retry
  starts clean.
- **Retry button did nothing on "Completed" rows.** Downloads that the 1.1.2 bug had falsely
  marked completed showed a retry button that was silently refused; retrying a finished
  download (i.e. downloading it again) is now allowed.
- **Downloads are now fully gamepad-accessible**: the per-row buttons could not be reached
  with the D-pad. A on a focused row retries (or stops an active download), X deletes it —
  with a confirmation dialog on completed rows that starts focused on "Keep file" and closes
  with B. The button legend shows the new shortcuts.

## [1.1.3] – 2026-08-31

### Fixed
- **Queued downloads from the same torrent failing** (1.1.2 regression): releasing a finished
  torrent deleted its cached files asynchronously, which could wipe the files of the same
  torrent when the next queued download re-added it (flashing Failed/Completed rows, endless
  partfile errors, nothing copied to the ROMs folder). Partial files — the partfile included —
  are now deleted synchronously and individually. Verified on device with six queued downloads
  from one torrent.
- The *Tag* filter lists the fixed set of content-type tags again (Game, Demo, Beta, Proto…),
  as *Type* did before 1.1.1 — only the filter's name changed.

## [1.1.2] – 2026-08-30

### Fixed
- **App cache growing to gigabytes.** Torrent downloads are staged in the app cache and were
  only removed after a successful copy: stopping or failing a download left the partial file
  behind for the rest of the session, and every rescan wrote a few random pieces while the
  metadata was being fetched. Torrents are now removed together with their files when stopped,
  failed or timed out, and metadata is fetched in upload mode (no pieces requested).

## [1.1.1] – 2026-08-30

### Added
- **Source filter** in the Library (All / Torrent / RomM / Direct link): shows where each
  entry comes from, so ROMs served by your RomM server can be listed on their own.
- **Tag filter** replaces the old *Type* filter. It covers every tag that is not a region,
  language, video standard or file extension — Game, Demo, Beta, Proto, Rev, Unl… — instead of
  the fixed content-type list, so tags like *Beta* or *Proto* are now filterable.

### Fixed
- **Metadata fetch failing on large torrents.** libtorrent rejects torrent info dicts above
  3 MiB by default; multi-TB collection torrents (e.g. MiNERVA's *Redump – Microsoft – Xbox*,
  ~30 MB of metadata) were rejected on every peer and always timed out. The limit is now
  256 MiB, and the same magnet fetches in about 20 s.
- **Metadata timeout is now an inactivity timeout.** The clock restarts whenever bytes or new
  peers arrive, so slow but healthy swarms are no longer cut off; it only gives up after the
  configured time with no progress (hard cap: 30 windows). Default raised from 20 s to 30 s.

### Changed
- Settings → *Metadata timeout* hint explains the new inactivity behaviour (EN/ES).
- Onboarding copy mentions the tag and source filters.

## [1.1] – 2026-08-29

### Added
- Favourites (★) with a *Favourites only* filter; Select on the gamepad toggles a game.
- Deep links: `dogmatix://library?console=…&region=…&lang=…&q=…&fav=1` (short console
  names and aliases accepted; tags case-insensitive).
- Debrid downloads through TorBox or Real-Debrid (API key in Settings, resumable, falls back
  to a direct torrent when the service only has a zipped set).
- RomM integration: automatic upload of finished downloads, and `romm://<platform>` as a
  library source.
- Spanish UI with a language setting (System / EN / ES).
- *Metadata timeout* stepper in Settings.

## [1.0.1] – 2026-08

### Fixed
- Content kept clear of display cutouts (notches) in every orientation.
- Owned-games index scoped by console folder (same name in `snes/` no longer marks `gbc/`).

## [1.0] – 2026-08

First Dogmatix release, forked from Milou:
- Package renamed to `com.cortinadev.dogmatix`.
- New flat theme (Manrope, light/dark, accent colour), full-screen shell with top tabs in
  landscape and bottom bar in portrait, gamepad legend and D-pad focus everywhere.
- Fixed filter panel (collapsible in landscape), game details card (RAWG / TheGamesDB),
  Settings redesign, onboarding, sources import/export as JSON.
