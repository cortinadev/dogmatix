package com.cortinadev.dogmatix.util

/**
 * Keeps the stars here and RomM's favourites collection in step: a three-way merge on RomM ids
 * against the set both sides agreed on at the last sync (the *base*).
 *  - Starred here only: new here (not in the base) → added on the server; in the base → the
 *    server took it away → un-starred here.
 *  - In the server's favourites only: new there → starred here; in the base → un-starred here
 *    → taken off the server.
 *  - No base (first sync, or another collection): the union, nothing is removed.
 * Only ids this device can place ([known]) take part; the server's other favourites are left alone.
 */
object RommFavouritesMerge {

    data class Plan(
        val addRemote: Set<Int>,
        val removeRemote: Set<Int>,
        val addLocal: Set<Int>,
        val removeLocal: Set<Int>,
        /** What to remember as agreed for the next sync. */
        val base: Set<Int>,
        /** The base was not used (first sync, or a suspicious mass removal): nothing is removed. */
        val additive: Boolean
    )

    /**
     * @param local ids of the games starred here that the server has
     * @param remote ids in the server's favourites collection
     * @param base ids agreed at the last sync; null when there was none
     * @param known ids this device can star (a library row of the game exists); [local] counts too
     */
    fun plan(local: Set<Int>, remote: Set<Int>, base: Set<Int>?, known: Set<Int>): Plan {
        val placeable = known + local
        // Every agreed favourite gone from one side at once looks like a wiped server or device,
        // not like the user removing them one by one: merge instead of deleting.
        val agreed = base?.intersect(placeable).orEmpty()
        val wiped = agreed.size >= MASS_REMOVAL && (remote.intersect(placeable).isEmpty() || local.isEmpty())
        val effectiveBase = if (wiped) null else base

        val kept = HashSet<Int>()
        for (id in placeable) {
            val here = id in local
            val there = id in remote
            val keep = when {
                here && there -> true
                !here && !there -> false
                effectiveBase == null -> true
                else -> id !in effectiveBase
            }
            if (keep) kept += id
        }
        return Plan(
            addRemote = kept - remote,
            removeRemote = remote.intersect(placeable) - kept,
            addLocal = kept - local,
            removeLocal = local - kept,
            // Ids nobody can place now keep their agreed state for when they come back.
            base = kept + (base.orEmpty() - placeable),
            additive = effectiveBase == null
        )
    }

    /** What [pickFavourites] needs of a collection. */
    data class CollectionRef(val id: Int, val name: String, val ownerId: Int?, val isFavourite: Boolean)

    /**
     * The account's favourites collection: the one RomM flags `is_favorite`, else one named like
     * "Favourites" (what servers before the flag used). Another user's collection never counts.
     */
    fun pickFavourites(collections: List<CollectionRef>, me: Int?): CollectionRef? {
        val mine = collections.filter { me == null || it.ownerId == null || it.ownerId == me }
        return mine.firstOrNull { it.isFavourite } ?: mine.firstOrNull { it.name.trim().lowercase() in FAVOURITE_NAMES }
    }

    /** Name of the collection created on the first star, as RomM's own web interface names it. */
    const val NEW_COLLECTION_NAME = "Favorites"

    private val FAVOURITE_NAMES = setOf("favorites", "favourites", "favoritos")
    private const val MASS_REMOVAL = 3
}
