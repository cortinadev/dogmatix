package com.cortinadev.dogmatix.util

import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * The account's own data about a game in RomM (`rom_user`): a play [status]
 * (`incomplete`, `finished`, `completed_100`, `retired`, `never_playing` or none) and two flags.
 */
data class RommUserProps(
    val status: String? = null,
    val backlogged: Boolean = false,
    val nowPlaying: Boolean = false
)

/**
 * One value of the details card's ◀ ▶ selector. RomM keeps two flags and a status; the selector
 * shows them as one value, read with [of] and written with [changesFrom] so the two round-trip:
 * "Playing" wins over the status, the status over "Backlog".
 */
enum class RommPlayState(val apiStatus: String?) {
    NONE(null),
    BACKLOG(null),
    PLAYING(null),
    INCOMPLETE("incomplete"),
    FINISHED("finished"),
    COMPLETED_100("completed_100"),
    RETIRED("retired"),
    NEVER_PLAYING("never_playing");

    /** The next value ◀ ▶ lands on, wrapping around. */
    fun step(delta: Int): RommPlayState = entries[(ordinal + delta).mod(entries.size)]

    /** [current] once this value is chosen. "Playing" keeps the status (RomM shows both). */
    fun applyTo(current: RommUserProps): RommUserProps = when (this) {
        NONE -> RommUserProps()
        BACKLOG -> RommUserProps(status = null, backlogged = true, nowPlaying = false)
        PLAYING -> current.copy(nowPlaying = true, backlogged = false)
        else -> RommUserProps(status = apiStatus, backlogged = false, nowPlaying = false)
    }

    /** The `PUT /api/roms/{id}/props` fields that turn [current] into this value (a cleared status is null). */
    fun changesFrom(current: RommUserProps): Map<String, Any?> {
        val target = applyTo(current)
        return buildMap {
            if (target.status != current.status) put("status", target.status)
            if (target.backlogged != current.backlogged) put("backlogged", target.backlogged)
            if (target.nowPlaying != current.nowPlaying) put("now_playing", target.nowPlaying)
        }
    }

    companion object {
        fun of(props: RommUserProps): RommPlayState = when {
            props.nowPlaying -> PLAYING
            props.status != null -> entries.firstOrNull { it.apiStatus == props.status } ?: NONE
            props.backlogged -> BACKLOG
            else -> NONE
        }

        fun fromName(name: String?): RommPlayState = entries.firstOrNull { it.name == name } ?: NONE

        /** An API status in any case or spelling RomM used; null when it is none of them. */
        fun normaliseStatus(value: String?): String? {
            val v = value?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.apiStatus == v }?.apiStatus
        }

        /**
         * The props request body. RomM 5 reads the fields at the top level and RomM 4 inside
         * `data`; each ignores what it does not know, so both shapes go in one body.
         */
        fun body(changes: Map<String, Any?>): String {
            fun fields() = JsonObject().apply {
                changes.forEach { (key, value) ->
                    when (value) {
                        null -> add(key, JsonNull.INSTANCE)
                        is Boolean -> addProperty(key, value)
                        is Number -> addProperty(key, value)
                        else -> addProperty(key, value.toString())
                    }
                }
            }
            return fields().apply { add("data", fields()) }.toString()
        }
    }
}
