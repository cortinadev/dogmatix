package com.cortinadev.dogmatix.data.model

/** Which debrid service (if any) fetches torrents server-side before the HTTP download. */
enum class DebridProvider(val label: String) {
    NONE("Off"),
    TORBOX("TorBox"),
    REAL_DEBRID("Real-Debrid");

    companion object {
        fun fromName(name: String?): DebridProvider = entries.firstOrNull { it.name == name } ?: NONE
    }
}
