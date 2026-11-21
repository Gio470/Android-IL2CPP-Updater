package org.updater.modern

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.updater.createArm64Disassembler

/**
 * Checks resolution against the TypeScript updater over its own test fixture.
 *
 * The fixture is a pair of small AArch64 libraries whose eight functions were written to exercise
 * every wildcard rule: ADRP+LDR chains, 48-bit address builds, bit tests, six condition codes, a
 * store of the zero register, and a near-duplicate that exists to make uniqueness hard. Callee
 * stubs and page targets differ between the two, so every volatile nibble really does move.
 */
class ResolveOracleTest {

    private data class Case(
        val name: String,
        val offset: Int,
        val expectedNew: Int,
        val pattern: String,
        val sourcePattern: String,
        val length: Int,
        val matches: List<Int>,
        val sourceMatches: List<Int>,
        val strategy: String,
    )

    private fun resource(name: String) =
        javaClass.getResourceAsStream(name)?.readBytes() ?: error("$name is missing")

    private val old: ByteArray by lazy { resource("/fixture-old.so") }
    private val new: ByteArray by lazy { resource("/fixture-new.so") }

    private val cases: List<Case> by lazy {
        Gson()
            .fromJson(
                resource("/resolve-oracle.json").toString(Charsets.UTF_8),
                Array<Case>::class.java,
            )
            .toList()
    }

    @Test
    fun `resolution matches the original, case for case`() {
        val cs = createArm64Disassembler()
        try {
            val differences =
                cases
                    .mapNotNull { expected ->
                        val actual = Resolve.resolve(cs, old, new, expected.offset, maxLength = 300)
                        when {
                            actual.pattern != expected.pattern ->
                                "${expected.name}: pattern differs"
                            actual.length != expected.length ->
                                "${expected.name}: length ${actual.length} != ${expected.length}"
                            actual.matches != expected.matches ->
                                "${expected.name}: matches ${actual.matches} != ${expected.matches}"
                            actual.sourceMatches != expected.sourceMatches ->
                                "${expected.name}: source matches differ"
                            actual.strategy.name.lowercase().replace('_', '-') !=
                                expected.strategy ->
                                "${expected.name}: strategy ${actual.strategy} != ${expected.strategy}"
                            else -> null
                        }
                    }
                    .take(5)

            assertEquals(emptyList(), differences)
        } finally {
            cs.close()
        }
    }

    @Test
    fun `every function is found where the fixture says it moved to`() {
        val cs = createArm64Disassembler()
        try {
            val wrong =
                cases
                    .mapNotNull { case ->
                        val actual = Resolve.resolve(cs, old, new, case.offset, maxLength = 300)
                        if (actual.matches == listOf(case.expectedNew)) null
                        else
                            "${case.name}: expected 0x${case.expectedNew.toString(16)}, got ${actual.matches}"
                    }
                    .take(8)

            assertEquals(emptyList(), wrong)
        } finally {
            cs.close()
        }
    }

    @Test
    fun `the fixture covers the archetypes it claims to`() {
        assertEquals(8, cases.size)
        assertTrue(cases.any { it.name == "NearDuplicate" }, "the hard uniqueness case is present")
        assertTrue(cases.all { it.length > 0 }, "every case produced a signature")
    }
}
