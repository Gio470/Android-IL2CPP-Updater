package org.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutputTest {
    private val pattern = "1f 20 03 d5 ?? ?? ?? 94"

    private fun result(
        found: List<String>,
        sigLen: Int = 64,
        tuned: Boolean = false,
        error: String? = null,
    ) = OffsetResult("0xbeaf", pattern, found, sigLen, tuned, error)

    @Test
    fun `status follows the number of matches`() {
        assertEquals(MatchStatus.RESOLVED, result(listOf("0xc123")).status)
        assertEquals(MatchStatus.NOT_FOUND, result(emptyList()).status)
        assertEquals(MatchStatus.AMBIGUOUS, result(listOf("0x1", "0x2")).status)
        assertEquals(MatchStatus.ERROR, result(emptyList(), error = "bad offset").status)
    }

    @Test
    fun `pairs format maps a single match to one line`() {
        assertEquals("0xbeaf -> 0xc123", formatResult(result(listOf("0xc123")), OutputFormat.PAIRS))
    }

    @Test
    fun `pairs format notes the signature length only when it was tuned`() {
        assertEquals(
            "0xbeaf -> 0xc123 // sigLen 256",
            formatResult(result(listOf("0xc123"), sigLen = 256, tuned = true), OutputFormat.PAIRS),
        )
    }

    @Test
    fun `pairs format marks misses, ambiguity and errors`() {
        assertEquals("0xbeaf -> not found", formatResult(result(emptyList()), OutputFormat.PAIRS))
        assertEquals(
            "0xbeaf -> 0x2000, 0x3000 // ambiguous, 2 matches",
            formatResult(result(listOf("0x2000", "0x3000")), OutputFormat.PAIRS),
        )
        assertEquals(
            "0xbeaf -> error: past the end",
            formatResult(result(emptyList(), error = "past the end"), OutputFormat.PAIRS),
        )
    }

    @Test
    fun `offsets format round trips as an offsets file`() {
        assertEquals(
            "0xc123 // was 0xbeaf",
            formatResult(result(listOf("0xc123")), OutputFormat.OFFSETS),
        )
        assertEquals("// 0xbeaf not found", formatResult(result(emptyList()), OutputFormat.OFFSETS))
        assertEquals(
            "// 0xbeaf ambiguous: 0x1, 0x2",
            formatResult(result(listOf("0x1", "0x2")), OutputFormat.OFFSETS),
        )
    }

    @Test
    fun `detailed format reports the pattern, matches and signature length`() {
        assertEquals(
            """
            Offset: 0xbeaf:
                - Pattern: $pattern
                - Found offsets: 0xc123
                - Signature length: 64 bytes
            """
                .trimIndent(),
            formatResult(result(listOf("0xc123")), OutputFormat.DETAILED),
        )
    }

    @Test
    fun `detailed format reports None and errors`() {
        assertEquals(
            """
            Offset: 0xbeaf:
                - Pattern: $pattern
                - Found offsets: None
                - Signature length: 64 bytes
            """
                .trimIndent(),
            formatResult(result(emptyList()), OutputFormat.DETAILED),
        )
        assertEquals(
            """
            Offset: 0xbeaf:
                - Error: past the end
            """
                .trimIndent(),
            formatResult(result(emptyList(), error = "past the end"), OutputFormat.DETAILED),
        )
    }

    @Test
    fun `progress descriptions stay on one line`() {
        assertEquals("0xc123", describeResult(result(listOf("0xc123"))))
        assertEquals(
            "0xc123 (sigLen 256)",
            describeResult(result(listOf("0xc123"), sigLen = 256, tuned = true)),
        )
        assertEquals("2 matches, ambiguous", describeResult(result(listOf("0x1", "0x2"))))
        assertEquals("not found", describeResult(result(emptyList())))
        assertEquals("error: nope", describeResult(result(emptyList(), error = "nope")))
    }

    @Test
    fun `format names are parsed case insensitively`() {
        assertEquals(OutputFormat.PAIRS, OutputFormat.from("Pairs"))
        assertEquals(OutputFormat.DETAILED, OutputFormat.from(" detailed "))
        assertNull(OutputFormat.from("csv"))
    }
}
