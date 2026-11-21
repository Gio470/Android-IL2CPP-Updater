package org.updater.nullbit

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.updater.MatchStatus

/**
 * The port against the tool it was ported from.
 *
 * The expectations are NullUpdater's own output, captured by running the Java tool over the same
 * two fixtures with a ceiling of one match and patterns written out. It is checked at both levels:
 * the signature it takes for each function, byte for byte, and what that signature then matches.
 */
class NullUpdaterOracleTest {

    private data class Case(
        val offset: String,
        val pattern: String,
        val settled: Boolean,
        val matchCount: Int,
        val matches: List<String>?,
        val firstMatches: List<String>?,
        val lastMatches: List<String>?,
        /** Set where the original's search reports matches that are not there. */
        val overreported: Boolean = false,
        val trueMatchCount: Int = 0,
        val trueFirstMatches: List<String>?,
        val trueLastMatches: List<String>?,
    )

    private val cases: List<Case> =
        Gson()
            .fromJson(
                javaClass.getResource("/nullupdater-oracle.json")!!.readText(),
                object : TypeToken<List<Case>>() {}.type,
            )

    private val oldBytes = javaClass.getResource("/fixture-old.so")!!.readBytes()
    private val newBytes = javaClass.getResource("/fixture-new.so")!!.readBytes()

    @Test
    fun `every signature and every match is the original's`() {
        val offsets = cases.map { Integer.decode(it.offset) }
        val results = NullUpdaterEngine.run(oldBytes, newBytes, offsets)

        assertEquals(cases.size, results.size)

        for ((case, result) in cases.zip(results)) {
            assertEquals(case.offset, result.offset, "offset")
            assertEquals(case.pattern, result.pattern, "pattern for ${case.offset}")
            // Where the original over-reports, the true positions are what this must find. Its
            // search is KMP with wildcards, and its failure function counts trailing wildcards as
            // a matched prefix: after one real hit it reports another every four bytes to the end
            // of the file. 0x400's signature ends in four wildcards, so the original claims 11,186
            // matches where 162 exist. Those 162 were read straight out of the fixture, not taken
            // from the original.
            val expectedCount = if (case.overreported) case.trueMatchCount else case.matchCount
            val expectedFirst = if (case.overreported) case.trueFirstMatches else case.firstMatches
            val expectedLast = if (case.overreported) case.trueLastMatches else case.lastMatches

            assertEquals(expectedCount, result.foundOffsets.size, "match count for ${case.offset}")

            if (!case.overreported) {
                case.matches?.let {
                    assertEquals(it, result.foundOffsets, "matches for ${case.offset}")
                }
            }
            expectedFirst?.let {
                assertEquals(it, result.foundOffsets.take(it.size), "first matches")
            }
            expectedLast?.let {
                assertEquals(it, result.foundOffsets.takeLast(it.size), "last matches")
            }

            if (case.settled) {
                assertTrue(
                    result.note?.contains("could not get under the ceiling") == true,
                    "${case.offset} should say it gave up on the ceiling, said ${result.note}",
                )
            }
        }
    }

    @Test
    fun `the seven that settle resolve to one address each`() {
        val settled = cases.filterNot { it.settled }
        val results =
            NullUpdaterEngine.run(oldBytes, newBytes, settled.map { Integer.decode(it.offset) })

        assertEquals(settled.size, results.count { it.status == MatchStatus.RESOLVED })
    }

    @Test
    fun `a signature ends at the ret that ends its function`() {
        val disassembler = org.updater.createArm64Disassembler()
        try {
            // Every fixture function ends in `ret` (c0 03 5f d6), and a mode-zero signature stops
            // there: the last four bytes of the window are that instruction.
            for (case in cases.filterNot { it.settled }) {
                val offset = Integer.decode(case.offset)
                val end = NullUpdaterEngine.endOfFunction(disassembler, oldBytes, offset)
                assertTrue(end > offset, "no end found for ${case.offset}")
                assertEquals(
                    "c0035fd6",
                    oldBytes.copyOfRange(end - 4, end).joinToString("") { "%02x".format(it) },
                    "the function at ${case.offset} should end in ret",
                )
                assertEquals(
                    end - offset,
                    NullUpdaterEngine.pattern(disassembler, oldBytes, offset, 0)!!.split(" ").size,
                    "the mode-zero signature for ${case.offset} should cover the function",
                )
            }
        } finally {
            disassembler.close()
        }
    }
}
