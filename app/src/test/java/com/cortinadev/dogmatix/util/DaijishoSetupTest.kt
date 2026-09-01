package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DaijishoSetupTest {

    @Test
    fun `the regex matches a shortcut and captures its name`() {
        val regex = Regex(DaijishoSetup.ACCEPTED_FILENAME_REGEX)
        val match = regex.find("${DgmtxFile.SHORTCUT_NAME}.${DgmtxFile.EXTENSION}")
        // Daijishō scrapes with group 1, so the name has to come back without the extension.
        assertEquals(DgmtxFile.SHORTCUT_NAME, match?.groupValues?.get(1))
    }

    @Test
    fun `the regex ignores other files in the same folder`() {
        val regex = Regex(DaijishoSetup.ACCEPTED_FILENAME_REGEX)
        for (name in listOf("Super Mario Bros.zip", "game.dgmtx.zip", "notes.txt")) {
            assertTrue(name, !regex.matches(name))
        }
    }

    @Test
    fun `the arguments carry the VIEW action, the launch component and the file uri`() {
        val lines = DaijishoSetup.amStartArguments("com.cortinadev.dogmatix").lines()
        assertEquals(
            listOf(
                "-a android.intent.action.VIEW",
                "-n com.cortinadev.dogmatix/com.cortinadev.dogmatix.MainActivity",
                // Not {file.path}: Dogmatix reads the shortcut through the content resolver.
                "-d {file.uri}",
                // Device-verified: Daijishō passes the grant through to the launch intent.
                "--grant-read-uri-permission"
            ),
            lines
        )
    }

    @Test
    fun `the debug build points at its own application id`() {
        val arguments = DaijishoSetup.amStartArguments("com.cortinadev.dogmatix.debug")
        assertTrue(arguments.contains("-n com.cortinadev.dogmatix.debug/com.cortinadev.dogmatix.MainActivity"))
    }
}
