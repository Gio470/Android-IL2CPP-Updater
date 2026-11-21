package org.updater.android

import androidx.annotation.StringRes
import org.updater.Engine
import org.updater.MatchStatus
import org.updater.OffsetResult
import org.updater.OutputFormat

/**
 * What the app calls things.
 *
 * Every one of these is a resource id rather than a word: the words live in
 * `res/values/strings.xml` so they can be changed, or translated, without going through the code.
 */
@get:StringRes
val Engine.label: Int
    get() =
        when (this) {
            Engine.CLASSIC -> R.string.engine_classic
            Engine.MODERN -> R.string.engine_modern
            Engine.NULLBIT -> R.string.engine_nullupdater
        }

@get:StringRes
val Engine.blurb: Int
    get() =
        when (this) {
            Engine.CLASSIC -> R.string.engine_classic_blurb
            Engine.MODERN -> R.string.engine_modern_blurb
            Engine.NULLBIT -> R.string.engine_nullupdater_blurb
        }

/**
 * Basic, Standard, Enhanced: the row reads low to high.
 *
 * [Engine.entries] is declared CLASSIC, MODERN, NULLBIT -- the order the CLI's --help, the config
 * file and the web UI all follow, and changing it there would reorder those too. This is the
 * Android app's own order for the toggle row only.
 */
val EngineDisplayOrder = listOf(Engine.NULLBIT, Engine.CLASSIC, Engine.MODERN)

@get:StringRes
val OutputFormat.blurb: Int
    get() =
        when (this) {
            OutputFormat.DETAILED -> R.string.format_detailed_blurb
            OutputFormat.PAIRS -> R.string.format_pairs_blurb
            OutputFormat.OFFSETS -> R.string.format_offsets_blurb
        }

/** Which results the list is showing. */
enum class ResultFilter(@StringRes val label: Int) {
    ALL(R.string.filter_all),
    RESOLVED(R.string.filter_resolved),
    AMBIGUOUS(R.string.filter_ambiguous),
    MISSING(R.string.filter_missing);

    fun matches(result: OffsetResult): Boolean =
        when (this) {
            ALL -> true
            RESOLVED -> result.status == MatchStatus.RESOLVED
            AMBIGUOUS -> result.status == MatchStatus.AMBIGUOUS
            MISSING -> result.status == MatchStatus.NOT_FOUND || result.status == MatchStatus.ERROR
        }

    fun count(results: List<OffsetResult>): Int = results.count(::matches)
}
