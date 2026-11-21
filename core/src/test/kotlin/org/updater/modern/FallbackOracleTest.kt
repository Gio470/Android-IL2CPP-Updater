package org.updater.modern

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the fallback ladder against the TypeScript updater.
 *
 * The rodata and vtable cases run on a pair of small ELF files built for the purpose: eight methods
 * that all slide by 0x80 between versions, one of them duplicated in the new library so its own
 * bytes cannot settle it, and a pointer array in read-only data mirroring an IL2CPP method table.
 * The xref cases are the original's own unit tests, ported as they stand.
 */
class FallbackOracleTest {

    private data class PendingJson(
        val name: String,
        val sourceOffset: Int,
        val candidates: List<Int>,
        val sourceBodyLength: Int?,
    )

    private data class ResolvedJson(
        val name: String?,
        val sourceOffset: Int,
        val destOffset: Int,
        val sourceBodyLength: Int,
    )

    private data class Oracle(
        val oldOffsets: List<Int>,
        val newOffsets: List<Int>,
        val pending: List<PendingJson>,
        val resolved: List<ResolvedJson>,
        val rodata: Map<String, Int>,
        val vtable: Map<String, Int>,
    )

    private fun resource(name: String) =
        javaClass.getResourceAsStream(name)?.readBytes() ?: error("$name is missing")

    private val old: ByteArray by lazy { resource("/fallback-old.so") }
    private val new: ByteArray by lazy { resource("/fallback-new.so") }
    private val oracle: Oracle by lazy {
        Gson()
            .fromJson(
                resource("/fallback-oracle.json").toString(Charsets.UTF_8),
                Oracle::class.java,
            )
    }

    private val pending: List<PendingMethod> by lazy {
        oracle.pending.map {
            PendingMethod(it.name, it.sourceOffset, it.candidates, it.sourceBodyLength)
        }
    }

    private val resolved: List<ResolvedMethod> by lazy {
        oracle.resolved.map {
            ResolvedMethod(it.name, it.sourceOffset, it.destOffset, it.sourceBodyLength)
        }
    }

    @Test
    fun `the ELF layer sees the same segments`() {
        val segments = Elf.segments(old)
        assertEquals(2, segments.size)
        assertTrue(segments[0].executable, "the first segment should be the code one")
        assertTrue(!segments[1].executable, "the second segment should be read-only data")
        // A file offset in the code segment maps to a virtual address and back.
        val offset = oracle.oldOffsets.first()
        val va = Elf.fileToVa(segments, offset)
        assertEquals(offset, va?.let { Elf.vaToFile(segments, it) })
    }

    @Test
    fun `rodata triangulation agrees with the original`() {
        assertEquals(oracle.rodata, Rodata.resolve(old, new, pending))
    }

    @Test
    fun `vtable resolution agrees with the original`() {
        assertEquals(oracle.vtable, Vtable.resolve(old, new, pending, resolved))
    }

    @Test
    fun `the fallbacks actually placed the methods where they moved to`() {
        // Not just "the same as the original" but "the right answer".
        assertEquals(oracle.newOffsets[3], oracle.rodata["Ambiguous"])
        assertEquals(oracle.newOffsets[5], oracle.rodata["Unfound"])
    }

    // --- the original's own xref unit tests -------------------------------------------------

    private fun writeCall(bytes: ByteArray, offset: Int, target: Int) {
        val displacement = (target - offset) / 4
        val word = 0x94000000.toInt() or (displacement and 0x03ffffff)
        for (i in 0 until 4) bytes[offset + i] = ((word shr (i * 8)) and 0xFF).toByte()
    }

    @Test
    fun `outgoing calls pick the candidate that calls the known destination`() {
        val source = ByteArray(1024)
        val destination = ByteArray(1024)
        writeCall(source, 100, 300)
        writeCall(destination, 500, 800)
        writeCall(destination, 600, 900)

        val result =
            Xref.resolve(
                source,
                destination,
                listOf(PendingMethod("pending", 100, listOf(500, 600), 32)),
                listOf(ResolvedMethod(null, 300, 800, 32)),
            )

        assertEquals(500, result["pending"])
    }

    @Test
    fun `outgoing calls leave ambiguity alone when candidates agree`() {
        val source = ByteArray(1024)
        val destination = ByteArray(1024)
        writeCall(source, 100, 300)
        writeCall(destination, 500, 800)
        writeCall(destination, 600, 800)

        val result =
            Xref.resolve(
                source,
                destination,
                listOf(PendingMethod("pending", 100, listOf(500, 600), 32)),
                listOf(ResolvedMethod(null, 300, 800, 32)),
            )

        assertTrue(!result.containsKey("pending"), "an undecidable case must stay unresolved")
    }
}
