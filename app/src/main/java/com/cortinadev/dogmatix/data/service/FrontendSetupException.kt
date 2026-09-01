package com.cortinadev.dogmatix.data.service

import android.content.Context
import com.cortinadev.dogmatix.R

/**
 * Failures of the one-button frontend setups (ES-DE, iiSU) that deserve their own message
 * instead of a raw exception string in a toast. ViewModels surface [userMessage].
 */
sealed class FrontendSetupException(cause: Throwable? = null) : Exception(cause) {

    /** The stored/picked folder is missing, or not a writable directory. */
    class FolderNotWritable : FrontendSetupException()

    /** The picked folder is not the frontend's data directory; nothing was written. */
    class WrongFolder : FrontendSetupException()

    /** Every console downloads to the root or a custom folder — nothing a frontend can scan. */
    class NoConsoleFolders : FrontendSetupException()

    /** The frontend's configuration file exists but could not be read; nothing was changed. */
    class ConfigUnreadable(cause: Throwable) : FrontendSetupException(cause)

    /** The frontend's configuration file could not be parsed; nothing was changed. */
    class ConfigCorrupt(cause: Throwable) : FrontendSetupException(cause)

    fun userMessage(context: Context): String = context.getString(
        when (this) {
            is FolderNotWritable -> R.string.frontend_folder_not_writable
            is WrongFolder -> R.string.iisu_wrong_folder
            is NoConsoleFolders -> R.string.frontend_no_console_folders
            is ConfigUnreadable -> R.string.frontend_config_unreadable
            is ConfigCorrupt -> R.string.frontend_config_corrupt
        }
    )
}
