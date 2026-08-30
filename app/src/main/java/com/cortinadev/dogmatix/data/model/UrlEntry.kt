package com.cortinadev.dogmatix.data.model

data class UrlEntry(
    val url: String,
    val contentType: ContentType = ContentType.GAME,
    val folders: List<String> = emptyList(),
    /** Disabled sources are kept but skipped by the rescan (their files drop out on the next one). */
    val enabled: Boolean = true
)

enum class ContentType {
    GAME,
    MISCELLANEOUS,
    RETROACHIEVEMENTS
}
