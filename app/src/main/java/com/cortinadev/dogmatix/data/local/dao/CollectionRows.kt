package com.cortinadev.dogmatix.data.local.dao

/** Games the catalogue lists for one console (see DownloadableFileDao.gameCountsByConsole). */
data class ConsoleGameCount(val consoleId: String, val games: Int)

/** One file extension the catalogue lists for a console, as stored (`.zip`, `zip`…). */
data class ConsoleExtension(val consoleId: String, val fileExtension: String)
