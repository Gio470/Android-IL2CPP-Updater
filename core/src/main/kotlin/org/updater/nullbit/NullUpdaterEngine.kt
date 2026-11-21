package org.updater.nullbit

import capstone.api.Disassembler
import org.updater.OffsetResult
import org.updater.createArm64Disassembler
import org.updater.modern.Scan

/**
 * NullUpdater, ported.
 *
 * Where the other two engines take a fixed or growing window of bytes, this one takes a whole
 * function: it disassembles forward from the offset to the first `ret` and signs everything up to
 * it. What that leaves is a signature whose length means something — a small function gets a small
 * one, a large function a large one — and which stops at the boundary rather than running into
 * whatever the linker happened to put next.
 *
 * Masking is per byte rather than per instruction or per nibble. A load, a store or an `add` keeps
 * its top byte, which on AArch64 is most of the opcode, and gives up the three that hold the
 * registers and the immediate; anything carrying an immediate of four hex digits or more is given
 * up whole; everything else stays concrete.
 *
 * When the signature matches too much or too little, the window is moved rather than the mask:
 * `mode` grows it past the end of the function in doubling steps, or pulls it back in 8-byte steps,
 * and a signature that oscillates between the two is accepted as it stands with every candidate
 * reported.
 *
 * Ported from `0NullBit0/NullUpdater` (`NullPattern.java`, `Utils.java`, and the loop in
 * `Main.java`).
 */
object NullUpdaterEngine {

    data class Settings(
        /** How many matches a signature may have and still be accepted. */
        val maxMatches: Int = 1
    )

    /** An immediate wide enough that the original gives up the whole instruction. */
    private val WIDE_IMMEDIATE = Regex("#0x[0-9a-fA-F]{4,}", RegexOption.IGNORE_CASE)

    /** Bytes disassembled at a time while looking for the end of a function. */
    private const val CHUNK = 4096

    /**
     * The original reads to the end of the file for every offset; this reads forward in chunks,
     * which finds the same `ret` without copying a 130 MB library per offset.
     */
    internal fun endOfFunction(disassembler: Disassembler, data: ByteArray, address: Int): Int {
        if (address < 0 || address >= data.size) return -1

        var at = address
        while (at < data.size) {
            val end = minOf(at + CHUNK, data.size)
            val window = data.copyOfRange(at, end)
            val instructions = disassembler.disasm(window, at.toLong())

            for (instruction in instructions) {
                if (instruction.mnemonic == "ret") return instruction.address.toInt() + 4
            }

            // Capstone stops at the first byte it cannot decode, and so does the original: a
            // function whose end is behind such a byte is never found.
            if (instructions.size * 4 < window.size) return -1
            at = end
        }

        return -1
    }

    /**
     * How far past the end of the function [mode] reaches.
     *
     * Positive modes double — 32 bytes, 64, 128 — so a signature that matches everywhere grows out
     * of it quickly. Negative modes back off 8 bytes at a time, because a signature that matches
     * nothing is usually only a little too long.
     */
    private fun addition(mode: Int): Int =
        when {
            mode == 0 -> 0
            mode > 0 -> 4 * (1 shl (mode + 2))
            else -> 8 * mode
        }

    /**
     * The signature for the function at [address], or null when [mode] has moved its end outside
     * the library or behind its start.
     */
    internal fun pattern(
        disassembler: Disassembler,
        data: ByteArray,
        address: Int,
        mode: Int,
    ): String? {
        val end = endOfFunction(disassembler, data, address) + addition(mode)
        if (end > data.size || end < address) return null

        val window = data.copyOfRange(address, end)
        val instructions = disassembler.disasm(window, address.toLong())
        if (instructions.isEmpty()) return null

        val bytes = StringBuilder()
        for (instruction in instructions) {
            val at = instruction.address.toInt()
            val mnemonic = instruction.mnemonic
            val operands = instruction.opStr

            // Kept in the original's order: the first rule that fits an instruction decides it.
            when {
                mnemonic == "ldr" || (mnemonic == "str" && hasTwoCommas(operands)) ->
                    bytes.append("?? ?? ?? ").append(hex(data[at + 3])).append(' ')
                mnemonic == "ldrb" || (mnemonic == "strb" && hasTwoCommas(operands)) ->
                    bytes.append("?? ?? ?? 39 ")
                mnemonic == "add" -> bytes.append("?? ?? ?? ").append(hex(data[at + 3])).append(' ')
                WIDE_IMMEDIATE.containsMatchIn(operands) -> bytes.append("?? ?? ?? ?? ")
                else ->
                    for (i in 0 until 4) {
                        bytes.append(hex(data[at + i])).append(' ')
                    }
            }
        }

        return bytes.toString().trim()
    }

    private fun hex(value: Byte): String = "%02X".format(value)

    private fun hasTwoCommas(operands: String): Boolean = operands.count { it == ',' } == 2

    fun run(
        source: ByteArray,
        destination: ByteArray,
        offsets: List<Int>,
        settings: Settings = Settings(),
        onResult: (done: Int, total: Int, result: OffsetResult) -> Unit = { _, _, _ -> },
        keepGoing: () -> Boolean = { true },
    ): List<OffsetResult> {
        val disassembler = createArm64Disassembler()
        val results = mutableListOf<OffsetResult>()

        try {
            for ((index, offset) in offsets.withIndex()) {
                if (!keepGoing()) break

                val result = resolve(disassembler, source, destination, offset, settings)
                results.add(result)
                onResult(index + 1, offsets.size, result)
            }
        } finally {
            disassembler.close()
        }

        return results
    }

    private fun resolve(
        disassembler: Disassembler,
        source: ByteArray,
        destination: ByteArray,
        offset: Int,
        settings: Settings,
    ): OffsetResult {
        val label = "0x${offset.toString(16)}"
        val max = maxOf(settings.maxMatches, 1)

        if (offset % 4 != 0 || offset < 0 || offset + 4 > source.size) {
            return OffsetResult(
                label,
                "",
                emptyList(),
                0,
                error = "offset must address an AArch64 instruction inside the library",
            )
        }

        var mode = 0
        // 1 when the last signature matched too much, 0 when it matched nothing, -1 at the start.
        var lastWas = -1
        var flips = 0
        var settle = false

        // The original has no bound here; it relies on the pattern running out of library. This
        // agrees with it in every case it settles, and gives up rather than spinning where it
        // would not.
        repeat(64) {
            val pattern =
                pattern(disassembler, source, offset, mode)
                    ?: return notFound(label, mode, "no signature could be taken")

            val matches = Scan.scan(pattern, destination, restrictToCode = false)
            val length = (pattern.count { it == ' ' } + 1)

            when {
                !settle && matches.size > max -> {
                    mode++
                    if (lastWas == 0) flips++
                    lastWas = 1
                }
                // Accepting anything from one match up to the ceiling. The original accepts only
                // exactly the ceiling, which never returns for a signature that settles below it.
                !settle && matches.isEmpty() -> {
                    mode--
                    if (lastWas == 1) flips++
                    lastWas = 0
                    if (flips > 3) settle = true
                }
                else ->
                    return OffsetResult(
                        offset = label,
                        pattern = pattern,
                        foundOffsets = matches.map { "0x${it.toString(16)}" },
                        sigLen = length,
                        tuned = mode != 0,
                        note = note(mode, settle),
                    )
            }
        }

        return notFound(label, mode, "its signature would not settle")
    }

    private fun notFound(label: String, mode: Int, why: String): OffsetResult =
        OffsetResult(label, "", emptyList(), 0, tuned = mode != 0, note = why)

    private fun note(mode: Int, settle: Boolean): String? {
        val window =
            when {
                mode > 0 -> "signed ${addition(mode)} bytes past the end of the function"
                mode < 0 -> "signed ${-addition(mode)} bytes short of the end of the function"
                else -> null
            }

        if (!settle) return window

        val reason = "could not get under the ceiling, so every candidate is listed"
        return if (window == null) reason else "$window, $reason"
    }
}
