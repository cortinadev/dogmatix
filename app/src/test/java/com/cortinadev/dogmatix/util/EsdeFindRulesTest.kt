package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The DOGMATIX find rule when release and debug builds take turns running the ES-DE setup. */
class EsdeFindRulesTest {

    private val release = "com.cortinadev.dogmatix/com.cortinadev.dogmatix.MainActivity"
    private val debug = "com.cortinadev.dogmatix.debug/com.cortinadev.dogmatix.MainActivity"

    @Test
    fun anotherBuildGetsItsEntryAfterTheExistingOne() {
        val releaseOnly = EsdeXml.findRulesWithDogmatix(null, release)!!
        val both = EsdeXml.findRulesWithDogmatix(releaseOnly, debug)!!

        // ES-DE launches the first installed entry: the order already there is kept.
        assertEquals(listOf(release, debug), EsdeXml.findRuleEntries(both))
        assertEquals(1, Regex("<emulator ").findAll(both).count())
        assertEquals(
            "            <entry>$release</entry>\n            <entry>$debug</entry>\n",
            both.lines().filter { it.contains("<entry>") }.joinToString("") { "$it\n" }
        )
        assertNull(EsdeXml.findRulesWithDogmatix(both, release))
        assertNull(EsdeXml.findRulesWithDogmatix(both, debug))
    }

    @Test
    fun aHandWrittenEmulatorIsCompletedNotDuplicated() {
        val noRule = "<ruleList>\n    <emulator name=\"DOGMATIX\">\n    </emulator>\n    <emulator name=\"OTHER\"><rule type=\"androidpackage\"><entry>x/y</entry></rule></emulator>\n</ruleList>\n"
        val fixed = EsdeXml.findRulesWithDogmatix(noRule, debug)!!
        assertEquals(listOf(debug), EsdeXml.findRuleEntries(fixed))
        assertEquals(2, Regex("<emulator ").findAll(fixed).count())
        // The other emulator's entry is not touched.
        assertEquals(true, fixed.contains("<entry>x/y</entry>"))

        val oneLine = "<ruleList><emulator name=\"DOGMATIX\"><rule type=\"androidpackage\"><entry>$release</entry></rule></emulator></ruleList>"
        assertEquals(listOf(release, debug), EsdeXml.findRuleEntries(EsdeXml.findRulesWithDogmatix(oneLine, debug)!!))
        assertNull(EsdeXml.findRuleEntries("<ruleList></ruleList>"))
    }
}
