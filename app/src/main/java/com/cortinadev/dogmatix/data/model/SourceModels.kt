package com.cortinadev.dogmatix.data.model

data class Manufacturer(
    val id: String,
    val name: String,
    val consoles: List<Console> = emptyList()
)

data class Console(
    val id: String,
    val name: String,
    val urls: List<UrlEntry> = emptyList(),
    /** Configured chip label; empty = built-in default. */
    val shortName: String = "",
    /** Configured download-folder aliases; empty = built-in defaults. */
    val folderAliases: List<String> = emptyList()
)
