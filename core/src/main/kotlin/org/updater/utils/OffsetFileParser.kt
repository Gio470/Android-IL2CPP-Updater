package org.updater.utils

import java.io.File

object OffsetFileParser {

    /** A token the parser could not read, so that it can be reported rather than dropped. */
    data class Problem(val line: Int, val token: String, val reason: String)

    /** An offset together with whatever name the line gave it. */
    data class Entry(val offset: Int, val name: String)

    data class Result(
        val offsets: List<Int>,
        val problems: List<Problem>,
        /**
         * The same offsets, each with a name. The modern engine's fallbacks refer to methods by
         * name, so a trailing `// name` or `-- name` is kept rather than discarded as a comment.
         */
        val entries: List<Entry> = offsets.map { Entry(it, "0x${it.toString(16)}") },
    )

    fun parse(filePath: String): Result {
        val file = File(filePath)

        if (!file.exists()) {
            throw IllegalArgumentException("File not found: $filePath")
        }

        return parseText(file.readText())
    }

    /** The same parsing, for callers holding the text rather than a path. */
    fun parseText(text: String): Result {
        val offsets = mutableListOf<Int>()
        val entries = mutableListOf<Entry>()
        val problems = mutableListOf<Problem>()
        var lineNumber = 0

        text.lineSequence().forEach { line ->
            lineNumber++
            val name = nameIn(line)
            for (token in tokenize(line)) {
                val offset = token.removePrefix("0x").toIntOrNull(16)
                when {
                    !token.startsWith("0x") ->
                        problems.add(Problem(lineNumber, token, reasonFor(token)))
                    offset == null -> problems.add(Problem(lineNumber, token, reasonFor(token)))
                    else -> {
                        offsets.add(offset)
                        entries.add(Entry(offset, name ?: "0x${offset.toString(16)}"))
                    }
                }
            }
        }

        return Result(offsets, problems, entries)
    }

    /** The text after `//` or `--`, which the upstream format uses for the method name. */
    private fun nameIn(line: String): String? {
        val marker =
            listOf("//", "--").mapNotNull { m -> line.indexOf(m).takeIf { it >= 0 } }.minOrNull()
        if (marker == null) return null
        return line.substring(marker + 2).trim().ifEmpty { null }
    }

    private fun reasonFor(token: String): String {
        val digits = token.removePrefix("0x").removePrefix("0X")

        return when {
            token.startsWith("0X") -> "hexadecimal prefix must be lowercase, write 0x$digits"
            !token.startsWith("0x") && isHex(digits) -> "missing the 0x prefix, write 0x$token"
            !token.startsWith("0x") -> "not an offset; use // to start a comment"
            digits.isEmpty() -> "no digits after 0x"
            !isHex(digits) -> "not valid hexadecimal"
            else -> "too large, offsets must be below 0x80000000"
        }
    }

    private fun isHex(text: String): Boolean =
        text.isNotEmpty() && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun tokenize(line: String): List<String> {
        val cleanLine = line.substringBefore("//").substringBefore("--").trim()
        return cleanLine.split("\\s+".toRegex()).filter { it.isNotEmpty() }
    }
}
