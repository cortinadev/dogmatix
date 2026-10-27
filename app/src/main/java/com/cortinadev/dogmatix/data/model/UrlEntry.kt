package com.cortinadev.dogmatix.data.model

data class UrlEntry(
    val url: String,
    val contentType: ContentType = ContentType.GAME,
    val folders: List<String> = emptyList(),
    /** Disabled sources are kept but skipped by the rescan (their files drop out on the next one). */
    val enabled: Boolean = true,
    /** Reserve addresses of a web source, tried in turn when a download from [url] fails. */
    val mirrors: List<String> = emptyList(),
    /** How to read a web page that is not a folder listing; null = a listing as always. */
    val scrape: ScrapeRecipe? = null
)

/**
 * The games of a web page picked with CSS selectors (see `util/ScrapeRecipes`). [link], [name]
 * and [size] are relative to each [item] and may end in `@attr`; blank = the default.
 */
data class ScrapeRecipe(
    /** Each game on the page: "table.list tr", "ul.roms li", "a.rom". */
    val item: String,
    /** Its file link (`href` unless `@attr`); blank = the element itself if it is a link, else its first link. */
    val link: String = "",
    /** Its name; blank = the link's text, then its title. */
    val name: String = "",
    /** Its size; blank = no size. */
    val size: String = "",
    /** Values for `{page}` in the URL: "1-50", "A-Z", "0,A-Z"; blank with `{page}` = "1-100". */
    val pages: String = "",
    /** Extension for links that have none ("zip"). */
    val ext: String = ""
)

enum class ContentType {
    GAME,
    MISCELLANEOUS,
    RETROACHIEVEMENTS
}
