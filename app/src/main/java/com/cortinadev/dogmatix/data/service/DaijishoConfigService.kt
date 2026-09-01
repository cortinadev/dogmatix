package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.util.DaijishoSetup
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assisted Daijishō setup: deploys the `.dgmtx` shortcuts and works out the values for its
 * *Add an emulator* form.
 *
 * There is no one-button version here. Daijishō stores platforms and players in its private
 * database and declares no intent filter, deep link or configuration file we could write, so
 * the entry has to be typed in *Settings → Library → Add an emulator*. What Dogmatix can do is
 * put the shortcuts in place and hand over the three values, so nothing has to be transcribed.
 */
@Singleton
class DaijishoConfigService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shortcuts: FrontendShortcutService
) {

    /** The values to type in Daijishō, plus how many shortcuts were deployed. */
    data class Setup(
        val shortcutsWritten: Int,
        val shortcutsFailed: Int,
        val name: String,
        val amStartArguments: String,
        val acceptedFilenameRegex: String
    )

    suspend fun prepare(): Setup {
        val deployed = shortcuts.deployShortcuts()
        return Setup(
            shortcutsWritten = deployed.written,
            shortcutsFailed = deployed.failed,
            name = DaijishoSetup.NAME,
            amStartArguments = DaijishoSetup.amStartArguments(context.packageName),
            acceptedFilenameRegex = DaijishoSetup.ACCEPTED_FILENAME_REGEX
        )
    }
}
