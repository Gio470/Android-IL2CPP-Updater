package org.updater.modern

/**
 * Direct AArch64 branches, resolved to file offsets.
 *
 * Only plain `B` and `BL` are decoded. Indirect branches and multi-hop detours are deliberately not
 * followed: a trampoline is only accepted when it can be proved, and a guess is worse than a miss.
 * Ported from `src/binary/branch.ts`.
 */
object Branch {

    private class Segment(val file: Long, val va: Long, val length: Long)

    /** Where a displacement from [instructionOffset] lands, as a file offset. */
    private fun fileTarget(data: ByteArray, instructionOffset: Int, displacement: Int): Int? {
        val isElf =
            data.size >= 64 &&
                data[0].toInt() == 0x7f &&
                data[1].toInt() == 0x45 &&
                data[2].toInt() == 0x4c &&
                data[3].toInt() == 0x46 &&
                data[4].toInt() == 2 &&
                data[5].toInt() == 1

        if (!isElf) {
            val target = instructionOffset + displacement
            return if (target >= 0 && target + 4 <= data.size) target else null
        }

        val table = readLong(data, 32)
        val entrySize = readShort(data, 54)
        val entryCount = readShort(data, 56)
        if (entrySize < 56 || table + entrySize.toLong() * entryCount > data.size) return null

        val segments = mutableListOf<Segment>()
        for (i in 0 until entryCount) {
            val entry = (table + i * entrySize).toInt()
            if (readInt(data, entry) != 1) continue // not PT_LOAD
            if (readInt(data, entry + 4) and 1 == 0) continue // not executable

            val file = readLong(data, entry + 8)
            val va = readLong(data, entry + 16)
            val length = readLong(data, entry + 32)
            if (file + length > data.size) return null
            segments.add(Segment(file, va, length))
        }

        val from =
            segments.firstOrNull {
                instructionOffset >= it.file && instructionOffset + 4 <= it.file + it.length
            } ?: return null

        val targetVa = from.va + instructionOffset - from.file + displacement
        val into =
            segments.firstOrNull { targetVa >= it.va && targetVa + 4 <= it.va + it.length }
                ?: return null

        return (into.file + targetVa - into.va).toInt()
    }

    /** The target of a direct unconditional branch, or null if this is not one. */
    fun branchTarget(data: ByteArray, offset: Int): Int? = target(data, offset, 0x14000000)

    /** The target of a direct call, or null if this is not one. */
    fun callTarget(data: ByteArray, offset: Int): Int? = target(data, offset, 0x94000000.toInt())

    private fun target(data: ByteArray, offset: Int, opcode: Int): Int? {
        if (offset < 0 || offset + 4 > data.size || offset % 4 != 0) return null

        val word = readInt(data, offset)
        if (word and 0xfc000000.toInt() != opcode) return null

        // imm26, sign-extended, counted in instructions.
        val displacement = (word shl 6 shr 6) * 4
        return fileTarget(data, offset, displacement)
    }

    internal fun readInt(data: ByteArray, at: Int): Int {
        var value = 0
        for (i in 3 downTo 0) value = (value shl 8) or (data[at + i].toInt() and 0xFF)
        return value
    }

    internal fun readLong(data: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
        return value
    }

    internal fun readShort(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
}
