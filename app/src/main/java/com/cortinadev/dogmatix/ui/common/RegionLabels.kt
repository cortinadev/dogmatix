package com.cortinadev.dogmatix.ui.common

import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.PreferredRegion

/** Name of each preferred region in Settings and the onboarding. */
val PreferredRegion.labelRes: Int
    get() = when (this) {
        PreferredRegion.EUROPE -> R.string.region_europe
        PreferredRegion.SPAIN -> R.string.region_spain
        PreferredRegion.FRANCE -> R.string.region_france
        PreferredRegion.GERMANY -> R.string.region_germany
        PreferredRegion.ITALY -> R.string.region_italy
        PreferredRegion.UK -> R.string.region_uk
        PreferredRegion.USA -> R.string.region_usa
        PreferredRegion.JAPAN -> R.string.region_japan
        PreferredRegion.BRAZIL -> R.string.region_brazil
        PreferredRegion.KOREA -> R.string.region_korea
        PreferredRegion.CHINA -> R.string.region_china
        PreferredRegion.AUSTRALIA -> R.string.region_australia
    }

/** The region [delta] steps away in the list, wrapping around (◀ ▶ on a stepper). */
fun PreferredRegion.cycled(delta: Int): PreferredRegion {
    val all = PreferredRegion.entries
    return all[((ordinal + delta) % all.size + all.size) % all.size]
}
