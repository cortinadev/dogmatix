package com.cortinadev.dogmatix.data.model

/** Metadata about a game as returned by an online database (or its local cache). */
data class GameDetails(
    val title: String,
    val description: String,
    val genres: List<String>,
    val released: String,
    val developer: String,
    val imageUrl: String,
    /** Human-readable name of the database the data came from, for attribution. */
    val source: String,
    /** True when the database lists this entry for several platforms (its artwork may be from another one). */
    val multiPlatform: Boolean = false
)
