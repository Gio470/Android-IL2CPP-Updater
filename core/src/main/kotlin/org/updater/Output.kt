package org.updater

enum class OutputFormat(val id: String) {
    DETAILED("detailed"),
    PAIRS("pairs"),
    OFFSETS("offsets");

    companion object {
        fun from(value: String): OutputFormat? =
            entries.firstOrNull { it.id.equals(value.trim(), ignoreCase = true) }

        val ids: String
            get() = entries.joinToString("|") { it.id }
    }
}

enum class MatchStatus {
    RESOLVED,
    AMBIGUOUS,
    NOT_FOUND,
    ERROR,
}

/**
 * What became of one offset. [sigLen] is the signature length the result was reached with, which is
 * not the requested one when [tuned] is set.
 */
data class OffsetResult(
    val offset: String,
    val pattern: String,
    val foundOffsets: List<String>,
    val sigLen: Int,
    val tuned: Boolean = false,
    val error: String? = null,
    /** Why this result is what it is, when the numbers alone would mislead. */
    val note: String? = null,
) {
    val status: MatchStatus
        get() =
            when {
                error != null -> MatchStatus.ERROR
                foundOffsets.size == 1 -> MatchStatus.RESOLVED
                foundOffsets.isEmpty() -> MatchStatus.NOT_FOUND
                else -> MatchStatus.AMBIGUOUS
            }
}

fun formatResult(result: OffsetResult, format: OutputFormat): String =
    when (format) {
        OutputFormat.DETAILED -> formatDetailed(result)
        OutputFormat.PAIRS -> formatPairs(result)
        OutputFormat.OFFSETS -> formatOffsets(result)
    }

/** A one-line summary of a result, for the progress output. */
fun describeResult(result: OffsetResult): String =
    when (result.status) {
        MatchStatus.RESOLVED ->
            result.foundOffsets.first() +
                // A note here says how it was found, which is worth more than the length.
                (result.note?.let { " ($it)" }
                    ?: if (result.tuned) " (sigLen ${result.sigLen})" else "")
        MatchStatus.AMBIGUOUS -> "${result.foundOffsets.size} matches, ambiguous"
        MatchStatus.NOT_FOUND -> "not found" + (result.note?.let { " ($it)" } ?: "")
        MatchStatus.ERROR -> "error: ${result.error}"
    }

private fun formatDetailed(result: OffsetResult): String {
    if (result.status == MatchStatus.ERROR) {
        return """
        Offset: ${result.offset}:
            - Error: ${result.error}
        """
            .trimIndent()
    }

    return """
    Offset: ${result.offset}:
        - Pattern: ${result.pattern}
        - Found offsets: ${if (result.foundOffsets.isNotEmpty()) result.foundOffsets.joinToString(", ") else "None"}
        - Signature length: ${result.sigLen} bytes
    """
        .trimIndent() + (result.note?.let { "\n    - Note: $it" } ?: "")
}

private fun formatPairs(result: OffsetResult): String {
    val sigLenNote = if (result.tuned) " // sigLen ${result.sigLen}" else ""

    return when (result.status) {
        MatchStatus.RESOLVED ->
            "${result.offset} -> ${result.foundOffsets.first()}" +
                (result.note?.let { " // $it" } ?: sigLenNote)
        MatchStatus.NOT_FOUND ->
            "${result.offset} -> not found" + (result.note?.let { " // $it" } ?: sigLenNote)
        MatchStatus.ERROR -> "${result.offset} -> error: ${result.error}"
        MatchStatus.AMBIGUOUS ->
            "${result.offset} -> ${result.foundOffsets.joinToString(", ")}" +
                " // ambiguous, ${result.foundOffsets.size} matches" +
                if (result.tuned) " at sigLen ${result.sigLen}" else ""
    }
}

/** The resolved offsets as an offsets file, ready to be the next run's input. */
private fun formatOffsets(result: OffsetResult): String =
    when (result.status) {
        MatchStatus.RESOLVED -> "${result.foundOffsets.first()} // was ${result.offset}"
        MatchStatus.NOT_FOUND ->
            "// ${result.offset} not found" + (result.note?.let { " ($it)" } ?: "")
        MatchStatus.ERROR -> "// ${result.offset} error: ${result.error}"
        MatchStatus.AMBIGUOUS ->
            "// ${result.offset} ambiguous: ${result.foundOffsets.joinToString(", ")}"
    }
