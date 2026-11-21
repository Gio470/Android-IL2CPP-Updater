package org.updater.modern

/**
 * Matching for nibble-masked patterns.
 *
 * A pattern is a byte-spaced hex string where either nibble of a byte may be '?', so `f9` matches
 * exactly, `?9` matches any byte whose low nibble is 9, and `??` matches anything. Ported from
 * `src/patterns/scan.ts`.
 */
object Scan {

    /** A pattern compiled to a value and a mask per byte. */
    class Compiled(val values: ByteArray, val masks: ByteArray) {
        val size: Int
            get() = values.size

        fun matchesAt(data: ByteArray, start: Int, from: Int = 0): Boolean =
            matchesRange(data, start, from, size)

        /** Whether bytes [from] until [until] of the pattern match, the rest being ignored. */
        fun matchesRange(data: ByteArray, start: Int, from: Int, until: Int): Boolean {
            if (start < 0 || start + size > data.size) return false
            for (i in from until minOf(until, size)) {
                if ((data[start + i].toInt() and masks[i].toInt()) != values[i].toInt()) {
                    return false
                }
            }
            return true
        }

        companion object {
            fun parse(pattern: String): Compiled {
                val text = pattern.filterNot { it.isWhitespace() }
                require(text.length % 2 == 0) { "A pattern must be whole bytes: $pattern" }

                val count = text.length / 2
                val values = ByteArray(count)
                val masks = ByteArray(count)

                for (i in 0 until count) {
                    val high = text[i * 2]
                    val low = text[i * 2 + 1]
                    var value = 0
                    var mask = 0
                    if (high != '?') {
                        value = value or (Character.digit(high, 16) shl 4)
                        mask = mask or 0xF0
                    }
                    if (low != '?') {
                        value = value or Character.digit(low, 16)
                        mask = mask or 0x0F
                    }
                    values[i] = value.toByte()
                    masks[i] = mask.toByte()
                }

                return Compiled(values, masks)
            }
        }
    }

    /**
     * The executable part of an ELF file, if this is one.
     *
     * Signatures describe code, so matches in data sections are noise. The original restricts its
     * scan this way and widens to the whole file only when nothing was found.
     */
    fun elfCodeBounds(data: ByteArray): IntRange? {
        if (data.size < 64) return null
        if (
            data[0].toInt() != 0x7f ||
                data[1].toInt() != 0x45 ||
                data[2].toInt() != 0x4c ||
                data[3].toInt() != 0x46 ||
                data[4].toInt() != 2 // 64-bit
        ) {
            return null
        }

        val sectionHeaderOffset = readLong(data, 40).toInt()
        val entrySize = readShort(data, 58)
        val entryCount = readShort(data, 60)
        if (sectionHeaderOffset == 0 || entryCount == 0 || entrySize < 64) return null

        var lowest = Int.MAX_VALUE
        var highest = 0
        for (i in 0 until entryCount) {
            val entry = sectionHeaderOffset + i * entrySize
            if (entry + 64 > data.size) break

            val flags = readLong(data, entry + 8)
            if (flags and 0x4L == 0L) continue // not SHF_EXECINSTR

            val offset = readLong(data, entry + 24).toInt()
            val size = readLong(data, entry + 32).toInt()
            if (offset < lowest) lowest = offset
            if (offset + size > highest) highest = offset + size
        }

        if (lowest == Int.MAX_VALUE || lowest >= highest) return null
        return lowest until minOf(highest, data.size)
    }

    private fun readLong(data: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
        return value
    }

    private fun readShort(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)

    /** Every position in [data] where [pattern] matches. */
    fun scan(pattern: String, data: ByteArray, restrictToCode: Boolean = true): List<Int> {
        val compiled = Compiled.parse(pattern)
        if (compiled.size == 0) return emptyList()

        if (restrictToCode) {
            val bounds = elfCodeBounds(data)
            if (bounds != null) {
                val inCode = search(compiled, data, bounds.first, bounds.last + 1)
                // Nothing in the code sections: the signature may describe something elsewhere.
                if (inCode.isNotEmpty()) return inCode
            }
        }

        return search(compiled, data, 0, data.size)
    }

    private fun search(compiled: Compiled, data: ByteArray, from: Int, until: Int): List<Int> {
        val size = compiled.size
        val last = minOf(until, data.size) - size
        if (last < from) return emptyList()

        // Anchor on the longest run of fully known bytes, so most positions are rejected by a
        // single comparison rather than by walking the whole pattern.
        var anchorStart = -1
        var anchorLength = 0
        var runStart = -1
        var runLength = 0
        for (i in 0 until size) {
            if (compiled.masks[i] == 0xFF.toByte()) {
                if (runStart == -1) runStart = i
                runLength++
                if (runLength > anchorLength) {
                    anchorLength = runLength
                    anchorStart = runStart
                }
            } else {
                runStart = -1
                runLength = 0
            }
        }

        val matches = mutableListOf<Int>()
        if (anchorLength == 0) {
            for (start in from..last) {
                if (compiled.matchesAt(data, start)) matches.add(start)
            }
            return matches
        }

        val anchorByte = compiled.values[anchorStart]
        for (start in from..last) {
            if (data[start + anchorStart] != anchorByte) continue
            var ok = true
            for (i in 1 until anchorLength) {
                if (data[start + anchorStart + i] != compiled.values[anchorStart + i]) {
                    ok = false
                    break
                }
            }
            if (ok && compiled.matchesAt(data, start)) matches.add(start)
        }

        return matches
    }

    /**
     * Narrows [offsets] to those that still match once the pattern is checked from byte
     * [checkStart] on. Growing a signature only needs the new tail verified, never another pass
     * over the library.
     */
    fun filterMatches(
        data: ByteArray,
        offsets: List<Int>,
        pattern: String,
        checkStart: Int,
    ): List<Int> {
        if (offsets.isEmpty()) return offsets
        val compiled = Compiled.parse(pattern)
        if (compiled.size == 0 || checkStart >= compiled.size) return offsets

        return offsets.filter { compiled.matchesAt(data, it, checkStart) }
    }

    /**
     * Matches for many patterns.
     *
     * The original hands this to a NEON kernel and returns nothing when that is unavailable, so the
     * caller falls back to scanning one at a time. This does the same work without the kernel.
     */
    fun batchScan(patterns: List<String>, data: ByteArray): List<List<Int>> =
        patterns.map { scan(it, data) }
}
