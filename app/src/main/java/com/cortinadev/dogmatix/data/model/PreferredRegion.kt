package com.cortinadev.dogmatix.data.model

import java.util.Locale

/**
 * The user's region, used to pick which version of a game the library downloads (onboarding and
 * Settings). [order] ranks the library's region tags for them: their own region first, then the
 * closest fallbacks. Tags not listed score nothing.
 */
enum class PreferredRegion(val order: List<String>) {
    EUROPE(listOf("Europe", "World", "UK", "Australia", "USA")),
    SPAIN(listOf("Spain", "Europe", "World", "USA")),
    FRANCE(listOf("France", "Europe", "World", "Canada", "USA")),
    GERMANY(listOf("Germany", "Europe", "World", "USA")),
    ITALY(listOf("Italy", "Europe", "World", "USA")),
    UK(listOf("UK", "United Kingdom", "Europe", "World", "Australia", "USA")),
    USA(listOf("USA", "World", "Canada", "Europe", "Australia")),
    JAPAN(listOf("Japan", "World", "Asia", "USA")),
    BRAZIL(listOf("Brazil", "USA", "World", "Europe")),
    KOREA(listOf("Korea", "Asia", "World", "Japan", "USA")),
    CHINA(listOf("China", "Asia", "World", "Japan", "USA")),
    AUSTRALIA(listOf("Australia", "Europe", "World", "USA"));

    companion object {
        fun fromName(name: String?): PreferredRegion? = entries.firstOrNull { it.name == name }

        private val europe = setOf(
            "PT", "NL", "BE", "LU", "CH", "SE", "NO", "DK", "FI", "IS", "PL", "CZ", "SK", "HU", "GR",
            "RO", "BG", "HR", "SI", "RS", "BA", "EE", "LV", "LT", "MT", "CY", "UA", "RU", "TR"
        )
        private val americas = setOf(
            "US", "CA", "MX", "AR", "CL", "CO", "PE", "VE", "UY", "EC", "BO", "PY", "CR", "PA", "DO",
            "GT", "HN", "SV", "NI", "CU", "PR"
        )

        /** A default from the device's country (or, without one, its language). */
        fun forLocale(locale: Locale = Locale.getDefault()): PreferredRegion =
            when (val country = locale.country.uppercase(Locale.ROOT)) {
                "ES" -> SPAIN
                "FR", "MC" -> FRANCE
                "DE", "AT" -> GERMANY
                "IT", "SM" -> ITALY
                "GB", "IE" -> UK
                "JP" -> JAPAN
                "BR" -> BRAZIL
                "KR" -> KOREA
                "CN", "TW", "HK", "MO" -> CHINA
                "AU", "NZ" -> AUSTRALIA
                in americas -> USA
                in europe -> EUROPE
                else -> when (locale.language.lowercase(Locale.ROOT)) {
                    "es" -> SPAIN
                    "fr" -> FRANCE
                    "de" -> GERMANY
                    "it" -> ITALY
                    "ja" -> JAPAN
                    "pt" -> BRAZIL
                    "ko" -> KOREA
                    "zh" -> CHINA
                    "en", "" -> USA
                    else -> EUROPE
                }
            }
    }
}
