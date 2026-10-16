# Frontend integration (.dgmtx shortcuts)

Dogmatix can appear inside a game frontend as if it were an emulator: each platform's ROM
folder gets a tiny `★ Search for more games....dgmtx` shortcut file, the frontend lists it
like a game (the star keeps it at one end of the alphabetical list, away from the games),
and "launching" it opens Dogmatix with the library already filtered to that platform —
browse, download, and the new ROM shows up in the frontend's folder.

## The `.dgmtx` format

A plain text file. Lines starting with `#` are comments; the first `dogmatix://` line is the
deep link that gets applied (any filter combination from the [deep links](README.md#deep-links)
section works):

```
# Dogmatix shortcut — Super Nintendo
dogmatix://library?console=nintendo_snes
```

Dogmatix opens these files from any `ACTION_VIEW` intent with a `content://` (or readable
`file://`) URI — from a frontend, a file manager, or `adb shell am start`.

## Creating the shortcuts

Settings → **Frontend shortcuts** → *Create* writes a `★ Search for more games....dgmtx` into
every console's download folder (the same folder a download would use: the per-console custom
folder, the detected/created subfolder, or — with "Separate subfolders by console" off — the
download root, where the file is named `★ Search for more SNES games....dgmtx` etc. so consoles
don't collide). Run it again after adding consoles; existing shortcuts are overwritten in place.

## ES-DE (Android)

### Automatic setup (recommended)

When ES-DE is installed, the first-run tour offers this setup as its final step ("Connect to
ES-DE"). Later — or after adding consoles — the same button lives in Settings.

Settings → **Configure ES-DE**: pick your ES-DE application data directory once (the folder
holding `custom_systems`, `gamelists`, `downloaded_media` — default `/storage/emulated/0/ES-DE`)
and Dogmatix does everything below by itself: deploys the shortcuts, registers itself as an
emulator, patches each platform's system definition (starting from the `es_systems.xml` bundled
inside the installed ES-DE, so no emulator entry is lost or outdated), writes the gamelist
entries with `<altemulator>` — the system default emulator is untouched; only the shortcut
launches Dogmatix — and sets the banner as the entry's cover art. Existing files are merged,
never truncated. Close ES-DE before running it (it rewrites gamelists on exit) and restart it
afterwards. Run it again after adding consoles.

Only console folders whose name matches an ES-DE system (`snes`, `psp`…) get an entry — which
is automatic when the Dogmatix download directory is the ES-DE ROM directory.

### Manual setup

What the button writes, for reference or fine-tuning — two files in the ES-DE application
data directory, then restart ES-DE:

`ES-DE/custom_systems/es_find_rules.xml` — registers Dogmatix as an "emulator":

```xml
<?xml version="1.0"?>
<ruleList>
    <emulator name="DOGMATIX">
        <rule type="androidpackage">
            <entry>com.cortinadev.dogmatix/com.cortinadev.dogmatix.MainActivity</entry>
        </rule>
    </emulator>
</ruleList>
```

`ES-DE/custom_systems/es_systems.xml` — a custom system entry *replaces* the bundled one with
the same `<name>`, so copy the bundled commands you still want and add `.dgmtx` to the
extensions. Example for SNES (RetroArch kept as second command):

```xml
<?xml version="1.0"?>
<systemList>
    <system>
        <name>snes</name>
        <fullname>Nintendo SNES (Super Nintendo)</fullname>
        <path>%ROMPATH%/snes</path>
        <extension>.dgmtx .DGMTX .sfc .SFC .smc .SMC .7z .7Z .zip .ZIP</extension>
        <command label="Dogmatix">%EMULATOR_DOGMATIX% %ACTION%=android.intent.action.VIEW %DATA%=%ROMPROVIDER%</command>
        <command label="Snes9x - Current">%EMULATOR_RETROARCH% %EXTRA_CONFIGFILE%=%EXTERNALDATA%/Android/data/%ANDROIDPACKAGE%/files/retroarch.cfg %EXTRA_LIBRETRO%=%INTERNALDATA%/%ANDROIDPACKAGE%/cores/snes9x_libretro_android.so %EXTRA_ROM%=%ROM%</command>
        <platform>snes</platform>
        <theme>snes</theme>
    </system>
</systemList>
```

`%ROMPROVIDER%` hands Dogmatix a `content://` URI with a temporary read grant, so ES-DE needs
no special storage setup for the shortcut. Since a `.dgmtx` is a single file, `%ROMSAF%` also
works.

The first `<command>` is the system's default emulator: keep *Dogmatix* first while the folder
only holds the shortcut, and once real games live next to it either move Dogmatix below the
real emulator or set the alternative emulator per game (game options → *Alternative emulator*)
so the ROMs keep launching in their emulator and only the shortcut opens Dogmatix. The per-game
choice lives in `ES-DE/gamelists/<system>/gamelist.xml`, so it can also be written directly —
together with a name and description for the entry:

```xml
<game>
    <path>./★ Search for more games....dgmtx</path>
    <name>★ Search for more games...</name>
    <desc>Browse and download games for this platform with Dogmatix.</desc>
    <altemulator>Dogmatix</altemulator>
</game>
```

### Preview image

`banner.png` in this repo is a ready-made preview for the shortcut entry. ES-DE finds media
by file name, so copy it (once per system) to:

```
ES-DE/downloaded_media/<system>/covers/★ Search for more games....png
```

(`screenshots/` and `miximages/` work too, depending on the theme's layout.)

### Favourites

Once ES-DE is set up, Dogmatix's ★ and ES-DE's favourites follow each other, matched through
the files on disk (a starred `Game (USA).zip` unpacked into `Game (USA).gba` is the same game,
whichever version is starred; a multi-disc `Game.m3u` folder stands for its discs):

- a ★ changed in Dogmatix is written into `gamelists/<system>/gamelist.xml` a moment later
  (`<favorite>true</favorite>` added or removed; an entry is created for a file that has none),
  and so is a starred game as soon as its download finishes;
- the full two-way sync runs a few seconds after Dogmatix starts and at the end of
  *Configure ES-DE*, and Settings → **Sync favourites with ES-DE** runs it on demand: ES-DE's
  favourites become ★, and a favourite removed on either side since the last sync is removed
  on the other.

Gamelists are edited entry by entry, never rewritten. ES-DE reads them when it starts, so it
shows the changes after a restart; when it saves, it merges entry by entry too, so a running
ES-DE does not undo them. A side that suddenly has none of the favourites it had (a deleted
gamelist, sources imported under other console ids) is taken as reset and filled again from
the other side rather than wiping it. Only systems Dogmatix configured take part.

### Covers from RomM

With a RomM server set up (Settings → RomM), **RomM covers to ES-DE** writes the server's cover
of every game you have on disk into ES-DE's media folder, named the way ES-DE looks for it:

```
ES-DE/downloaded_media/<system>/covers/<rom file without extension>.png|jpg
```

Covers ES-DE already has are left alone unless *Replace the covers ES-DE already has* is
ticked; images that are neither PNG nor JPEG are converted to PNG. Close ES-DE first: it reads
the covers when it starts.

## iiSU

### Automatic setup (recommended)

Settings → **Configure iiSU**: pick iiSU's data folder once — `iiSULauncher`, inside
`Android/media/com.iisulauncher/` on internal storage (the folder picker hides `Android/data`
and `Android/obb`, but `Android/media` is reachable; picking the parent `com.iisulauncher`
also works, Dogmatix descends into `iiSULauncher` by itself and refuses anything else) — and
Dogmatix deploys the shortcuts and registers itself in iiSU's `emuladores.json`. Restart iiSU afterwards, then add or rescan the
console: the shortcut shows up as a game.

For each console whose folder got a shortcut, Dogmatix adds `.dgmtx`/`.DGMTX` to its
`romExtensions` and appends a `DOGMATIX` emulator. Everything else is left alone — other
consoles, other emulators, and the console's own first choice of emulator. Point the shortcut
(and only the shortcut) at Dogmatix with the per-ROM override: focus it, press the *Details*
button, then *Settings → Launch options → Override Emulator*. That is iiSU's equivalent of
ES-DE's `<altemulator>`. *Add to home* on the same menu pins the shortcut to the iiSU home.

Two things to know. iiSU ships `emuladores.json` as a versioned artifact it can update from
its own repository, so *Apply emuladores.json update* wipes these additions — run the setup
again. And on an iiSU that has not extracted its defaults yet, Dogmatix starts from the
`emuladores_default.json` bundled inside the installed iiSU APK, so no console is lost.

### Manual setup

What the button writes, for reference. In
`Android/media/com.iisulauncher/iiSULauncher/Emuladores/emuladores.json`, find the console by
its `shortName` (which is also its folder name) and add the extension and the emulator:

```json
{
  "shortName": "snes",
  "romExtensions": [".sfc", ".SFC", ".smc", ".SMC", ".dgmtx", ".DGMTX"],
  "emulators": [
    { "id": "DOGMATIX",
      "name": "Dogmatix",
      "routeType": "uri",
      "commands": [
        { "description": "Search for more games",
          "command": "com.cortinadev.dogmatix/com.cortinadev.dogmatix.MainActivity -a android.intent.action.VIEW -d %ROM_URI%" }
      ],
      "packages": ["com.cortinadev.dogmatix"] }
  ]
}
```

`routeType` must be `uri`: iiSU then passes `%ROM_URI%`, a `content://` URI sent with
`FLAG_GRANT_READ_URI_PERMISSION`, which is what Dogmatix reads the shortcut through — a raw
path would need storage permissions Dogmatix does not ask for. Spell the component out in
full and do **not** use `%PACKAGE%`: when the command uses it, iiSU rebuilds the class name
from the chosen package, which fails on any build whose application id is not the class's
package (`START_CLASS_NOT_FOUND`). `packages` is what fills iiSU's *emulator variant* list.

## Daijishō

Daijishō keeps its platforms and players in a private database and declares no intent filter,
deep link or configuration file, so there is no one-button setup: the emulator entry is typed
in by hand. Settings → **Set up Daijishō** in Dogmatix deploys the shortcuts and shows the
three values with a Copy button each.

In Daijishō, go to Settings → Library → **Add an emulator** and fill it in:

| Field | Value |
|---|---|
| Emulator name | `Dogmatix` |
| Emulator am start arguments | `-a android.intent.action.VIEW`<br>`-n com.cortinadev.dogmatix/com.cortinadev.dogmatix.MainActivity`<br>`-d {file.uri}`<br>`--grant-read-uri-permission` |
| Accepted filename regex | `^(.*)\.(?:dgmtx)$` |
| Kill package processes before am start | off |

Then sync. A custom emulator is global rather than per platform, so this single entry covers
every console; the platform-level filename regex accepts everything that does not start with
a dot, so the shortcut is picked up without touching the platform. Doing it this way also
survives *Update platforms automatically*, which re-fetches platform definitions from
Daijishō's index and would revert an imported system.

`{file.uri}` rather than `{file.path}`: Dogmatix reads the shortcut through the content
resolver. The `--grant-read-uri-permission` flag asks Daijishō to extend its read grant on
that URI to Dogmatix. Verified end to end on a real device: with this exact entry, a synced
`.dgmtx` launches with `FLAG_GRANT_READ_URI_PERMISSION` set and Dogmatix opens filtered to
the platform.

## Checking the setup

Settings → **Check frontends** reads back what the setups wrote and lists, with ✓ or ⚠:

- ES-DE: access to its data folder, the DOGMATIX find rule *for this build*, the `.dgmtx`
  extension and Dogmatix command in each system, the shortcut's gamelist entry and its cover;
- iiSU: access to `iiSULauncher` and the DOGMATIX emulator in each console of `emuladores.json`;
- the `.dgmtx` shortcut in every console folder (all Daijishō needs: its own configuration
  cannot be read from outside).

Each warning has **Repair**, which runs the same setup as its Settings row (or asks for the
folder again when the permission was lost), and the report is checked again afterwards.

With a release and a debug build installed, each one's setup adds its own `<entry>` to the
DOGMATIX find rule, after the ones already there: ES-DE launches the first installed entry,
so the order (which build the shortcut opens) stays as it was.

## Multi-disc games

When every disc of a multi-disc release has been downloaded, Dogmatix gathers them into one
folder named after the game with an `.m3u` extension, holding the discs (and their tracks) next
to a playlist of the same name:

```
psx/Final Fantasy VII (USA).m3u/
psx/Final Fantasy VII (USA).m3u/Final Fantasy VII (USA).m3u
psx/Final Fantasy VII (USA).m3u/Final Fantasy VII (USA) (Disc 1).cue
psx/Final Fantasy VII (USA).m3u/Final Fantasy VII (USA) (Disc 1).bin
…
```

This is ES-DE's *directories interpreted as files* layout: ES-DE lists the folder as a single
game and launches the playlist inside, and the emulator swaps discs from it. Dogmatix only
does it for systems whose emulators read playlists (PlayStation, Saturn, Sega CD, Dreamcast,
GameCube, PC Engine CD, 3DO, Neo Geo CD, Amiga, C64, MSX…), never for PS2 or Xbox, and a disc
downloaded later joins the folder and the playlist. Launchers that scan sub-folders file by
file may list the playlist and the discs separately; Settings → *Multi-disc games (.m3u)*
turns the whole behaviour off.

## Any other launcher

Anything that can fire an Android intent works:

```
am start -a android.intent.action.VIEW \
    -n com.cortinadev.dogmatix/com.cortinadev.dogmatix.MainActivity \
    -d <content-uri-of-the-dgmtx-file> --grant-read-uri-permission
```

or skip the file entirely and send the `dogmatix://library?…` deep link as the data URI.
