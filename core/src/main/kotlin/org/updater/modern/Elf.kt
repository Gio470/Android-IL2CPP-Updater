package org.updater.modern

/**
 * The loadable segments of an ELF file, and translation between file offsets and virtual addresses.
 *
 * Pointers stored in a library are virtual addresses, while everything the matcher works with is a
 * file offset, so the fallbacks need both. Ported from the segment half of `src/binary/vtable.ts`.
 */
object Elf {

    /** [flags] carries PF_X = 1, PF_W = 2, PF_R = 4. */
    data class Segment(val file: Int, val va: Long, val fileSize: Int, val flags: Int) {
        val executable: Boolean
            get() = flags and 1 != 0
    }

    /** The PT_LOAD segments, or nothing if this is not a 64-bit little-endian ELF. */
    fun segments(data: ByteArray): List<Segment> {
        if (
            data.size < 64 ||
                data[0].toInt() != 0x7f ||
                data[1].toInt() != 0x45 ||
                data[2].toInt() != 0x4c ||
                data[3].toInt() != 0x46 ||
                data[4].toInt() != 2 ||
                data[5].toInt() != 1
        ) {
            return emptyList()
        }

        val headerOffset = Branch.readLong(data, 32).toInt()
        val entrySize = Branch.readShort(data, 54)
        val entryCount = Branch.readShort(data, 56)

        val segments = mutableListOf<Segment>()
        for (i in 0 until entryCount) {
            val entry = headerOffset + i * entrySize
            if (entry + entrySize > data.size) break
            if (Branch.readInt(data, entry) != 1) continue // PT_LOAD only

            segments.add(
                Segment(
                    flags = Branch.readInt(data, entry + 4),
                    file = Branch.readLong(data, entry + 8).toInt(),
                    va = Branch.readLong(data, entry + 16),
                    fileSize = Branch.readLong(data, entry + 32).toInt(),
                )
            )
        }

        return segments
    }

    fun fileToVa(segments: List<Segment>, offset: Int): Long? {
        for (segment in segments) {
            if (offset >= segment.file && offset < segment.file + segment.fileSize) {
                return segment.va + (offset - segment.file)
            }
        }
        return null
    }

    fun vaToFile(segments: List<Segment>, va: Long): Int? {
        for (segment in segments) {
            if (va >= segment.va && va < segment.va + segment.fileSize) {
                return segment.file + (va - segment.va).toInt()
            }
        }
        return null
    }

    /** Reads a little-endian 64-bit pointer. */
    fun readVa(data: ByteArray, at: Int): Long = Branch.readLong(data, at)
}
