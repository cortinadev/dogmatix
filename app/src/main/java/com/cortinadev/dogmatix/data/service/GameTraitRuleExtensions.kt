package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.util.CollectionRuleExtension
import com.cortinadev.dogmatix.util.GameTraitRules
import com.cortinadev.dogmatix.util.SqlCondition
import javax.inject.Inject

/**
 * The library's genres and decades (game_traits) as smart-collection rules. Before a collection
 * with one of them is worked out, the traits of its consoles (every console when it names none)
 * are brought up to date, as the library's Genre / Decade filters do.
 */
abstract class GameTraitRuleExtension(
    private val traits: GameTraitsService,
    private val files: DownloadableFileDao
) : CollectionRuleExtension {
    override suspend fun prepare(rawJson: String, consoleIds: Set<String>) {
        val consoles = consoleIds.ifEmpty { files.consoleFileCounts().map { it.consoleId }.toSet() }
        runCatching { traits.ensure(consoles) }
    }
}

class GenreRuleExtension @Inject constructor(traits: GameTraitsService, files: DownloadableFileDao) : GameTraitRuleExtension(traits, files) {
    override val key: String = GameTraitRules.GENRES
    override fun condition(rawJson: String): SqlCondition? = GameTraitRules.genreCondition(rawJson)
}

class DecadeRuleExtension @Inject constructor(traits: GameTraitsService, files: DownloadableFileDao) : GameTraitRuleExtension(traits, files) {
    override val key: String = GameTraitRules.DECADES
    override fun condition(rawJson: String): SqlCondition? = GameTraitRules.decadeCondition(rawJson)
}
