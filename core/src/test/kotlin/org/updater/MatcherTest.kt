package org.updater

import capstone.api.Disassembler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.updater.utils.Signature
import org.updater.utils.getPattern

class MatcherTest {
    private val nop = 0xd503201fL.toInt()
    private val ret = 0xd65f03c0L.toInt()
    private val movX0X1 = 0xaa0103e0L.toInt()
    private val movX1X2 = 0xaa0203e1L.toInt()
    private val movX8X0 = 0xaa0003e8L.toInt()
    private val subSp = 0xd10043ffL.toInt()

    /** Sixteen instructions, 64 bytes, none of them the kind the builder wildcards. */
    private val block =
        words(
            subSp,
            movX8X0,
            nop,
            movX1X2,
            movX0X1,
            ret,
            nop,
            movX8X0,
            ret,
            nop,
            nop,
            movX0X1,
            movX1X2,
            ret,
            nop,
            subSp,
        )

    private val tailA =
        words(
            movX1X2,
            ret,
            nop,
            nop,
            movX8X0,
            subSp,
            ret,
            movX0X1,
            nop,
            ret,
            movX1X2,
            nop,
            subSp,
            movX8X0,
            nop,
            ret,
        )

    private val tailB =
        words(
            nop,
            nop,
            subSp,
            movX0X1,
            ret,
            movX1X2,
            nop,
            movX8X0,
            ret,
            subSp,
            nop,
            ret,
            movX0X1,
            nop,
            movX8X0,
            movX1X2,
        )

    private val pad = ByteArray(64)

    private fun words(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 4)
        values.forEachIndexed { i, word ->
            out[i * 4] = (word and 0xFF).toByte()
            out[i * 4 + 1] = ((word shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((word shr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((word shr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach { part ->
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    // The same backend the tool itself opens. Going through the bundled factory instead would
    // pull a second Capstone build into the JVM, and two of them in one process crash it.
    private fun <T> withDisassembler(block: (Disassembler) -> T): T {
        val cs = createArm64Disassembler()
        try {
            return block(cs)
        } finally {
            cs.close()
        }
    }

    private fun settings(sigLen: Int = 64, maxSigLen: Int = 1024, autoTune: Boolean = true) =
        MatchSettings(sigLen = sigLen, maxSigLen = maxSigLen, autoTune = autoTune)

    // The block appears twice in the new library, with a different tail each time, so 64 bytes is
    // ambiguous and 128 is not.
    private val old = concat(pad, block, tailA, pad)
    private val new = concat(pad, pad, block, tailA, pad, block, tailB, pad)
    private val blockInOld = 64

    @Test
    fun `widens an ambiguous signature until the match is unique`() {
        val result = withDisassembler { resolveOffset(it, old, new, blockInOld, settings()) }

        assertEquals(MatchStatus.RESOLVED, result.status)
        assertEquals(listOf("0x80"), result.foundOffsets)
        assertEquals(128, result.sigLen)
        assertTrue(result.tuned)
    }

    @Test
    fun `leaves the signature alone when tuning is off`() {
        val result = withDisassembler {
            resolveOffset(it, old, new, blockInOld, settings(autoTune = false))
        }

        assertEquals(MatchStatus.AMBIGUOUS, result.status)
        assertEquals(2, result.foundOffsets.size)
        assertEquals(64, result.sigLen)
        assertTrue(!result.tuned)
    }

    @Test
    fun `stops widening at the ceiling`() {
        val result = withDisassembler {
            resolveOffset(it, old, new, blockInOld, settings(maxSigLen = 64))
        }

        assertEquals(MatchStatus.AMBIGUOUS, result.status)
        assertEquals(64, result.sigLen)
    }

    @Test
    fun `narrows the signature when the longer one matches nowhere`() {
        // Only the first half of the block survived into the new library.
        val half = block.copyOfRange(0, 32)
        val shortened = concat(pad, half, tailB, pad)

        val result = withDisassembler { resolveOffset(it, old, shortened, blockInOld, settings()) }

        assertEquals(MatchStatus.RESOLVED, result.status)
        assertEquals(listOf("0x40"), result.foundOffsets)
        assertEquals(32, result.sigLen)
        assertTrue(result.tuned)
    }

    // Doubling goes 64, 128, 256. When 256 runs past the end of what survived, the length that
    // settles it can still be 192, and giving up at 128 would report a false ambiguity.
    @Test
    fun `finds a length between the one that is too short and the one that is too long`() {
        val old = concat(pad, block, tailA, tailB, tailA, pad)
        // Unique at 192 bytes: the decoy diverges after 128, and nothing matches 256.
        val new =
            concat(
                pad,
                block,
                tailA,
                tailB,
                tailB, // where the old library has tailA, so 256 bytes match nowhere
                pad,
                block,
                tailA,
                tailA, // decoy: same first 128 bytes, different third block
                pad,
            )

        val result = withDisassembler { resolveOffset(it, old, new, 64, settings()) }

        assertEquals(MatchStatus.RESOLVED, result.status)
        assertEquals(192, result.sigLen)
        assertEquals(listOf("0x40"), result.foundOffsets)
    }

    // Narrowing halves, 64 -> 32 -> 16, and the length that singles the function out can sit
    // between two of those. Taking the first length that matches anything reports a prologue
    // shared by dozens of functions instead.
    @Test
    fun `narrowing looks between the halves rather than settling for the first match`() {
        val head = block.copyOfRange(0, 16) // four instructions, a common-looking prologue
        val six = block.copyOfRange(0, 24) // six instructions, distinctive
        val old = concat(pad, block, tailA, pad)
        val new =
            concat(
                pad,
                six,
                tailB, // the only place the first six instructions survive
                pad,
                head,
                tailA, // decoys: the prologue alone, three times
                pad,
                head,
                tailB,
                pad,
                head,
                tailA,
                tailB,
                pad,
            )

        val result = withDisassembler { resolveOffset(it, old, new, 64, settings()) }

        assertEquals(MatchStatus.RESOLVED, result.status)
        assertEquals(24, result.sigLen)
        assertEquals(listOf("0x40"), result.foundOffsets)
    }

    // A prologue shared by dozens of functions is not a list of candidates.
    @Test
    fun `a fragment matching many places is reported as not found, not as candidates`() {
        val head = block.copyOfRange(0, 16)
        val old = concat(pad, block, tailA, pad)
        val new =
            concat(pad, head, tailB, pad, head, tailA, pad, head, tailB, tailA, pad, head, pad)

        val result = withDisassembler { resolveOffset(it, old, new, 64, settings()) }

        assertEquals(MatchStatus.NOT_FOUND, result.status)
        assertEquals(emptyList(), result.foundOffsets)
        assertTrue(result.note!!.contains("nothing matched at 64 bytes"))
        assertTrue(result.note!!.contains("identify nothing"))
        assertTrue(describeResult(result).startsWith("not found ("))
    }

    @Test
    fun `reports an offset past the end of the old library`() {
        val result = withDisassembler { resolveOffset(it, old, new, old.size + 4, settings()) }

        assertEquals(MatchStatus.ERROR, result.status)
        assertTrue(result.error!!.contains("past the end"))
    }

    // Widening filters the matches already found instead of scanning again, which is only sound
    // while a longer signature begins with the shorter one. Lock that property down.
    @Test
    fun `a longer signature starts with the shorter one at the same offset`() {
        withDisassembler { cs ->
            val short = Signature.parse(getPattern(cs, old, blockInOld, 64))
            val long = Signature.parse(getPattern(cs, old, blockInOld, 128))
            assertTrue(long.size > short.size)
            assertTrue(long.startsWith(short))
        }
    }

    @Test
    fun `a signature longer than the library is clamped rather than refused`() {
        withDisassembler { cs ->
            val pattern = getPattern(cs, old, old.size - 8, 512)
            assertTrue(Signature.parse(pattern).size <= 8)
        }
    }

    @Test
    fun `reports not found when no signature length matches`() {
        val unrelated = concat(pad, tailB, pad)

        val result = withDisassembler { resolveOffset(it, old, unrelated, blockInOld, settings()) }

        assertEquals(MatchStatus.NOT_FOUND, result.status)
    }
}
