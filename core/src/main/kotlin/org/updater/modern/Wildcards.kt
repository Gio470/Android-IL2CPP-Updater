package org.updater.modern

/**
 * Nibble-level wildcard masks for AArch64 instructions.
 *
 * The classic engine wildcards a whole instruction or keeps it entire. This keeps the parts of an
 * encoding that identify the instruction — opcode, condition, register, operand size — and masks
 * only the bits that genuinely move between builds, such as branch displacements and page offsets.
 * A call keeps its opcode nibble instead of vanishing into four wildcard bytes.
 *
 * A mask is one character per nibble: either '?' or the literal hex digit. Ported from
 * `src/patterns/wildcards.ts` of the TypeScript updater; [WildcardsTest] checks it against masks
 * dumped from that implementation.
 */
object Wildcards {

    /** [high, low] inclusive, bit 31 being the top of the 32-bit word. */
    private data class BitRange(val high: Int, val low: Int)

    /**
     * Nibble index to the bits it covers. AArch64 words are little-endian, so nibble 0 is the high
     * half of byte 0, which holds bits 7..4.
     */
    private val NIBBLE_BIT_RANGES =
        listOf(
            BitRange(7, 4),
            BitRange(3, 0),
            BitRange(15, 12),
            BitRange(11, 8),
            BitRange(23, 20),
            BitRange(19, 16),
            BitRange(31, 28),
            BitRange(27, 24),
        )

    private const val HEX = "0123456789abcdef"

    private class Rule(val volatileBits: List<BitRange>, val matches: (String, String) -> Boolean) {
        val mask: Int = maskFor(volatileBits)
    }

    private fun maskFor(volatileBits: List<BitRange>): Int {
        var mask = 0
        NIBBLE_BIT_RANGES.forEachIndexed { index, nibble ->
            val touched = volatileBits.any { nibble.high >= it.low && nibble.low <= it.high }
            if (touched) mask = mask or (1 shl index)
        }
        return mask
    }

    private val LARGE_IMMEDIATE = Regex("#(0x[0-9a-f]+)", RegexOption.IGNORE_CASE)
    private val NEGATIVE_OFFSET = Regex("#-0x[0-9a-f]+", RegexOption.IGNORE_CASE)
    private val CONDITIONAL_BRANCH = Regex("^b\\.[a-z]+$")
    private val LOAD_OR_STORE = Regex("^(?:ldr(?:b|h|sw)?|str(?:b|h)?)$")

    /** Immediates below 0x100 read as struct or stack offsets, which are usually stable. */
    private fun hasLargeHexImmediate(operand: String): Boolean {
        val digits = LARGE_IMMEDIATE.find(operand)?.groupValues?.get(1) ?: return false
        val value = digits.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return true
        return value >= 0x100
    }

    /** Frame-pointer-relative locals, which are stable. */
    private fun hasNegativeOffset(operand: String) = NEGATIVE_OFFSET.containsMatchIn(operand)

    private fun isRegisterOffset(operand: String) =
        operand.contains('[') && operand.contains(',') && !hasNegativeOffset(operand)

    // First rule that matches wins.
    private val RULES =
        listOf(
            // B / BL: the whole 26-bit displacement moves, the opcode nibble does not.
            Rule(listOf(BitRange(25, 0))) { mnemonic, _ -> mnemonic == "b" || mnemonic == "bl" },
            // ADRP / ADR: immhi and immlo between them cover nearly the whole word.
            Rule(listOf(BitRange(30, 29), BitRange(23, 5))) { mnemonic, _ ->
                mnemonic == "adrp" || mnemonic == "adr"
            },
            // B.cond: bits 31..24 are always 0x54 and the condition sits in bits 3..0.
            Rule(listOf(BitRange(23, 5))) { mnemonic, _ -> CONDITIONAL_BRANCH.matches(mnemonic) },
            // CBZ / CBNZ: opcode and operand size stay, the 19-bit displacement goes.
            Rule(listOf(BitRange(23, 5))) { mnemonic, _ ->
                mnemonic == "cbz" || mnemonic == "cbnz"
            },
            // TBZ / TBNZ: the bit being tested is part of the logic, so only imm14 goes.
            Rule(listOf(BitRange(18, 5))) { mnemonic, _ ->
                mnemonic == "tbz" || mnemonic == "tbnz"
            },
            // Literal loads, which are PC-relative and have no base register.
            Rule(listOf(BitRange(23, 5))) { mnemonic, operand ->
                mnemonic in LITERAL_LOADS && !operand.contains('[')
            },
            // Register plus offset loads: imm12 moves, opcode and registers stay.
            Rule(listOf(BitRange(21, 10))) { mnemonic, operand ->
                mnemonic in LITERAL_LOADS && isRegisterOffset(operand)
            },
            // Stores, except of the zero register, which tend to be stable initialisation.
            Rule(listOf(BitRange(21, 10))) { mnemonic, operand ->
                mnemonic in STORES &&
                    !operand.startsWith("wzr") &&
                    !operand.startsWith("xzr") &&
                    isRegisterOffset(operand)
            },
            // ADD / SUB with a large immediate and no sp: stack setup is stable, this is not.
            Rule(listOf(BitRange(21, 10))) { mnemonic, operand ->
                (mnemonic == "add" || mnemonic == "sub") &&
                    !operand.contains("sp") &&
                    hasLargeHexImmediate(operand)
            },
            // MOVZ / MOVK / MOVN building an absolute address.
            Rule(listOf(BitRange(22, 5))) { mnemonic, operand ->
                mnemonic in MOVE_IMMEDIATES && operand.contains("#0x")
            },
        )

    private val LITERAL_LOADS = setOf("ldr", "ldrb", "ldrh", "ldrsw")
    private val STORES = setOf("str", "strb", "strh")
    private val MOVE_IMMEDIATES = setOf("movz", "movk", "movn")

    /** The portable mask: register-relative offsets are treated as volatile. */
    fun mask(mnemonic: String, operand: String, bytes: ByteArray): String =
        mask(mnemonic, operand, bytes, strict = false)

    /**
     * A stricter mask that keeps small register-relative offsets.
     *
     * Fields move between builds, so the portable mask drops those offsets. When it leaves a
     * signature ambiguous they are worth keeping: small offsets are usually stable and make a much
     * stronger discriminator.
     */
    fun maskStrict(mnemonic: String, operand: String, bytes: ByteArray): String =
        mask(mnemonic, operand, bytes, strict = true)

    private fun mask(mnemonic: String, operand: String, bytes: ByteArray, strict: Boolean): String {
        val name = mnemonic.lowercase()

        for (rule in RULES) {
            if (!rule.matches(name, operand)) continue

            if (
                strict &&
                    isRegisterOffset(operand) &&
                    !hasLargeHexImmediate(operand) &&
                    LOAD_OR_STORE.matches(name)
            ) {
                return literal(bytes)
            }

            return maskedNibbles(bytes, rule.mask)
        }

        return literal(bytes)
    }

    private fun maskedNibbles(bytes: ByteArray, mask: Int): String {
        val out = StringBuilder(8)
        for (index in 0 until 8) {
            if (mask and (1 shl index) != 0) {
                out.append('?')
            } else {
                val byte = bytes[index / 2].toInt() and 0xFF
                out.append(HEX[if (index % 2 == 0) byte shr 4 else byte and 0xF])
            }
        }
        return out.toString()
    }

    private fun literal(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value shr 4]).append(HEX[value and 0xF])
        }
        return out.toString()
    }
}
