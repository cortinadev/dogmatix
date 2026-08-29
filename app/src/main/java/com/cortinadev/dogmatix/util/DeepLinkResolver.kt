package com.cortinadev.dogmatix.util

/**
 * Turns the loose values of a deep link into what the library filters actually store, so
 * `dogmatix://library?console=snes&region=japan` works as well as the exact
 * `console=super_nintendo_entertainment_system&region=Japan`.
 */
object DeepLinkResolver {

    /**
     * Console ids among [consoleIds] matching each requested value: the id itself (any case),
     * or anything [ConsoleFolderAliases.matches] accepts (short name, folder aliases, key).
     * Values matching nothing are dropped.
     */
    fun resolveConsoles(requested: Set<String>, consoleIds: Collection<String>): Set<String> =
        requested.flatMapTo(mutableSetOf()) { value ->
            consoleIds.filter { id -> id.equals(value, ignoreCase = true) || ConsoleFolderAliases.matches(id, value) }
        }

    /**
     * Each requested tag replaced by its spelling in [known] (case-insensitive). Tags that are
     * not known are kept verbatim: the catalogue may not cover them yet.
     */
    fun resolveTags(requested: Set<String>, known: Collection<String>): Set<String> =
        requested.mapTo(mutableSetOf()) { value -> known.firstOrNull { it.equals(value, ignoreCase = true) } ?: value }
}
