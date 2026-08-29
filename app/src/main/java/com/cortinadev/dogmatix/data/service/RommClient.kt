package com.cortinadev.dogmatix.data.service

import android.util.Base64
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

class RommException(message: String) : IOException(message)

data class RommRom(val id: Int, val fsName: String, val fsSizeBytes: Long, val name: String)

data class RommPlatform(val id: Int, val slug: String, val fsSlug: String, val name: String, val displayName: String) {
    val label: String get() = displayName.ifBlank { name.ifBlank { slug } }
}

/**
 * The RomM endpoints Dogmatix uses: platform listing (for the console mapping) and the chunked
 * ROM upload. Base URL and token come from Settings → RomM. All paths live here.
 */
@Singleton
class RommClient @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    private suspend fun baseUrl(): String =
        settingsRepository.rommUrl.first().trim().trimEnd('/').ifEmpty { throw RommException("RomM server URL not set") }

    private suspend fun headers(): Map<String, String> =
        mapOf("Authorization" to authHeader(settingsRepository.rommToken.first()))

    /** Configured server URL (no trailing slash) or "" when RomM is not set up. */
    suspend fun configuredBaseUrl(): String = settingsRepository.rommUrl.first().trim().trimEnd('/')

    /** Headers a plain HTTP download from this server needs. */
    suspend fun downloadHeaders(): Map<String, String> = headers()

    /** Every ROM of [platformId], paged through `/api/roms`. */
    suspend fun roms(platformId: Int): List<RommRom> = withContext(Dispatchers.IO) {
        val base = baseUrl()
        val out = mutableListOf<RommRom>()
        var offset = 0
        val limit = 500
        while (true) {
            val response = JsonHttp.requireOk(JsonHttp.request("GET", "$base/api/roms?platform_ids=$platformId&limit=$limit&offset=$offset", headers()))
            val json = response.json ?: throw RommException("RomM returned no JSON")
            val items = when {
                json.isJsonArray -> json.asJsonArray
                json.isJsonObject -> json.asJsonObject.getAsJsonArray("items") ?: throw RommException("Unexpected /api/roms payload")
                else -> throw RommException("Unexpected /api/roms payload")
            }
            items.map { it.asJsonObject }.forEach { r ->
                val fsName = r.str("fs_name").ifEmpty { r.str("file_name") }
                if (fsName.isNotEmpty()) out += RommRom(
                    id = r.get("id").asInt,
                    fsName = fsName,
                    fsSizeBytes = r.get("fs_size_bytes")?.takeUnless { it.isJsonNull }?.asLong ?: 0L,
                    name = r.str("name")
                )
            }
            if (items.size() < limit) break
            offset += limit
        }
        out
    }

    /** Returns the number of platforms the server reports, as a connection check. */
    suspend fun testConnection(url: String, token: String): Int = withContext(Dispatchers.IO) {
        platforms(url.trim().trimEnd('/'), mapOf("Authorization" to authHeader(token))).size
    }

    suspend fun platforms(): List<RommPlatform> = withContext(Dispatchers.IO) { platforms(baseUrl(), headers()) }

    private fun platforms(base: String, headers: Map<String, String>): List<RommPlatform> {
        val response = JsonHttp.requireOk(JsonHttp.request("GET", "$base/api/platforms", headers))
        val array = when {
            response.json?.isJsonArray == true -> response.json.asJsonArray
            response.json?.isJsonObject == true -> response.json.asJsonObject.getAsJsonArray("items") ?: throw RommException("Unexpected /api/platforms payload")
            else -> throw RommException("RomM returned no JSON")
        }
        return array.map { it.asJsonObject }.map {
            RommPlatform(
                id = it.get("id").asInt,
                slug = it.str("slug"),
                fsSlug = it.str("fs_slug"),
                name = it.str("name"),
                displayName = it.str("display_name").ifEmpty { it.str("custom_name") }
            )
        }
    }

    /** Opens a chunked upload session and returns its id. */
    suspend fun uploadStart(platformId: Int, fileName: String, totalSize: Long, totalChunks: Int): String = withContext(Dispatchers.IO) {
        val response = JsonHttp.requireOk(
            JsonHttp.request(
                "POST", "${baseUrl()}/api/roms/upload/start",
                headers = headers() + mapOf(
                    "x-upload-platform" to platformId.toString(),
                    "x-upload-filename" to fileName,
                    "x-upload-total-size" to totalSize.toString(),
                    "x-upload-total-chunks" to totalChunks.toString()
                ),
                body = ByteArray(0)
            )
        )
        val obj = response.json?.takeIf { it.isJsonObject }?.asJsonObject ?: throw RommException("RomM did not return an upload session")
        listOf("upload_id", "id", "session_id").firstNotNullOfOrNull { obj.get(it)?.takeUnless { v -> v.isJsonNull }?.asString }
            ?: throw RommException("RomM upload session has no id")
    }

    suspend fun uploadChunk(uploadId: String, index: Int, bytes: ByteArray, length: Int) = withContext(Dispatchers.IO) {
        val payload = if (length == bytes.size) bytes else bytes.copyOf(length)
        JsonHttp.requireOk(
            JsonHttp.request(
                "PUT", "${baseUrl()}/api/roms/upload/$uploadId",
                headers = headers() + mapOf("x-chunk-index" to index.toString()),
                body = payload,
                contentType = "application/octet-stream",
                readTimeoutMs = 120_000
            )
        )
    }

    suspend fun uploadComplete(uploadId: String) = withContext(Dispatchers.IO) {
        JsonHttp.requireOk(JsonHttp.request("POST", "${baseUrl()}/api/roms/upload/$uploadId/complete", headers(), body = ByteArray(0), readTimeoutMs = 300_000))
    }

    suspend fun uploadCancel(uploadId: String) = withContext(Dispatchers.IO) {
        runCatching { JsonHttp.request("POST", "${baseUrl()}/api/roms/upload/$uploadId/cancel", headers(), body = ByteArray(0)) }
    }

    private fun JsonObject.str(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    companion object {
        /** `rmm_…` client tokens go as Bearer; `user:password` becomes HTTP Basic. */
        fun authHeader(token: String): String {
            val t = token.trim()
            return if (t.contains(':') && !t.startsWith("rmm_")) {
                "Basic " + Base64.encodeToString(t.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } else "Bearer $t"
        }
    }
}
