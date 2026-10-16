package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.BuildConfig
import com.cortinadev.dogmatix.util.LibretroThumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds a game's box art on libretro-thumbnails (see [LibretroThumbnails]) and follows GitHub's
 * symlink stubs to the real image. Only the first bytes of each candidate are read; the image
 * itself is loaded (and disk-cached) by Coil.
 */
@Singleton
class ThumbnailService @Inject constructor() {

    private val userAgent = "Dogmatix/${BuildConfig.VERSION_NAME}"

    /**
     * The image URL for the ROM [romName] of [consoleId], or null when libretro has none (or does
     * not cover the console). Throws [IOException] when the server could not be asked properly
     * (offline, rate limited), so the caller does not remember that as "no cover".
     */
    suspend fun boxart(consoleId: String, romName: String): String? = withContext(Dispatchers.IO) {
        val system = LibretroThumbnails.systemFor(consoleId) ?: return@withContext null
        LibretroThumbnails.candidates(romName).firstNotNullOfOrNull { resolve(system, it, hops = 2) }
    }

    private fun resolve(system: LibretroThumbnails.System, name: String, hops: Int): String? {
        val url = LibretroThumbnails.boxartUrl(system, name)
        val head = head(url) ?: return null
        val target = LibretroThumbnails.symlinkTarget(head) ?: return url
        return if (hops > 0) resolve(system, target, hops - 1) else null
    }

    /** First bytes of the body (enough to tell a PNG from a symlink stub); null on 404. */
    private fun head(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", userAgent)
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.use { input ->
                    val buffer = ByteArray(301)
                    var read = 0
                    while (read < buffer.size) {
                        val n = input.read(buffer, read, buffer.size - read)
                        if (n < 0) break
                        read += n
                    }
                    buffer.copyOf(read)
                }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("HTTP $code for $url")
            }
        } finally {
            connection.disconnect()
        }
    }
}
