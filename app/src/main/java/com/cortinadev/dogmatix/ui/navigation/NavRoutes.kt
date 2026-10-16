package com.cortinadev.dogmatix.ui.navigation

import com.cortinadev.dogmatix.R

sealed class NavRoutes(val route: String, val labelRes: Int, val icon: Int) {
    object Home : NavRoutes("home", R.string.nav_library, R.drawable.ic_search)
    object Downloads : NavRoutes("downloads", R.string.nav_downloads, R.drawable.ic_arrow_down)
    object Collection : NavRoutes("collection", R.string.nav_collection, R.drawable.ic_collection)
    object Sources : NavRoutes("sources", R.string.nav_sources, R.drawable.ic_folder)
    object Settings : NavRoutes("settings", R.string.nav_settings, R.drawable.ic_settings)
    object Contact : NavRoutes("contact", R.string.nav_contact, R.drawable.ic_edit)
    object Romm : NavRoutes("romm", R.string.nav_romm, R.drawable.ic_arrow_up)

    companion object {
        /** The sections shown as tabs; Contact and RomM are reached from Settings. */
        val tabs by lazy { listOf(Home, Downloads, Collection, Sources, Settings) }
        val allRoutes by lazy { tabs + Contact + Romm }
    }
}
