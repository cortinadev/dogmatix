package com.cortinadev.dogmatix.data.search

import com.cortinadev.dogmatix.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * Stable keys of the Settings rows a search result can land on. SettingsScreen tags each row
 * with its key (`SettingsRow(key = …)`); a row added there gets a key here and an entry in
 * [SettingsSearchEntries].
 */
object SettingsRowKeys {
    const val DOWNLOAD_DIR = "download_dir"
    const val THEME = "theme"
    const val LANGUAGE = "language"
    const val ACCENT = "accent"
    const val LIST_COVERS = "list_covers"
    const val TAG_COLORS = "tag_colors"
    const val GAMEPAD_LAYOUT = "gamepad_layout"
    const val SWAP_BUTTONS = "swap_buttons"
    const val CONCURRENT = "concurrent"
    const val PER_HOST = "per_host"
    const val SPEED_LIMIT = "speed_limit"
    const val METADATA_TIMEOUT = "metadata_timeout"
    const val SEEDING = "seeding"
    const val AUTO_RETRY = "auto_retry"
    const val AUTO_UNZIP = "auto_unzip"
    const val MULTI_DISC = "multi_disc"
    const val SEPARATE_CONSOLE = "separate_console"
    const val DEBRID = "debrid"
    const val MAX_RESULTS = "max_results"
    const val REGION = "region"
    const val FAVORITE_LANGUAGES = "favorite_languages"
    const val FRONTEND_SHORTCUTS = "frontend_shortcuts"
    const val ESDE = "esde"
    const val IISU = "iisu"
    const val DAIJISHO = "daijisho"
    const val ROMM = "romm"
    const val ABOUT = "about"
    const val AUTO_SCAN = "auto_scan"
    const val AUTO_SCAN_WIFI = "auto_scan_wifi"
    const val AUTO_SCAN_CHARGING = "auto_scan_charging"
    const val VERIFY_DOWNLOADS = "verify_downloads"
    const val DAT_FILES = "dat_files"
    const val BATCH_NOTICE = "batch_notice"
    const val ESDE_FAVOURITES = "esde_favourites"
    const val FRONTEND_CHECK = "frontend_check"

    /** Rows only shown with another setting on: a search result lands on that one while they are hidden. */
    val shownWith: Map<String, String> = mapOf(
        AUTO_SCAN_WIFI to AUTO_SCAN,
        AUTO_SCAN_CHARGING to AUTO_SCAN,
        ESDE_FAVOURITES to ESDE
    )
}

/**
 * Every Settings row the search finds: its title, its hint (shown under it) and a few synonyms per
 * language (`search_kw_*`). Rows that only show with another setting on (the debrid key) are
 * found through that setting.
 */
object SettingsSearchEntries {
    private fun row(key: String, title: Int, hint: Int?, keywords: Int) = StaticSearchEntry(
        id = "setting:$key", group = SearchGroup.SETTINGS, title = title, hint = hint, keywords = keywords,
        target = SearchTarget.Setting(key)
    )

    val all: List<StaticSearchEntry> = listOf(
        row(SettingsRowKeys.DOWNLOAD_DIR, R.string.settings_download_directory, null, R.string.search_kw_download_dir),
        row(SettingsRowKeys.THEME, R.string.settings_theme, R.string.settings_theme_hint, R.string.search_kw_theme),
        row(SettingsRowKeys.LANGUAGE, R.string.settings_language, R.string.settings_language_hint, R.string.search_kw_language),
        row(SettingsRowKeys.ACCENT, R.string.settings_accent, R.string.settings_accent_hint, R.string.search_kw_accent),
        row(SettingsRowKeys.LIST_COVERS, R.string.settings_list_covers, R.string.settings_list_covers_hint, R.string.search_kw_list_covers),
        row(SettingsRowKeys.TAG_COLORS, R.string.settings_tag_colors, R.string.settings_tag_colors_hint, R.string.search_kw_tag_colors),
        row(SettingsRowKeys.GAMEPAD_LAYOUT, R.string.settings_gamepad_layout, R.string.settings_gamepad_layout_hint, R.string.search_kw_gamepad_layout),
        row(SettingsRowKeys.SWAP_BUTTONS, R.string.settings_swap_face_buttons, R.string.settings_swap_face_buttons_hint, R.string.search_kw_swap_buttons),
        row(SettingsRowKeys.CONCURRENT, R.string.settings_concurrent_label, null, R.string.search_kw_concurrent),
        row(SettingsRowKeys.PER_HOST, R.string.settings_per_host, R.string.settings_per_host_hint, R.string.search_kw_per_host),
        row(SettingsRowKeys.SPEED_LIMIT, R.string.settings_limit_label, R.string.settings_limit_hint, R.string.search_kw_speed_limit),
        row(SettingsRowKeys.METADATA_TIMEOUT, R.string.settings_metadata_timeout, R.string.settings_metadata_timeout_hint, R.string.search_kw_metadata_timeout),
        row(SettingsRowKeys.SEEDING, R.string.settings_seeding, R.string.settings_seeding_hint, R.string.search_kw_seeding),
        row(SettingsRowKeys.AUTO_RETRY, R.string.settings_auto_retry, R.string.settings_auto_retry_hint, R.string.search_kw_auto_retry),
        row(SettingsRowKeys.AUTO_UNZIP, R.string.settings_auto_unzip, R.string.settings_auto_unzip_hint, R.string.search_kw_auto_unzip),
        row(SettingsRowKeys.MULTI_DISC, R.string.settings_multi_disc, R.string.settings_multi_disc_hint, R.string.search_kw_multi_disc),
        row(SettingsRowKeys.SEPARATE_CONSOLE, R.string.settings_separate_by_console, R.string.settings_separate_hint, R.string.search_kw_separate_console),
        row(SettingsRowKeys.DEBRID, R.string.settings_debrid, R.string.settings_debrid_hint, R.string.search_kw_debrid),
        row(SettingsRowKeys.MAX_RESULTS, R.string.settings_max_results, R.string.settings_max_results_hint, R.string.search_kw_max_results),
        row(SettingsRowKeys.REGION, R.string.settings_region, R.string.settings_region_hint, R.string.search_kw_region),
        row(SettingsRowKeys.FAVORITE_LANGUAGES, R.string.settings_favorite_languages, R.string.settings_favorite_languages_hint, R.string.search_kw_favorite_languages),
        row(SettingsRowKeys.FRONTEND_SHORTCUTS, R.string.settings_frontend_shortcuts, R.string.settings_frontend_shortcuts_hint, R.string.search_kw_frontend_shortcuts),
        row(SettingsRowKeys.ESDE, R.string.settings_esde, R.string.settings_esde_hint, R.string.search_kw_esde),
        row(SettingsRowKeys.IISU, R.string.settings_iisu, R.string.settings_iisu_hint, R.string.search_kw_iisu),
        row(SettingsRowKeys.DAIJISHO, R.string.settings_daijisho, R.string.settings_daijisho_hint, R.string.search_kw_daijisho),
        row(SettingsRowKeys.ROMM, R.string.settings_romm, R.string.settings_romm_hint, R.string.search_kw_romm),
        row(SettingsRowKeys.ABOUT, R.string.settings_about, null, R.string.search_kw_about),
        row(SettingsRowKeys.AUTO_SCAN, R.string.settings_auto_scan, R.string.settings_auto_scan_hint, R.string.search_kw_auto_scan),
        row(SettingsRowKeys.AUTO_SCAN_WIFI, R.string.settings_auto_scan_wifi, R.string.settings_auto_scan_wifi_hint, R.string.search_kw_auto_scan_wifi),
        row(SettingsRowKeys.AUTO_SCAN_CHARGING, R.string.settings_auto_scan_charging, R.string.settings_auto_scan_charging_hint, R.string.search_kw_auto_scan_charging),
        row(SettingsRowKeys.VERIFY_DOWNLOADS, R.string.settings_verify_downloads, R.string.settings_verify_downloads_hint, R.string.search_kw_verify_downloads),
        row(SettingsRowKeys.DAT_FILES, R.string.settings_dat_files, R.string.settings_dat_files_hint_none, R.string.search_kw_dat_files),
        row(SettingsRowKeys.BATCH_NOTICE, R.string.settings_batch_notice, R.string.settings_batch_notice_hint, R.string.search_kw_batch_notice),
        row(SettingsRowKeys.ESDE_FAVOURITES, R.string.settings_esde_favourites, R.string.settings_esde_favourites_hint, R.string.search_kw_esde_favourites),
        row(SettingsRowKeys.FRONTEND_CHECK, R.string.settings_frontend_check, R.string.settings_frontend_check_hint, R.string.search_kw_frontend_check)
    )
}

/** Settings rows; resolved on every (re)build of the index, so a language change shows up. */
class SettingsSearchProvider @Inject constructor(private val strings: SearchStrings) : SearchProvider {
    override val entries: Flow<List<SearchEntry>> = flow { emit(strings.resolve(SettingsSearchEntries.all)) }
}
