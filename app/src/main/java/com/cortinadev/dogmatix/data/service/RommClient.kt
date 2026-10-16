package com.cortinadev.dogmatix.data.service

import android.util.Base64
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.RommUploadSources
import com.cortinadev.dogmatix.util.RommCatalogRom
import com.cortinadev.dogmatix.util.RommCollectionInfo
import com.cortinadev.dogmatix.util.RommGameInfo
import com.cortinadev.dogmatix.util.RommJson
import com.cortinadev.dogmatix.util.RommPlayState
import com.cortinadev.dogmatix.util.RommUserProps
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
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
    private val settingsRepository: SettingsRepository,
    private val tls: RommTls
) {
    private suspend fun baseUrl(): String =
        settingsRepository.rommUrl.first().trim().trimEnd('/').ifEmpty { throw RommException("RomM server URL not set") }

    private suspend fun headers(): Map<String, String> =
        mapOf("Authorization" to authHeader(settingsRepository.rommToken.first()))

    /** Configured server URL (no trailing slash) or "" when RomM is not set up. */
    suspend fun configuredBaseUrl(): String = settingsRepository.rommUrl.first().trim().trimEnd('/')

    /** Headers a plain HTTP download from this server needs. */
    suspend fun downloadHeaders(): Map<String, String> {
        tls.awaitLoaded()
        return headers()
    }

    // romm-transporte
    /** Lets [connection] accept the server's trusted certificate (a no-op for any other server). */
    fun secure(connection: HttpURLConnection) = tls.secure(connection)

    /** Every RomM call: [JsonHttp.request] with the server's TLS trust (self-signed certificates). */
    private suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
        contentType: String? = null,
        readTimeoutMs: Int = 30_000,
        bodyLength: Int = body?.size ?: 0,
        onBodySent: ((Int) -> Unit)? = null
    ): JsonHttp.Response {
        tls.awaitLoaded()
        return JsonHttp.request(
            method, url, headers, body, contentType,
            readTimeoutMs = readTimeoutMs, bodyLength = bodyLength, onBodySent = onBodySent, configure = tls::secure
        )
    }

    /** Every ROM of [platformId], paged through `/api/roms`. */
    suspend fun roms(platformId: Int): List<RommRom> = withContext(Dispatchers.IO) {
        val base = baseUrl()
        val out = mutableListOf<RommRom>()
        var offset = 0
        val limit = 500
        while (true) {
            val response = JsonHttp.requireOk(request("GET", "$base/api/roms?platform_ids=$platformId&limit=$limit&offset=$offset", headers()))
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

    private suspend fun platforms(base: String, headers: Map<String, String>): List<RommPlatform> {
        val response = JsonHttp.requireOk(request("GET", "$base/api/platforms", headers))
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

    /**
     * Opens a chunked upload session and returns its id. The header carries an ASCII-safe name
     * and the body the real one, which RomM 5.3+ prefers (older versions ignore the body).
     */
    suspend fun uploadStart(platformId: Int, fileName: String, totalSize: Long, totalChunks: Int): String = withContext(Dispatchers.IO) {
        val target = JsonObject().apply { addProperty("filename", fileName) }.toString().toByteArray(Charsets.UTF_8)
        val response = JsonHttp.requireOk(
            request(
                "POST", "${baseUrl()}/api/roms/upload/start",
                headers = headers() + mapOf(
                    "x-upload-platform" to platformId.toString(),
                    "x-upload-filename" to RommUploadSources.headerName(fileName),
                    "x-upload-total-size" to totalSize.toString(),
                    "x-upload-total-chunks" to totalChunks.toString()
                ),
                body = target,
                contentType = JsonHttp.JSON
            )
        )
        val obj = response.json?.takeIf { it.isJsonObject }?.asJsonObject ?: throw RommException("RomM did not return an upload session")
        listOf("upload_id", "id", "session_id").firstNotNullOfOrNull { obj.get(it)?.takeUnless { v -> v.isJsonNull }?.asString }
            ?: throw RommException("RomM upload session has no id")
    }

    /** Sends the first [length] bytes of [bytes] as chunk [index]; [onSent] follows the bytes written. */
    suspend fun uploadChunk(uploadId: String, index: Int, bytes: ByteArray, length: Int, onSent: ((Int) -> Unit)? = null) = withContext(Dispatchers.IO) {
        JsonHttp.requireOk(
            request(
                "PUT", "${baseUrl()}/api/roms/upload/$uploadId",
                headers = headers() + mapOf("x-chunk-index" to index.toString()),
                body = bytes,
                contentType = "application/octet-stream",
                readTimeoutMs = 120_000,
                bodyLength = length,
                onBodySent = onSent
            )
        )
    }

    /** RomM assembles the chunks before answering: [readTimeoutMs] grows with the file. */
    suspend fun uploadComplete(uploadId: String, readTimeoutMs: Int = 300_000) = withContext(Dispatchers.IO) {
        JsonHttp.requireOk(request("POST", "${baseUrl()}/api/roms/upload/$uploadId/complete", headers(), body = ByteArray(0), readTimeoutMs = readTimeoutMs))
    }

    suspend fun uploadCancel(uploadId: String) = withContext(Dispatchers.IO) {
        runCatching { request("POST", "${baseUrl()}/api/roms/upload/$uploadId/cancel", headers(), body = ByteArray(0)) }
    }

    // romm-catalogo: the server catalogue, a game's details and play status, collections and
    // cover files. Every call of this block goes through [catalogCall] (and [asset] for image
    // bytes), so the transport's TLS trust applies by changing those two only. Payloads are read
    // by util/RommJson, which copes with what RomM 3, 4 and 5 send.

    /** One page of the ROMs of [platformId]; [itemCount] counts what the server sent, parsed or not. */
    data class CatalogPage(val roms: List<RommCatalogRom>, val itemCount: Int)

    suspend fun catalogPage(platformId: Int, offset: Int, limit: Int): CatalogPage = withContext(Dispatchers.IO) {
        // RomM 5 adds a letter index, the filter values and every ROM id unless told otherwise
        // (and leaves the files out); older servers ignore these flags.
        val query = "platform_ids=$platformId&limit=$limit&offset=$offset&with_files=true" +
            "&with_char_index=false&with_filter_values=false&with_rom_id_index=false&with_total=false"
        val json = JsonHttp.requireOk(catalogCall("GET", "/api/roms?$query", readTimeoutMs = 90_000)).json
            ?: throw RommException("RomM returned no JSON")
        CatalogPage(RommJson.catalogRoms(json), RommJson.items(json).size)
    }

    /** A game's metadata and the account's play data (`GET /api/roms/{id}`). */
    suspend fun gameInfo(romId: Int): RommGameInfo = withContext(Dispatchers.IO) {
        RommJson.gameInfo(JsonHttp.requireOk(catalogCall("GET", "/api/roms/$romId", readTimeoutMs = 45_000)).json)
            ?: throw RommException("Unexpected /api/roms/$romId payload")
    }

    /** Writes play data (`PUT /api/roms/{id}/props`); returns what the server now holds when it says. */
    suspend fun updateProps(romId: Int, changes: Map<String, Any?>): RommUserProps? = withContext(Dispatchers.IO) {
        if (changes.isEmpty()) return@withContext null
        val body = RommPlayState.body(changes).toByteArray(Charsets.UTF_8)
        RommJson.props(JsonHttp.requireOk(catalogCall("PUT", "/api/roms/$romId/props", body, JsonHttp.JSON)).json)
    }

    /** The id of the account the token belongs to (`GET /api/users/me`); null when unknown. */
    suspend fun currentUserId(): Int? = withContext(Dispatchers.IO) {
        RommJson.id(JsonHttp.requireOk(catalogCall("GET", "/api/users/me")).json)
    }

    /** The regular collections the account can see (`GET /api/collections`). */
    suspend fun collections(): List<RommCollectionInfo> = withContext(Dispatchers.IO) {
        RommJson.collections(JsonHttp.requireOk(catalogCall("GET", "/api/collections")).json)
    }

    /** Creates a collection; [favourite] makes it RomM's favourites collection. Returns its id. */
    suspend fun createCollection(name: String, favourite: Boolean): Int = withContext(Dispatchers.IO) {
        val (body, type) = JsonHttp.multipartBody(mapOf("name" to name, "description" to ""))
        val query = if (favourite) "?is_favorite=true&is_public=false" else ""
        RommJson.id(JsonHttp.requireOk(catalogCall("POST", "/api/collections$query", body, type)).json)
            ?: throw RommException("RomM did not return the new collection")
    }

    /** Adds [romIds] to [collection]: RomM 5's atomic route, else the whole list (older servers). */
    suspend fun addToCollection(collection: RommCollectionInfo, romIds: Collection<Int>) = withContext(Dispatchers.IO) {
        if (romIds.isEmpty()) return@withContext
        if (collectionRomsRoute("POST", collection.id, romIds)) return@withContext
        putCollectionRoms(collection.id, collection.name) { it + romIds }
    }

    suspend fun removeFromCollection(collection: RommCollectionInfo, romIds: Collection<Int>) = withContext(Dispatchers.IO) {
        if (romIds.isEmpty()) return@withContext
        if (collectionRomsRoute("DELETE", collection.id, romIds)) return@withContext
        putCollectionRoms(collection.id, collection.name) { it - romIds.toSet() }
    }

    /**
     * The bytes of an image (a cover path made absolute by `RommCovers.url`), at most [maxBytes];
     * the auth header only goes to this server. Throws on any non-2xx answer.
     */
    suspend fun asset(url: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val onServer = url.startsWith(baseUrl() + "/", ignoreCase = true)
        tls.awaitLoaded()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            // The server's trusted certificate (self-signed); a no-op for an image on another host.
            secure(connection)
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", JsonHttp.userAgent)
            if (onServer) headers().forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val code = connection.responseCode
            if (code !in 200..299) throw JsonHttp.HttpException(code, null)
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) throw RommException("Image larger than $maxBytes bytes")
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** True when the atomic `/roms` route did it; false when this server has no such route. */
    private suspend fun collectionRomsRoute(method: String, id: Int, romIds: Collection<Int>): Boolean {
        val body = """{"rom_ids":${romIds.distinct().joinToString(",", "[", "]")}}""".toByteArray(Charsets.UTF_8)
        val response = try {
            catalogCall(method, "/api/collections/$id/roms", body, JsonHttp.JSON)
        } catch (_: ProtocolException) {
            return false   // the HTTP stack refused a DELETE with a body
        }
        if (response.code == 404 || response.code == 405) return false
        JsonHttp.requireOk(response)
        return true
    }

    /** `PUT /api/collections/{id}` with the full ROM list, read fresh so nothing added meanwhile is lost. */
    private suspend fun putCollectionRoms(id: Int, name: String, change: (Set<Int>) -> Set<Int>) {
        val current = RommJson.collection(JsonHttp.requireOk(catalogCall("GET", "/api/collections/$id")).json)
            ?: throw RommException("Unexpected /api/collections/$id payload")
        val ids = change(current.romIds).joinToString(",", "[", "]")
        val (body, type) = JsonHttp.multipartBody(mapOf("name" to current.name.ifEmpty { name }, "rom_ids" to ids))
        JsonHttp.requireOk(catalogCall("PUT", "/api/collections/$id", body, type))
    }

    /** The one place this block talks to the server: [path] below the configured URL, with auth and its TLS trust ([request]). */
    private suspend fun catalogCall(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String? = null,
        readTimeoutMs: Int = 30_000
    ): JsonHttp.Response =
        request(method, baseUrl() + path, headers(), body, contentType, readTimeoutMs = readTimeoutMs)

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
