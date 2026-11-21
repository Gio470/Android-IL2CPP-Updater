package org.updater

import capstone.api.Disassembler
import org.updater.utils.Signature
import org.updater.utils.getPattern

const val MIN_SIG_LEN = 16
private const val MAX_BISECTIONS = 4

data class MatchSettings(
    val sigLen: Int,
    val maxSigLen: Int,
    val autoTune: Boolean,
    val minSigLen: Int = MIN_SIG_LEN,
    /** How many matches a signature may have and still be taken. The nullbit engine only. */
    val maxMatches: Int = 1,
)

private class Attempt(
    val sigLen: Int,
    val pattern: String,
    val signature: Signature,
    val matches: List<Int>,
)

/**
 * Locates [offset] from the old library in the new one.
 *
 * With [MatchSettings.autoTune] the signature length is adjusted per offset: widened while the
 * signature matches in more than one place, narrowed while it matches nowhere. Widening filters the
 * matches already found instead of scanning again, which it can do because a longer signature only
 * matches where its own prefix did.
 */
fun resolveOffset(
    cs: Disassembler,
    oldBytes: ByteArray,
    newBytes: ByteArray,
    offset: Int,
    settings: MatchSettings,
): OffsetResult {
    val label = "0x${offset.toString(16)}"

    if (offset < 0 || offset >= oldBytes.size) {
        return failed(
            label,
            "offset is past the end of the old library (size 0x${oldBytes.size.toString(16)})",
        )
    }

    val requested = minOf(settings.sigLen, oldBytes.size - offset)
    val first =
        try {
            attempt(cs, oldBytes, newBytes, offset, requested)
        } catch (e: Exception) {
            return failed(label, e.message ?: e.toString())
        }

    if (first.signature.isBlank) {
        return failed(label, "no instructions could be disassembled here")
    }

    val best =
        when {
            !settings.autoTune -> first
            first.matches.size > 1 -> widen(cs, oldBytes, newBytes, offset, first, settings)
            first.matches.isEmpty() -> narrow(cs, oldBytes, newBytes, offset, first, settings)
            else -> first
        }

    // Narrowing had to give up on the real signature and fall back to a fragment of it. A
    // fragment matching in several places identifies nothing: reporting those addresses as
    // candidates invites someone to use one.
    if (best.sigLen < requested && best.matches.size > 1) {
        return OffsetResult(
            offset = label,
            pattern = best.pattern,
            foundOffsets = emptyList(),
            sigLen = best.sigLen,
            tuned = true,
            note =
                "nothing matched at $requested bytes; its first ${best.sigLen} bytes " +
                    "matched ${best.matches.size} places, so they identify nothing",
        )
    }

    return OffsetResult(
        offset = label,
        pattern = best.pattern,
        foundOffsets = best.matches.map { "0x${it.toString(16)}" },
        sigLen = best.sigLen,
        tuned = best.sigLen != requested,
    )
}

private fun widen(
    cs: Disassembler,
    oldBytes: ByteArray,
    newBytes: ByteArray,
    offset: Int,
    from: Attempt,
    settings: MatchSettings,
): Attempt {
    val available = oldBytes.size - offset
    var best = from

    while (best.matches.size > 1 && best.sigLen < settings.maxSigLen) {
        val longer = minOf(best.sigLen * 2, settings.maxSigLen, available)
        if (longer <= best.sigLen) break

        val wider = attempt(cs, oldBytes, newBytes, offset, longer, from = best)
        // The disassembly stopped growing, so a longer window cannot say anything new.
        if (wider.signature.size <= best.signature.size) break
        // Doubling ran past the point where the function still matches. The length that
        // settles it may lie in between, so look there before giving up.
        if (wider.matches.isEmpty()) {
            between(cs, oldBytes, newBytes, offset, best, longer)?.let { best = it }
            break
        }

        best = wider
    }

    return best
}

/**
 * Searches between a signature length that matches too often and one that matches nowhere, for the
 * longest one in between that still matches. Each step filters [from]'s matches rather than
 * scanning the library again.
 */
private fun between(
    cs: Disassembler,
    oldBytes: ByteArray,
    newBytes: ByteArray,
    offset: Int,
    from: Attempt,
    tooLong: Int,
): Attempt? {
    var low = from.sigLen
    var high = tooLong
    var best: Attempt? = null

    repeat(MAX_BISECTIONS) {
        val middle = ((low + high) / 2) and 3.inv() // instructions are four bytes wide
        if (middle <= low || middle >= high) return best

        val attempt = attempt(cs, oldBytes, newBytes, offset, middle, from = from)
        if (attempt.matches.isEmpty()) {
            high = middle
        } else {
            best = attempt
            if (attempt.matches.size == 1) return attempt
            low = middle
        }
    }

    return best
}

private fun narrow(
    cs: Disassembler,
    oldBytes: ByteArray,
    newBytes: ByteArray,
    offset: Int,
    from: Attempt,
    settings: MatchSettings,
): Attempt {
    var sigLen = from.sigLen

    while (sigLen > settings.minSigLen) {
        val shorter = maxOf(sigLen / 2, settings.minSigLen)
        if (shorter >= sigLen) break

        val narrower = attempt(cs, oldBytes, newBytes, offset, shorter)
        if (narrower.signature.isBlank) break
        if (narrower.matches.isNotEmpty()) {
            // Halving jumps a long way. If this length matches in several places, a
            // longer one below the last failure may single one out.
            if (narrower.matches.size == 1) return narrower
            return between(cs, oldBytes, newBytes, offset, narrower, sigLen) ?: narrower
        }

        sigLen = shorter
    }

    return from
}

/**
 * Builds the signature of [sigLen] bytes at [offset] and finds where it matches.
 *
 * When [from] is given and this signature extends it, its matches are filtered rather than the
 * whole library being scanned again.
 */
private fun attempt(
    cs: Disassembler,
    oldBytes: ByteArray,
    newBytes: ByteArray,
    offset: Int,
    sigLen: Int,
    from: Attempt? = null,
): Attempt {
    val pattern = getPattern(cs, oldBytes, offset, sigLen)
    val signature = Signature.parse(pattern)
    val matches =
        if (from != null && signature.startsWith(from.signature)) {
            from.matches.filter { signature.matchesAt(newBytes, it) }
        } else {
            signature.findAll(newBytes)
        }

    return Attempt(sigLen, pattern, signature, matches)
}

private fun failed(label: String, reason: String): OffsetResult =
    OffsetResult(
        offset = label,
        pattern = "",
        foundOffsets = emptyList(),
        sigLen = 0,
        error = reason,
    )
