package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The few TorBox endpoints a debrid download needs. Every endpoint name and parameter lives
 * here so an API rename touches one file. Key: Settings → "TorBox API key".
 */
@Singleton
class TorBoxClient @Inject constructor(
    private val settingsRepository: SettingsRepository
) : DebridClient {
    override val provider = DebridProvider.TORBOX

    private suspend fun apiKey(): String =
        settingsRepository.torboxApiKey.first().trim().ifEmpty { throw DebridAuthException("API key not set") }

    /** Returns the account e-mail (or plan) for a "Connected as …" toast. */
    override suspend fun validateKey(key: String): String = withContext(Dispatchers.IO) {
        val data = call("GET", "/api/user/me", key = key).asJsonObject
        data.str("email").ifEmpty { data.str("plan").ifEmpty { "ok" } }
    }

    override suspend fun isCached(hash: String): Boolean = checkCached(hash) != null

    /** Files of a cached torrent, or null when TorBox does not have [hash] yet. */
    suspend fun checkCached(hash: String): List<DebridFile>? = withContext(Dispatchers.IO) {
        val data = call("GET", "/api/torrents/checkcached?hash=${enc(hash)}&format=list&list_files=true")
        val entry = data.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull { it.asJsonObject.str("hash").equals(hash, ignoreCase = true) }
            ?: data.takeIf { it.isJsonObject && it.asJsonObject.has("files") }
            ?: return@withContext null
        entry.asJsonObject.files()
    }

    /** Adds [magnet] to the account (idempotent on TorBox) and returns its torrent id. */
    override suspend fun createTorrent(magnet: String): String = withContext(Dispatchers.IO) {
        val (body, contentType) = JsonHttp.multipartBody(mapOf("magnet" to magnet, "seed" to "3", "allow_zip" to "false"))
        val data = call("POST", "/api/torrents/createtorrent", body = body, contentType = contentType).asJsonObject
        data.get("torrent_id")?.asInt?.toString() ?: throw DebridException("TorBox did not return a torrent id")
    }

    override suspend fun getTorrent(id: String): DebridTorrent = withContext(Dispatchers.IO) {
        // `mylist?id=` answers 500 while a fresh torrent is still in metaDL: fall back to the full list.
        val data = runCatching { call("GET", "/api/torrents/mylist?id=$id&bypass_cache=true") }
            .getOrElse { e -> if (e is DebridAuthException) throw e else call("GET", "/api/torrents/mylist?bypass_cache=true") }
        val obj = when {
            data.isJsonObject -> data.asJsonObject
            data.isJsonArray -> data.asJsonArray.map { it.asJsonObject }.firstOrNull { it.get("id")?.asInt?.toString() == id }
            else -> null
        } ?: throw DebridException("Torrent $id not found on TorBox (it dropped it: uncached torrents need a plan that fetches them)")
        DebridTorrent(
            id = obj.get("id").asInt.toString(),
            hash = obj.str("hash"),
            name = obj.str("name"),
            progress = obj.get("progress")?.takeUnless { it.isJsonNull }?.asFloat ?: 0f,
            downloadFinished = obj.get("download_finished")?.takeUnless { it.isJsonNull }?.asBoolean ?: false,
            files = obj.files()
        )
    }

    /** A direct (time-limited) download link for one file of a finished torrent. */
    override suspend fun requestDownload(torrentId: String, fileId: Int): String = withContext(Dispatchers.IO) {
        val key = apiKey()
        val data = call("GET", "/api/torrents/requestdl?token=${enc(key)}&torrent_id=$torrentId&file_id=$fileId", key = key)
        data.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.startsWith("http") }
            ?: throw DebridException("TorBox did not return a download link")
    }

    /** Best effort: removes the torrent from the account once the file is on disk. */
    override suspend fun delete(torrentId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val body = """{"torrent_id":$torrentId,"operation":"delete"}""".toByteArray()
            call("POST", "/api/torrents/controltorrent", body = body, contentType = JsonHttp.JSON)
        }
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
        if (!response.ok) android.util.Log.w("TorBoxClient", "HTTP ${response.code} for ${path.substringBefore('?')}: ${response.body.take(300)}")
        if (response.code == 401 || response.code == 403) throw DebridAuthException("API key rejected (HTTP ${response.code})")
        val json = response.json?.takeIf { it.isJsonObject }?.asJsonObject
        if (!response.ok) throw DebridException("TorBox HTTP ${response.code}: ${json?.str("detail").orEmpty().ifEmpty { response.body.take(120) }}")
        if (json == null) throw DebridException("TorBox returned no JSON")
        if (json.get("success")?.takeUnless { it.isJsonNull }?.asBoolean == false) throw DebridException("TorBox: ${json.str("detail").ifEmpty { json.str("error") }}")
        return json.get("data") ?: JsonObject()
    }

    /** `files` is `null` (not `[]`) while TorBox has not fetched the metadata yet. */
    private fun JsonObject.files(): List<DebridFile> =
        get("files")?.takeIf { it.isJsonArray }?.asJsonArray?.mapIndexed { i, f -> f.asJsonObject.toFile(i) }.orEmpty()

    private fun JsonObject.toFile(index: Int) = DebridFile(
        id = get("id")?.takeUnless { it.isJsonNull }?.asInt ?: index,
        name = str("name").ifEmpty { str("short_name") },
        size = get("size")?.takeUnless { it.isJsonNull }?.asLong ?: 0L
    )

    private fun JsonObject.str(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val BASE_URL = "https://api.torbox.app/v1"
    }
}
