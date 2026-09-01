# Changelog

All notable changes to Dogmatix are listed here. Dogmatix is a fork of
[Milou](https://github.com/santiifm/milou) focused on UI/UX for Android handhelds.

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
