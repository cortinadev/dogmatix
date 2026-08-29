package com.cortinadev.dogmatix.ui.navigation

import com.cortinadev.dogmatix.R

sealed class NavRoutes(val route: String, val labelRes: Int, val icon: Int) {
    object Home : NavRoutes("home", R.string.nav_library, R.drawable.ic_search)
    object Downloads : NavRoutes("downloads", R.string.nav_downloads, R.drawable.ic_arrow_down)
    object Sources : NavRoutes("sources", R.string.nav_sources, R.drawable.ic_folder)
    object Settings : NavRoutes("settings", R.string.nav_settings, R.drawable.ic_settings)
    object Contact : NavRoutes("contact", R.string.nav_contact, R.drawable.ic_edit)
    object Romm : NavRoutes("romm", R.string.nav_romm, R.drawable.ic_arrow_up)

    companion object {
        /** The four sections shown as tabs; Contact and RomM are reached from Settings. */
        val tabs by lazy { listOf(Home, Downloads, Sources, Settings) }
        val allRoutes by lazy { tabs + Contact + Romm }
    }
}
