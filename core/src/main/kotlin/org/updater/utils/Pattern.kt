package org.updater.utils

import capstone.api.Disassembler

/**
 * Disassembles [len] bytes of [buf] at [offset] and renders them as a byte signature, with the
 * instructions whose encodings move between builds replaced by wildcards.
 *
 * [len] is clamped to what is left in [buf], so asking for a longer signature than the file can
 * supply yields a shorter one rather than failing.
 */
fun getPattern(cs: Disassembler, buf: ByteArray, offset: Int, len: Int): String {
    require(offset >= 0 && offset < buf.size) {
        "Offset 0x${offset.toString(16)} is outside the library (size 0x${buf.size.toString(16)})"
    }

    val hex = HexUtils.getHexFromOffset(buf, offset, minOf(len, buf.size - offset))
    val instructions = cs.disasm(hex, offset.toLong())
    var pattern = StringBuilder()

    for (insn in instructions) {
        val result = insnIsWildCard(insn)
        val bytesAsHex: String =
            if (result.isWildCard) {
                if (result.specialByte) {
                    "?? ?? ?? ${String.format("%02x", insn.bytes[3])}" // Insert space between
                    // wildcards
                } else {
                    "?? ?? ?? ??" // Insert space for full wildcards
                }
            } else {
                HexUtils.byteArraytoHexString(insn.bytes).chunked(2).joinToString(" ")
            }
        pattern.append(bytesAsHex).append(" ") // Append the bytes and space
    }

    return pattern.toString().trim()
}
