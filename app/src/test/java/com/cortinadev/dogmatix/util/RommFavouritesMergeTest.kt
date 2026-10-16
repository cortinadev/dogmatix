package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommFavouritesMergeTest {

    @Test
    fun `the first sync is the union and removes nothing`() {
        val plan = RommFavouritesMerge.plan(local = setOf(1, 2), remote = setOf(2, 3), base = null, known = setOf(3))
        assertEquals(setOf(1), plan.addRemote)
        assertEquals(setOf(3), plan.addLocal)
        assertTrue(plan.removeRemote.isEmpty() && plan.removeLocal.isEmpty())
        assertEquals(setOf(1, 2, 3), plan.base)
        assertTrue(plan.additive)
    }

    @Test
    fun `a star added here goes up, one taken away here comes off the server`() {
        val plan = RommFavouritesMerge.plan(local = setOf(1, 4), remote = setOf(1, 2), base = setOf(1, 2), known = setOf(2))
        assertEquals(setOf(4), plan.addRemote)
        assertEquals(setOf(2), plan.removeRemote)
        assertTrue(plan.addLocal.isEmpty() && plan.removeLocal.isEmpty())
        assertEquals(setOf(1, 4), plan.base)
    }

    @Test
    fun `a favourite added on the server comes down, one removed there loses its star`() {
        val plan = RommFavouritesMerge.plan(local = setOf(1, 2), remote = setOf(1, 5), base = setOf(1, 2), known = setOf(5))
        assertEquals(setOf(5), plan.addLocal)
        assertEquals(setOf(2), plan.removeLocal)
        assertTrue(plan.addRemote.isEmpty() && plan.removeRemote.isEmpty())
    }

    @Test
    fun `the server's favourites this device cannot place are left alone`() {
        val plan = RommFavouritesMerge.plan(local = setOf(1), remote = setOf(1, 99), base = setOf(1), known = emptySet())
        assertTrue(plan.removeRemote.isEmpty())
        assertTrue(plan.addLocal.isEmpty())
    }

    @Test
    fun `a wiped server does not wipe the stars`() {
        val plan = RommFavouritesMerge.plan(local = setOf(1, 2, 3), remote = emptySet(), base = setOf(1, 2, 3), known = emptySet())
        assertTrue(plan.removeLocal.isEmpty())
        assertEquals(setOf(1, 2, 3), plan.addRemote)
        assertTrue(plan.additive)
    }

    @Test
    fun `remembers the agreed state of games that cannot be placed now`() {
        val plan = RommFavouritesMerge.plan(local = setOf(1), remote = setOf(1), base = setOf(1, 7), known = emptySet())
        assertEquals(setOf(1, 7), plan.base)
    }

    @Test
    fun `picks the account's favourites collection`() {
        val refs = listOf(
            RommFavouritesMerge.CollectionRef(1, "Favorites", ownerId = 2, isFavourite = true),
            RommFavouritesMerge.CollectionRef(2, "RPGs", ownerId = 1, isFavourite = false),
            RommFavouritesMerge.CollectionRef(3, "Favourites", ownerId = 1, isFavourite = false)
        )
        assertEquals(3, RommFavouritesMerge.pickFavourites(refs, me = 1)?.id)
        assertEquals(1, RommFavouritesMerge.pickFavourites(refs, me = 2)?.id)
        assertNull(RommFavouritesMerge.pickFavourites(refs.take(2), me = 1))
    }
}
