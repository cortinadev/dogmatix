package com.cortinadev.dogmatix.util

/**
 * The three values Daijishō's *Add an emulator* form asks for, so Dogmatix can hand them over
 * ready to paste instead of making the user transcribe them.
 *
 * Daijishō keeps platforms and players in its own private database and exposes no intent,
 * deep link or configuration file, so — unlike ES-DE and iiSU — the last step has to be taken
 * by hand in *Settings → Library → Add an emulator*. A custom emulator there is global rather
 * than per platform, so one entry covers every console, it does not touch the platforms the
 * user already has, and it survives Daijishō's automatic platform updates (which would revert
 * an imported system).
 *
 * Pure JVM (no Android types) so it can be unit-tested.
 */
object DaijishoSetup {

    /** What to type in "Emulator name". */
    const val NAME = "Dogmatix"

    /**
     * What to type in "Accepted filename regex". Daijishō uses group 1 as the scraping
     * keyword, hence the capture around the name.
     */
    const val ACCEPTED_FILENAME_REGEX = "^(.*)\\.(?:${DgmtxFile.EXTENSION})$"

    /**
     * What to type in "Emulator am start arguments", one argument per line.
     *
     * `{file.uri}` (not `{file.path}`): Dogmatix reads the shortcut through the content
     * resolver, so it needs the `content://` URI Daijishō holds a grant for; a raw path would
     * need storage permissions Dogmatix does not ask for. `--grant-read-uri-permission` asks
     * Daijishō to extend that grant to Dogmatix; device-verified — the launch arrives with
     * `FLAG_GRANT_READ_URI_PERMISSION` and the deep link applies.
     */
    fun amStartArguments(packageName: String): String =
        "-a android.intent.action.VIEW\n" +
            "-n ${DgmtxFile.launchComponent(packageName)}\n" +
            "-d {file.uri}\n" +
            "--grant-read-uri-permission"
}
