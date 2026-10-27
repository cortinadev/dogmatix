package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.service.ScanKind

/** One source of one console (a console's URL entry), as the scan and its results know it. */
data class SourceRef(val consoleId: String, val url: String)

/** What the app asks the scan for; requests that arrive while a run is under way wait together. */
sealed interface ScanRequest {
    /** Every source: incremental, or with [full] every one read again in full; [auto] = the background job. */
    data class All(val full: Boolean, val auto: Boolean = false) : ScanRequest
    /** These consoles, every source of them read again in full (a console's refresh button). */
    data class Consoles(val ids: Set<String>) : ScanRequest
    /** The sources that failed in the latest run, read incrementally. */
    data object RetryFailed : ScanRequest
    /**
     * Single sources, read incrementally: switched on again ([ScanKind.SOURCES]) or added by a new
     * app version to the bundled list ([ScanKind.NEW_SOURCES]).
     */
    data class Sources(val refs: Set<SourceRef>, val kind: ScanKind) : ScanRequest
}

/**
 * One scan run, made of every request that waited for it. Nothing asked for is lost and nothing is
 * read twice: [everything] covers single sources and retries, a full read wins over an incremental
 * one, and the consoles asked for are read in full even inside an incremental pass over everything.
 */
data class ScanPlan(
    val kind: ScanKind,
    val everything: Boolean,
    /** Every source read again in full ("Full rescan"). */
    val forceAll: Boolean,
    /** Consoles read again in full, every source of them. */
    val consoles: Set<String>,
    /** Single sources, read incrementally; empty when [everything]. */
    val sources: Set<SourceRef>,
    /** The failed sources of the latest run (as it stands when this run starts); false when [everything]. */
    val retryFailed: Boolean
) {
    /** Whether [consoleId]'s sources are read in full, without the incremental checks. */
    fun forced(consoleId: String): Boolean = forceAll || consoleId in consoles

    /** Whether this run reads every source of [consoleId], so its legacy rows may go once all answered. */
    fun coversConsole(consoleId: String): Boolean = everything || consoleId in consoles
}

object ScanRequests {

    /** The run that serves all of [requests] (in the order they came); null when there are none. */
    fun merge(requests: List<ScanRequest>): ScanPlan? {
        if (requests.isEmpty()) return null
        val all = requests.filterIsInstance<ScanRequest.All>()
        val everything = all.isNotEmpty()
        val forceAll = all.any { it.full }
        val consoles = requests.filterIsInstance<ScanRequest.Consoles>().flatMapTo(LinkedHashSet()) { it.ids }
        val singles = requests.filterIsInstance<ScanRequest.Sources>()
        val retry = requests.any { it is ScanRequest.RetryFailed }
        val kind = when {
            forceAll -> ScanKind.FULL
            everything -> if (requests.all { it is ScanRequest.All && it.auto }) ScanKind.AUTO else ScanKind.MANUAL
            consoles.isNotEmpty() -> ScanKind.CONSOLE
            singles.any { it.kind == ScanKind.SOURCES } -> ScanKind.SOURCES
            retry -> ScanKind.RETRY
            else -> ScanKind.NEW_SOURCES
        }
        return ScanPlan(
            kind = kind,
            everything = everything,
            forceAll = forceAll,
            consoles = if (forceAll) emptySet() else consoles,
            sources = if (everything) emptySet() else singles.flatMapTo(LinkedHashSet()) { it.refs },
            retryFailed = retry && !everything
        )
    }
}

/**
 * What a scan run removes once it ends: the rows and results of sources that are no longer set up
 * (deleted, switched off, or their console gone), and the legacy rows (`sourceUrl` = '', indexed
 * before sources were tracked) of consoles where every source was read and every one answered.
 * A run that was cancelled removes nothing.
 */
object ScanCleanup {

    /**
     * Consoles whose legacy rows can go: this run read every source of them ([covered]) and every
     * one of those reads ended well. [tasks] and [succeeded] count sources per console; a source
     * that failed, or never got to the end, keeps the console's legacy rows.
     */
    fun legacyConsoles(covered: Set<String>, tasks: Map<String, Int>, succeeded: Map<String, Int>): Set<String> =
        covered.filterTo(HashSet()) { (tasks[it] ?: 0) == (succeeded[it] ?: 0) }

    /**
     * Rows to delete, as (console, source) pairs from [indexed] ('' = legacy). [configured] holds,
     * for every console the run may clean, its enabled sources right now; with [everything], a
     * console missing from it is gone and loses all its rows.
     */
    fun staleRows(
        indexed: List<SourceRef>,
        configured: Map<String, Set<String>>,
        everything: Boolean,
        legacyConsoles: Set<String>
    ): List<SourceRef> = indexed.filter { ref ->
        val urls = configured[ref.consoleId] ?: if (everything) emptySet() else return@filter false
        if (ref.url.isEmpty()) ref.consoleId in legacyConsoles || ref.consoleId !in configured else ref.url !in urls
    }

    /** Scan results to delete: those of sources that are not set up (or switched off) any more. */
    fun staleResults(results: List<SourceRef>, configured: Map<String, Set<String>>, everything: Boolean): List<SourceRef> =
        results.filter { ref ->
            val urls = configured[ref.consoleId] ?: if (everything) emptySet() else return@filter false
            ref.url !in urls
        }
}
