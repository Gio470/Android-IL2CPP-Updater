package org.updater.utils

/**
 * A byte pattern where some positions are wildcards, as produced by [getPattern].
 *
 * Wildcards are held as [WILDCARD] rather than as a byte value, so a literal `ff` in a signature
 * stays a literal `ff` instead of being read back as "anything".
 */
class Signature private constructor(private val bytes: IntArray) {

    val size: Int
        get() = bytes.size

    /** True when the signature has no concrete bytes at all, and so carries no information. */
    val isBlank: Boolean
        get() = bytes.isEmpty() || bytes.all { it == WILDCARD }

    /** True when this signature begins with all of [prefix], wildcards included. */
    fun startsWith(prefix: Signature): Boolean =
        prefix.size <= size && (0 until prefix.size).all { bytes[it] == prefix.bytes[it] }

    fun matchesAt(buf: ByteArray, position: Int): Boolean {
        if (position < 0 || position + bytes.size > buf.size) return false

        for (i in bytes.indices) {
            val expected = bytes[i]
            if (expected != WILDCARD && expected != (buf[position + i].toInt() and 0xFF)) {
                return false
            }
        }

        return true
    }

    /**
     * Every position in [buf] where this signature matches.
     *
     * A blank signature matches everywhere, which is never a useful answer, so it returns nothing
     * instead. Positions are tested against the first concrete byte before the full compare, which
     * is what keeps the scan close to one comparison per position.
     */
    fun findAll(buf: ByteArray): List<Int> {
        if (isBlank) return emptyList()

        val anchor = bytes.indexOfFirst { it != WILDCARD }
        val anchorByte = bytes[anchor].toByte()
        val matches = mutableListOf<Int>()
        val last = buf.size - bytes.size

        for (position in 0..last) {
            if (buf[position + anchor] == anchorByte && matchesAt(buf, position)) {
                matches.add(position)
            }
        }

        return matches
    }

    companion object {
        const val WILDCARD = -1

        fun parse(pattern: String): Signature {
            val tokens = pattern.trim().split(" ").filter { it.isNotEmpty() }
            val bytes =
                IntArray(tokens.size) { i ->
                    val token = tokens[i]
                    if (token == "??") WILDCARD
                    else
                        token.toIntOrNull(16)
                            ?: throw IllegalArgumentException("Not a signature byte: $token")
                }

            return Signature(bytes)
        }
    }
}
