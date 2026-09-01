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

## Any other launcher

Anything that can fire an Android intent works:

```
am start -a android.intent.action.VIEW \
    -n com.cortinadev.dogmatix/com.cortinadev.dogmatix.MainActivity \
    -d <content-uri-of-the-dgmtx-file> --grant-read-uri-permission
```

or skip the file entirely and send the `dogmatix://library?…` deep link as the data URI.
