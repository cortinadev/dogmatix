package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.util.MirrorUrls
import com.cortinadev.dogmatix.util.SourcesJson
import javax.inject.Inject
import javax.inject.Singleton

/** Reserve addresses (Sources → edit URL) for a web download, looked up from its console's sources. */
@Singleton
class SourceMirrors @Inject constructor(private val consoleDao: ConsoleDao) {

    /** The same file under each mirror of the source it was indexed from; empty when there are none. */
    suspend fun alternativesFor(file: DownloadableFileEntity): List<String> {
        if (file.isTorrent || !MirrorUrls.isWeb(file.downloadUrl)) return emptyList()
        val console = runCatching { consoleDao.getConsoleById(file.consoleId) }.getOrNull() ?: return emptyList()
        return SourcesJson.parseUrlEntries(console.urls).asSequence()
            .filter { it.mirrors.isNotEmpty() }
            .map { MirrorUrls.alternatives(file.downloadUrl, it.url, it.mirrors) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }
}
