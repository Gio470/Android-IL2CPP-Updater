package org.updater.modern

/**
 * Places ambiguous methods through the IL2CPP method pointer arrays in read-only data.
 *
 * IL2CPP emits large arrays of function pointers. If a method's address appears in one, its
 * immediate neighbours in that array can be located in the new library by their own bytes, and the
 * slot beside the relocated neighbour holds the method being looked for. Ported from
 * `src/binary/rodata.ts`.
 */
object Rodata {

    /** How many of the eight surrounding slots must look like code pointers. */
    private const val REQUIRED_NEIGHBOURS = 6

    fun resolve(
        source: ByteArray,
        destination: ByteArray,
        pending: List<PendingMethod>,
    ): Map<String, Int> {
        val sourceSegments = Elf.segments(source)
        val destSegments = Elf.segments(destination)
        val answers = mutableMapOf<String, Int>()

        val neighbourMatches = mutableMapOf<Int, Int?>()
        val destinationSlots = mutableMapOf<Long, List<Int>>()

        for (method in pending) {
            val va = Elf.fileToVa(sourceSegments, method.sourceOffset) ?: continue

            var done = false
            for (pointer in alignedOccurrences(source, va)) {
                if (done) break
                // An isolated pointer proves nothing; this has to be an array of them.
                if (!looksLikePointerArray(source, sourceSegments, pointer)) continue

                val before = Elf.readVa(source, pointer - 8) to -1
                val after = Elf.readVa(source, pointer + 8) to 1

                for ((neighbourVa, direction) in listOf(before, after)) {
                    if (done) break

                    val neighbourOffset =
                        Elf.vaToFile(sourceSegments, neighbourVa)?.takeIf { it in source.indices }
                            ?: continue

                    val relocated =
                        neighbourMatches.getOrPut(neighbourOffset) {
                            val end = minOf(neighbourOffset + 64, source.size)
                            val literal =
                                source.copyOfRange(neighbourOffset, end).joinToString(" ") {
                                    "%02x".format(it)
                                }
                            val matches = Scan.scan(literal, destination)
                            if (matches.size == 1) matches[0] else null
                        } ?: continue

                    val relocatedVa = Elf.fileToVa(destSegments, relocated) ?: continue
                    val slots =
                        destinationSlots.getOrPut(relocatedVa) {
                            alignedOccurrences(destination, relocatedVa)
                        }

                    for (slot in slots) {
                        if (!looksLikePointerArray(destination, destSegments, slot)) continue

                        val target = slot - direction * 8
                        if (target < 0 || target + 8 > destination.size) continue

                        val targetVa = Elf.readVa(destination, target)
                        val targetOffset = Elf.vaToFile(destSegments, targetVa) ?: continue

                        answers[method.name] = targetOffset
                        done = true
                        break
                    }
                }
            }
        }

        return answers
    }

    /** Every eight-byte-aligned position holding [va]. */
    private fun alignedOccurrences(data: ByteArray, va: Long): List<Int> {
        val found = mutableListOf<Int>()
        var position = 0
        val end = data.size - 8
        while (position <= end) {
            if (Elf.readVa(data, position) == va) found.add(position)
            position += 8
        }
        return found
    }

    private fun looksLikePointerArray(
        data: ByteArray,
        segments: List<Elf.Segment>,
        at: Int,
    ): Boolean {
        var plausible = 0
        for (j in -4..4) {
            if (j == 0) continue
            val slot = at + j * 8
            if (slot < 0 || slot >= data.size - 8) continue

            val offset = Elf.vaToFile(segments, Elf.readVa(data, slot))
            if (offset != null && offset > 0x10000 && offset < data.size) plausible++
        }
        return plausible >= REQUIRED_NEIGHBOURS
    }
}
