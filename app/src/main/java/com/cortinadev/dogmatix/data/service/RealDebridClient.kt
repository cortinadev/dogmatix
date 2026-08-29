package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real-Debrid REST API (`/rest/1.0`). Key: Settings → "Real-Debrid API key" (real-debrid.com/apitoken).
 * Flow: `addMagnet` → poll `torrents/info` until the file list shows up (`waiting_files_selection`)
 * → `selectFiles` with the one wanted file → poll until `downloaded` → `unrestrict/link` on the
 * single link. Adding torrents needs a premium account (free accounts get `permission_denied`).
 */
@Singleton
class RealDebridClient @Inject constructor(
    private val settingsRepository: SettingsRepository
) : DebridClient {
    override val provider = DebridProvider.REAL_DEBRID

    private suspend fun apiKey(): String =
        settingsRepository.realDebridApiKey.first().trim().ifEmpty { throw DebridAuthException("API key not set") }

    override suspend fun validateKey(key: String): String = withContext(Dispatchers.IO) {
        val user = call("GET", "/user", key = key).asJsonObject
        val name = user.str("username").ifEmpty { user.str("email") }.ifEmpty { "ok" }
        val type = user.str("type")
        if (type.isNotEmpty() && type != "premium") "$name ($type: premium needed for torrents)" else name
    }

    /** Real-Debrid removed its cache lookup endpoint; the status poll reveals cached torrents at once anyway. */
    override suspend fun isCached(hash: String): Boolean = false

    override suspend fun createTorrent(magnet: String): String = withContext(Dispatchers.IO) {
        val data = call("POST", "/torrents/addMagnet", body = JsonHttp.formBody(mapOf("magnet" to magnet)), contentType = JsonHttp.FORM_URLENCODED).asJsonObject
        data.str("id").ifEmpty { throw DebridException("Real-Debrid did not return a torrent id") }
    }

    override suspend fun getTorrent(id: String): DebridTorrent = withContext(Dispatchers.IO) {
        call("GET", "/torrents/info/$id").asJsonObject.toTorrent()
    }

    override suspend fun selectFile(torrentId: String, fileId: Int) = withContext(Dispatchers.IO) {
        call("POST", "/torrents/selectFiles/$torrentId", body = JsonHttp.formBody(mapOf("files" to fileId.toString())), contentType = JsonHttp.FORM_URLENCODED)
        Unit
    }

    override suspend fun requestDownload(torrentId: String, fileId: Int): String = withContext(Dispatchers.IO) {
        val info = call("GET", "/torrents/info/$torrentId").asJsonObject
        // One link per selected file, in selection order; we select a single file.
        val link = info.get("links")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.asString
            ?: throw DebridException("Real-Debrid has no link for the file yet (status ${info.str("status")})")
        val unrestricted = call("POST", "/unrestrict/link", body = JsonHttp.formBody(mapOf("link" to link)), contentType = JsonHttp.FORM_URLENCODED).asJsonObject
        unrestricted.str("download").takeIf { it.startsWith("http") } ?: throw DebridException("Real-Debrid did not return a download link")
    }

    override suspend fun delete(torrentId: String) = withContext(Dispatchers.IO) {
        runCatching { call("DELETE", "/torrents/delete/$torrentId") }
        Unit
    }

    // ---- helpers --------------------------------------------------------------------------------

    private suspend fun call(
        method: String,
        path: String,
        key: String? = null,
        body: ByteArray? = null,
        contentType: String? = null
    ): JsonElement {
        val token = key ?: apiKey()
        val response = JsonHttp.request(method, BASE_URL + path, headers = mapOf("Authorization" to "Bearer $token"), body = body, contentType = contentType)
        if (!response.ok) android.util.Log.w("RealDebridClient", "HTTP ${response.code} for ${path.substringBefore('?')}: ${response.body.take(300)}")
        val json = response.json
        val error = json?.takeIf { it.isJsonObject }?.asJsonObject?.str("error").orEmpty()
        if (response.code == 401) throw DebridAuthException("API key rejected (${error.ifEmpty { "HTTP 401" }})")
        if (response.code == 403) throw DebridAuthException(
            if (error == "permission_denied") "Real-Debrid refused: a premium account is required" else "Real-Debrid: ${error.ifEmpty { "HTTP 403" }}"
        )
        if (!response.ok) throw DebridException("Real-Debrid HTTP ${response.code}: ${error.ifEmpty { response.body.take(120) }}")
        return json ?: JsonObject()
    }

    private fun JsonObject.toTorrent(): DebridTorrent {
        val status = str("status")
        return DebridTorrent(
            id = str("id"),
            hash = str("hash"),
            name = str("filename").ifEmpty { str("original_filename") },
            progress = (get("progress")?.takeUnless { it.isJsonNull }?.asFloat ?: 0f) / 100f,
            downloadFinished = status == "downloaded",
            files = get("files")?.takeIf { it.isJsonArray }?.asJsonArray?.mapIndexed { i, f ->
                val o = f.asJsonObject
                DebridFile(
                    id = o.get("id")?.takeUnless { it.isJsonNull }?.asInt ?: (i + 1),
                    name = o.str("path").trimStart('/'),
                    size = o.get("bytes")?.takeUnless { it.isJsonNull }?.asLong ?: 0L
                )
            }.orEmpty(),
            failure = if (status in FAILED_STATES) "Real-Debrid reported '$status' for the torrent" else null
        )
    }

    private fun JsonObject.str(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    companion object {
        const val BASE_URL = "https://api.real-debrid.com/rest/1.0"
        private val FAILED_STATES = setOf("magnet_error", "error", "virus", "dead")
    }
}
