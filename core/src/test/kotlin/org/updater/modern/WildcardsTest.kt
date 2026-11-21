package org.updater.modern

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the ported masks against the TypeScript updater they came from.
 *
 * `wildcard-oracle.json` was produced by running that implementation over the .text of a real
 * AArch64 library and keeping one example of each distinct instruction shape. Every mask here is
 * therefore what the original produces, not what this port thinks it should produce.
 */
class WildcardsTest {

    private data class Row(
        val bytes: String,
        val mnemonic: String,
        val opStr: String,
        val mask: String,
        val maskStrict: String,
    )

    private val rows: List<Row> by lazy {
        val json =
            javaClass.getResourceAsStream("/wildcard-oracle.json")?.bufferedReader()?.readText()
                ?: error("wildcard-oracle.json is missing from the test resources")
        Gson().fromJson(json, Array<Row>::class.java).toList()
    }

    private fun bytesOf(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `the corpus is broad enough to be worth checking`() {
        assertTrue(rows.size > 1000, "expected a wide corpus, got ${rows.size} rows")
        assertTrue(rows.map { it.mnemonic }.distinct().size > 50, "expected many mnemonics")
        assertTrue(rows.count { it.mask.contains('?') } > 300, "expected plenty of masks")
    }

    @Test
    fun `portable masks match the original implementation`() {
        val wrong =
            rows
                .filter { Wildcards.mask(it.mnemonic, it.opStr, bytesOf(it.bytes)) != it.mask }
                .take(5)
                .map {
                    "${it.mnemonic} ${it.opStr}: expected ${it.mask}, got " +
                        Wildcards.mask(it.mnemonic, it.opStr, bytesOf(it.bytes))
                }

        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `strict masks match the original implementation`() {
        val wrong =
            rows
                .filter {
                    Wildcards.maskStrict(it.mnemonic, it.opStr, bytesOf(it.bytes)) != it.maskStrict
                }
                .take(5)
                .map {
                    "${it.mnemonic} ${it.opStr}: expected ${it.maskStrict}, got " +
                        Wildcards.maskStrict(it.mnemonic, it.opStr, bytesOf(it.bytes))
                }

        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `the documented examples hold`() {
        assertEquals("??????9?", Wildcards.mask("bl", "#0x1000", bytesOf("01000094")))
        assertEquals("??????1?", Wildcards.mask("b", "#0x1000", bytesOf("01000014")))
        // The original's header calls ADRP "fully volatile", but its own bit ranges leave two
        // nibbles literal, and the oracle agrees: immhi and immlo do not reach bits 3..0 or 27..24.
        assertEquals("?8?????0", Wildcards.mask("adrp", "x0, #0x1000", bytesOf("08000090")))
        assertEquals("1f2003d5", Wildcards.mask("nop", "", bytesOf("1f2003d5")))
    }
}
