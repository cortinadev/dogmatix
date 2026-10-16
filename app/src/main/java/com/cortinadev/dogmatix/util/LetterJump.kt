package com.cortinadev.dogmatix.util

/**
 * Jumping through the name-sorted library list by first letter (◀ ▶ on a gamepad, a letter
 * picked by touch). The list follows SQLite's byte order, so "Zelda" comes before "de Blob" and
 * names starting with a digit, a symbol or an accented letter sit at either end. Each name falls
 * in a *bucket* by its first character: `0` (before `A`), each upper-case letter, `1` (between
 * `Z` and `a`), each lower-case letter, `2` (after `z`); buckets follow that order, reversed for
 * Z→A. A bucket shows as its upper-case letter, or [OTHER]. The DAO counts games per bucket, so
 * a jump can land on a page that is not loaded yet ([Index]). Pure JVM for the tests.
 */
object LetterJump {

    const val OTHER = '#'

    /** Same rule as the DAO's `letterBuckets` query (single characters compared as bytes). */
    fun bucketOf(name: String): String {
        val c = name.firstOrNull() ?: return "0"
        return when {
            c < 'A' -> "0"
            c <= 'Z' -> c.toString()
            c < 'a' -> "1"
            c <= 'z' -> c.toString()
            else -> "2"
        }
    }

    fun letterOfBucket(bucket: String): Char =
        bucket.singleOrNull()?.takeIf { it in 'A'..'Z' || it in 'a'..'z' }?.uppercaseChar() ?: OTHER

    fun letterOf(name: String): Char = letterOfBucket(bucketOf(name))

    private fun rank(bucket: String): Int {
        val c = bucket.singleOrNull() ?: return 0
        return when {
            c == '0' -> 0
            c in 'A'..'Z' -> 1 + (c - 'A')
            c == '1' -> 27
            c in 'a'..'z' -> 28 + (c - 'a')
            else -> 54
        }
    }

    /** Rows [start] until [start] + [size] of the list begin with [letter]. */
    data class Run(val letter: Char, val start: Int, val size: Int)

    /** The letter runs of a whole list, from the number of games per bucket. */
    class Index(gamesPerBucket: Map<String, Int>, descending: Boolean) {
        val runs: List<Run>

        init {
            val ordered = gamesPerBucket.filterValues { it > 0 }.entries.sortedBy { rank(it.key) }
            val list = ArrayList<Run>()
            var start = 0
            for ((bucket, games) in if (descending) ordered.reversed() else ordered) {
                val letter = letterOfBucket(bucket)
                val last = list.lastOrNull()
                // `0` and `2` are both "#": merged when nothing lies between them.
                if (last != null && last.letter == letter) list[list.lastIndex] = last.copy(size = last.size + games)
                else list += Run(letter, start, games)
                start += games
            }
            runs = list
        }

        val size: Int get() = runs.lastOrNull()?.let { it.start + it.size } ?: 0

        /** The letters present, `#` first and then alphabetical (the picker's order). */
        val letters: List<Char> get() = runs.map { it.letter }.distinct().sortedWith(compareBy({ it != OTHER }, { it }))

        private fun runIndexAt(index: Int): Int = runs.indexOfLast { it.start <= index }

        /** First row of the run after the one holding [index]; null in the last one. */
        fun next(index: Int): Int? = runs.getOrNull(runIndexAt(index) + 1)?.start

        /** Start of the run holding [index] when past it, else of the previous run; null at the very start. */
        fun previous(index: Int): Int? {
            val i = runIndexAt(index)
            if (i < 0) return null
            if (index > runs[i].start) return runs[i].start
            return runs.getOrNull(i - 1)?.start
        }

        /** First row starting with [letter] in list order; null when none does. */
        fun firstOf(letter: Char): Int? = runs.firstOrNull { it.letter == letter }?.start
    }

    /** [Index.next] over the rows loaded so far ([letters] of each row); null when the next run is not among them. */
    fun next(letters: List<Char>, index: Int): Int? {
        val letter = letters.getOrNull(index) ?: return null
        for (i in index + 1 until letters.size) if (letters[i] != letter) return i
        return null
    }

    /** [Index.previous] over the loaded rows (always enough: they start at the top of the list). */
    fun previous(letters: List<Char>, index: Int): Int? {
        if (index <= 0 || index >= letters.size) return null
        var start = index
        while (start > 0 && letters[start - 1] == letters[index]) start--
        if (start < index) return start
        val letter = letters[start - 1]
        var previous = start - 1
        while (previous > 0 && letters[previous - 1] == letter) previous--
        return previous
    }
}
