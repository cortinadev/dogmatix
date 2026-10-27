package com.cortinadev.dogmatix.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Who archive.org says is signed in; [name] when its answer gives one. */
data class IaIdentity(val name: String?)

/** The answer of `archive.org/services/user.php?op=whoami`. Pure, for the tests. */
object IaAuthJson {

    /** A valid session: a 2xx with `"success": true`; anything else (401 "Not logged in", an error page) is null. */
    fun whoami(code: Int, body: String): IaIdentity? {
        if (code !in 200..299) return null
        val root = runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val success = root.get("success")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
        if (!success) return null
        val value = root.get("value")?.takeIf { it.isJsonObject }?.asJsonObject
        val name = sequenceOf(value, root).filterNotNull()
            .flatMap { o -> NAME_FIELDS.asSequence().map { o.text(it) } }
            .firstOrNull { !it.isNullOrBlank() }
        return IaIdentity(name?.trim())
    }

    private val NAME_FIELDS = listOf("screenname", "username", "itemname", "email")

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
