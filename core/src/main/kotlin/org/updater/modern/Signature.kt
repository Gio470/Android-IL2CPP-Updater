package org.updater.modern

import capstone.api.Disassembler

/**
 * Builds nibble-masked signatures, growing them only as far as uniqueness demands.
 *
 * Ported from `src/patterns/signature.ts`. Signatures start at 48 bytes, which is enough for most
 * methods, and grow by decoding further chunks and narrowing the matches already found rather than
 * scanning the library again.
 */
object Signature {

    /** The result of growing a signature until it is unique, or until it cannot grow further. */
    data class Grown(val pattern: String, val length: Int, val matches: List<Int>)

    private const val FIRST_LENGTH = 48
    private const val CHUNK = 48
    private const val STEP = 12

    /** The masked pattern for [length] bytes of [buffer] at [offset]. */
    fun patternAt(
        disassembler: Disassembler,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        strict: Boolean = false,
    ): String = partsAt(disassembler, buffer, offset, length, strict).joinToString(" ")

    private fun partsAt(
        disassembler: Disassembler,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        strict: Boolean,
    ): List<String> {
        val end = minOf(offset + length, buffer.size)
        if (offset < 0 || offset >= buffer.size || end <= offset) return emptyList()

        val code = buffer.copyOfRange(offset, end)
        val parts = mutableListOf<String>()

        for (instruction in disassembler.disasm(code, offset.toLong())) {
            val mask =
                if (strict) {
                    Wildcards.maskStrict(instruction.mnemonic, instruction.opStr, instruction.bytes)
                } else {
                    Wildcards.mask(instruction.mnemonic, instruction.opStr, instruction.bytes)
                }
            // Each instruction yields eight nibbles; the pattern is spaced by byte.
            for (i in mask.indices step 2) parts.add(mask.substring(i, i + 2))
        }

        return parts
    }

    /**
     * Grows the signature at [offset] in [source] until it matches [target] in exactly one place,
     * which for a self-check is [offset] itself.
     */
    fun unique(
        disassembler: Disassembler,
        source: ByteArray,
        target: ByteArray,
        offset: Int,
        maxLength: Int,
    ): Grown {
        val available = minOf(maxLength, source.size - offset)
        val firstLength = minOf(FIRST_LENGTH, (available / 4) * 4)
        if (firstLength <= 0) return Grown("", 0, emptyList())

        val parts =
            partsAt(disassembler, source, offset, firstLength, strict = false).toMutableList()
        if (parts.isEmpty()) return Grown("", 0, emptyList())

        var length = parts.size
        var pattern = parts.joinToString(" ")
        var matches = Scan.scan(pattern, target)

        // Nothing more to try: already unique, decoding stopped short, or the window is spent.
        if (isUnique(matches, offset) || length < firstLength || length >= available) {
            return Grown(pattern, length, matches)
        }

        while (length < available && !isUnique(matches, offset)) {
            val chunkLength = minOf(CHUNK, available - length)
            val chunk = partsAt(disassembler, source, offset + length, chunkLength, strict = false)
            if (chunk.isEmpty()) break

            val before = parts.size
            parts.addAll(chunk)

            val limit = minOf(parts.size, (available / 4) * 4)
            while (length < limit && !isUnique(matches, offset)) {
                val next = minOf(length + STEP, limit)
                val grown = parts.subList(0, next).joinToString(" ")
                matches = Scan.filterMatches(target, matches, grown, length)
                length = next
            }

            if (parts.size == before) break
        }

        pattern = parts.subList(0, length).joinToString(" ")
        return Grown(pattern, length, matches)
    }

    private fun isUnique(matches: List<Int>, offset: Int) =
        matches.size == 1 && matches[0] == offset
}
