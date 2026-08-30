# Changelog

All notable changes to Dogmatix are listed here. Dogmatix is a fork of
[Milou](https://github.com/santiifm/milou) focused on UI/UX for Android handhelds.

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
