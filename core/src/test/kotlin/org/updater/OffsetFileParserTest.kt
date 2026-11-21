package org.updater

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.updater.utils.OffsetFileParser

class OffsetFileParserTest {
    private fun parse(contents: String): OffsetFileParser.Result {
        val file = File.createTempFile("offsets", ".txt")
        file.deleteOnExit()
        file.writeText(contents)
        return OffsetFileParser.parse(file.path)
    }

    @Test
    fun `reads offsets and ignores comments and blank lines`() {
        val result =
            parse(
                """
                0xBEAF // a comment
                
                // a whole line
                0xf00d
                0x1 0x2
                """
                    .trimIndent()
            )

        assertEquals(listOf(0xBEAF, 0xf00d, 0x1, 0x2), result.offsets)
        assertEquals(emptyList(), result.problems)
    }

    @Test
    fun `reports an uppercase prefix with the correction`() {
        val problems = parse("0X1234\n").problems
        assertEquals(1, problems.size)
        assertEquals(1, problems.first().line)
        assertEquals("0X1234", problems.first().token)
        assertTrue(problems.first().reason.contains("0x1234"))
    }

    @Test
    fun `reports a missing prefix with the correction`() {
        val problems = parse("0xbeef\n1234\n").problems
        assertEquals(listOf("1234"), problems.map { it.token })
        assertEquals(2, problems.first().line)
        assertTrue(problems.first().reason.contains("0x1234"))
    }

    @Test
    fun `reports offsets too large for the parser`() {
        val problems = parse("0x80000000\n").problems
        assertEquals(1, problems.size)
        assertTrue(problems.first().reason.contains("0x80000000"))
    }

    @Test
    fun `reports trailing punctuation and stray words`() {
        val problems = parse("0x1234,\nhello\n0x\n").problems
        assertEquals(listOf("0x1234,", "hello", "0x"), problems.map { it.token })
        assertTrue(problems[2].reason.contains("no digits"))
    }

    @Test
    fun `keeps the good offsets alongside the problems`() {
        val result = parse("0x10\nnonsense\n0x20\n")
        assertEquals(listOf(0x10, 0x20), result.offsets)
        assertEquals(1, result.problems.size)
    }

    @Test
    fun `a missing file is an error rather than an empty result`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "definitely-not-here.txt")
        try {
            OffsetFileParser.parse(missing.path)
            throw AssertionError("expected a failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("File not found"))
        }
    }
}
