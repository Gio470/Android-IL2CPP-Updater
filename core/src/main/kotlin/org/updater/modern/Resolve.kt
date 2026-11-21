package org.updater.modern

import capstone.api.Disassembler

/** How a method's new location was established. */
enum class Strategy {
    EXACT,
    EXTENDED,
    MULTI_WINDOW,
    TRAMPOLINE,
}

/**
 * Where a signature matched, and what it took to get there.
 *
 * [sourcePattern] is kept separately because a verified trampoline rewrites the pattern to describe
 * the new binary, which no longer describes the old one.
 */
data class Resolution(
    val pattern: String,
    val sourcePattern: String,
    val length: Int,
    val matches: List<Int>,
    val sourceMatches: List<Int>,
    val strategy: Strategy,
)

/**
 * Locates a method from the old library in the new one.
 *
 * Four strategies, in order of how much they assume. Ported from `src/patterns/resolve.ts`.
 */
object Resolve {

    private const val SEED = 48
    private const val CHUNK = 48
    private const val STEP = 12
    private const val WINDOW = 32
    private const val EVIDENCE_CAP = 384

    private val BRANCHING = Regex("^(?:b(?:\\.|$)|bl$|br$|blr$|cbz$|cbnz$|tbz$|tbnz$|ret$)")

    private val empty = Resolution("", "", 0, emptyList(), emptyList(), Strategy.EXACT)

    /** The 48-byte seed signature, before anything has been scanned. */
    fun initialPattern(
        disassembler: Disassembler,
        source: ByteArray,
        offset: Int,
        maxLength: Int,
    ): Pair<String, Int> {
        val available = minOf(maxLength, source.size - offset)
        val firstLength = minOf(SEED, (available / 4) * 4)
        if (firstLength <= 0) return "" to 0

        val parts = maskedParts(disassembler, source, offset, firstLength, strict = false)
        return parts.joinToString(" ") to parts.size
    }

    private fun maskedParts(
        disassembler: Disassembler,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        strict: Boolean,
    ): List<String> {
        val end = minOf(offset + length, buffer.size)
        if (offset < 0 || offset >= buffer.size || end <= offset) return emptyList()

        val parts = mutableListOf<String>()
        for (instruction in disassembler.disasm(buffer.copyOfRange(offset, end), offset.toLong())) {
            val mask =
                if (strict) {
                    Wildcards.maskStrict(instruction.mnemonic, instruction.opStr, instruction.bytes)
                } else {
                    Wildcards.mask(instruction.mnemonic, instruction.opStr, instruction.bytes)
                }
            for (i in mask.indices step 2) parts.add(mask.substring(i, i + 2))
        }
        return parts
    }

    private class Flow(val mnemonics: List<String>, val branches: List<String>)

    /** The shape of a region's control flow, used to tell real matches from coincidences. */
    private fun controlFlow(disassembler: Disassembler, bytes: ByteArray, address: Int): Flow {
        val mnemonics = disassembler.disasm(bytes, address.toLong()).map { it.mnemonic }
        val branches =
            mnemonics.mapIndexedNotNull { index, mnemonic ->
                if (BRANCHING.containsMatchIn(mnemonic)) "$index:$mnemonic" else null
            }
        return Flow(mnemonics, branches)
    }

    fun resolve(
        disassembler: Disassembler,
        source: ByteArray,
        destination: ByteArray,
        offset: Int,
        maxLength: Int,
        seed: Triple<String, Int, List<Int>>? = null,
    ): Resolution {
        val available = minOf(maxLength, source.size - offset)
        val firstLength = minOf(SEED, (available / 4) * 4)
        if (firstLength <= 0) return empty

        val seedPattern =
            seed?.first ?: initialPattern(disassembler, source, offset, maxLength).first
        val parts =
            (if (seedPattern.isEmpty()) emptyList() else seedPattern.split(" ")).toMutableList()
        if (parts.isEmpty()) return empty

        var length = parts.size
        val pattern48 = parts.joinToString(" ")
        var matches = seed?.third ?: Scan.scan(pattern48, destination)

        // Almost always the 48-byte signature is already unique, and the old library never has to
        // be scanned at all.
        if (length == firstLength && matches.size == 1) {
            return Resolution(pattern48, pattern48, length, matches, listOf(offset), Strategy.EXACT)
        }

        var sourceMatches = Scan.scan(pattern48, source)
        if (isAt(sourceMatches, offset) && matches.size == 1) {
            return Resolution(pattern48, pattern48, length, matches, sourceMatches, Strategy.EXACT)
        }

        fun extendSource(limit: Int) {
            while (parts.size < limit) {
                val chunk = minOf(CHUNK, limit - parts.size)
                val grown =
                    maskedParts(disassembler, source, offset + parts.size, chunk, strict = false)
                if (grown.isEmpty()) break
                val before = parts.size
                parts.addAll(grown)
                if (parts.size == before) break
            }
        }

        // Grow until the signature picks out its own offset in the old library.
        while (length < available && !isAt(sourceMatches, offset)) {
            extendSource(minOf(available, length + CHUNK))
            val limit = minOf(parts.size, (available / 4) * 4)
            if (limit <= length) break

            while (length < limit && !isAt(sourceMatches, offset)) {
                val next = minOf(length + STEP, limit)
                sourceMatches =
                    Scan.filterMatches(
                        source,
                        sourceMatches,
                        parts.subList(0, next).joinToString(" "),
                        length,
                    )
                length = next
            }
        }

        if (matches.isNotEmpty()) {
            matches =
                Scan.filterMatches(
                    destination,
                    matches,
                    parts.subList(0, length).joinToString(" "),
                    0,
                )
        }

        var strategy = Strategy.EXACT
        if (length == 0 || (isAt(sourceMatches, offset) && matches.size == 1)) {
            val settled = parts.subList(0, length).joinToString(" ")
            return Resolution(settled, settled, length, matches, sourceMatches, strategy)
        }

        // The new library can need more evidence than the old one did.
        if (matches.size > 1) {
            while (length < maxLength && matches.size > 1) {
                if (length >= parts.size) {
                    extendSource(minOf(maxLength, length + CHUNK))
                    if (length >= parts.size) break
                }
                val next = minOf(length + STEP, parts.size)
                if (next <= length) break
                val grown = parts.subList(0, next).joinToString(" ")
                matches = Scan.filterMatches(destination, matches, grown, length)
                sourceMatches = Scan.filterMatches(source, sourceMatches, grown, length)
                length = next
            }
            strategy = Strategy.EXTENDED

            // Portable masks drop relocatable field offsets. When that leaves several candidates,
            // retry those candidates keeping the small stable ones -- but only to narrow what the
            // portable pass already found, never to widen it.
            if (matches.size > 1) {
                val strict =
                    maskedParts(disassembler, source, offset, length, strict = true)
                        .joinToString(" ")
                val strictMatches = Scan.filterMatches(destination, matches, strict, 0)
                if (strictMatches.size == 1) {
                    val strictSource = Scan.filterMatches(source, sourceMatches, strict, 0)
                    if (isAt(strictSource, offset)) {
                        return Resolution(
                            strict,
                            strict,
                            length,
                            strictMatches,
                            strictSource,
                            strategy,
                        )
                    }
                }
            }
        }

        val settled = parts.subList(0, length).joinToString(" ")
        if (!(isAt(sourceMatches, offset) && matches.size == 1)) {
            multiWindow(disassembler, source, destination, offset, available, matches)?.let {
                return it
            }
        }

        if (matches.isNotEmpty() || !isAt(sourceMatches, offset) || length < SEED) {
            return Resolution(settled, settled, length, matches, sourceMatches, strategy)
        }

        return trampoline(disassembler, source, destination, offset, length, settled, sourceMatches)
            ?: Resolution(settled, settled, length, matches, sourceMatches, strategy)
    }

    /**
     * Evidence from several windows, so one rewritten region cannot sink the whole signature.
     *
     * Four 32-byte windows are spread across the method and every three-of-four combination is
     * tried, the gaps being wildcards. Candidates then have to agree on control flow.
     */
    private fun multiWindow(
        disassembler: Disassembler,
        source: ByteArray,
        destination: ByteArray,
        offset: Int,
        available: Int,
        existing: List<Int>,
    ): Resolution? {
        val evidence = minOf((available / 4) * 4, EVIDENCE_CAP)
        if (evidence < WINDOW * 3) return null

        val span = evidence - WINDOW
        val starts = listOf(0, (span / 3 / 4) * 4, (span * 2 / 3 / 4) * 4, span).distinct()
        if (starts.size < 3) return null

        val windows =
            starts.map {
                it to maskedParts(disassembler, source, offset + it, WINDOW, strict = false)
            }
        val sourceFlow =
            controlFlow(
                disassembler,
                source.copyOfRange(offset, minOf(offset + evidence, source.size)),
                offset,
            )

        for (omitted in -1 until windows.size) {
            val selected = windows.filterIndexed { index, _ -> index != omitted }
            if (selected.size < 3) continue

            val parts = MutableList(evidence) { "??" }
            for ((start, bytes) in selected) {
                bytes.forEachIndexed { i, part ->
                    if (start + i < evidence) parts[start + i] = part
                }
            }

            val pattern = parts.joinToString(" ")
            val sourceMatches = Scan.scan(pattern, source)
            if (!isAt(sourceMatches, offset)) continue

            val raw =
                if (existing.isNotEmpty()) Scan.filterMatches(destination, existing, pattern, 0)
                else Scan.scan(pattern, destination)
            val candidates = raw.filter { it % 4 == 0 && it + evidence <= destination.size }

            val verified =
                candidates.filter { candidate ->
                    val flow =
                        controlFlow(
                            disassembler,
                            destination.copyOfRange(candidate, candidate + evidence),
                            candidate,
                        )
                    if (flow.branches != sourceFlow.branches) return@filter false
                    if (flow.mnemonics.size != sourceFlow.mnemonics.size) return@filter false
                    val same =
                        flow.mnemonics.indices.count {
                            flow.mnemonics[it] == sourceFlow.mnemonics[it]
                        }
                    same.toDouble() / sourceFlow.mnemonics.size >= 0.9
                }

            if (verified.size == 1) {
                return Resolution(
                    pattern,
                    pattern,
                    evidence,
                    verified,
                    sourceMatches,
                    Strategy.MULTI_WINDOW,
                )
            }
        }

        return null
    }

    /**
     * A method whose body is intact except that one instruction was replaced by a jump out to it.
     *
     * Substituting one instruction leaves at least one half of the signature whole, so the halves
     * find candidates. Acceptance is stricter: exactly one instruction may differ, it has to be a
     * register-relative load, the replacement has to be a direct branch landing outside the method,
     * the displaced instruction has to be at the target, and the instruction after it has to branch
     * back.
     */
    private fun trampoline(
        disassembler: Disassembler,
        source: ByteArray,
        destination: ByteArray,
        offset: Int,
        length: Int,
        pattern: String,
        sourceMatches: List<Int>,
    ): Resolution? {
        val parts = pattern.split(" ").toMutableList()
        val split = (length / 8) * 4
        if (split <= 0 || split >= parts.size) return null

        val candidates =
            (Scan.scan(parts.subList(0, split).joinToString(" "), destination) +
                    Scan.scan(parts.subList(split, parts.size).joinToString(" "), destination).map {
                        it - split
                    })
                .toSet()

        val compiled = Scan.Compiled.parse(pattern)
        val verified = mutableListOf<Pair<Int, Int>>()

        for (start in candidates) {
            if (start < 0 || start % 4 != 0 || start + length > destination.size) continue

            var changed = -1
            var valid = true
            var i = 0
            while (i < length) {
                if (compiled.matchesRange(destination, start, i, i + 4)) {
                    i += 4
                    continue
                }
                if (changed != -1) {
                    valid = false
                    break
                }

                val original =
                    disassembler
                        .disasm(
                            source.copyOfRange(offset + i, offset + i + 4),
                            (offset + i).toLong(),
                        )
                        .firstOrNull()
                // Register-relative loads can be moved; PC-relative ones cannot.
                if (
                    original == null ||
                        !Regex("^ldr(?:b|h|sw)?$").matches(original.mnemonic) ||
                        !original.opStr.contains('[')
                ) {
                    valid = false
                    break
                }

                val target = Branch.branchTarget(destination, start + i)
                if (
                    target == null ||
                        (target >= start && target < start + length) ||
                        !compiled.matchesRange(destination, target - i, i, i + 4) ||
                        Branch.branchTarget(destination, target + 4) != start + i + 4
                ) {
                    valid = false
                    break
                }

                changed = i
                i += 4
            }

            if (valid && changed != -1) verified.add(start to changed)
        }

        if (verified.size != 1) return null

        val (start, changed) = verified.first()
        val adapted = parts.toMutableList()
        for (k in 0 until 4) adapted[changed + k] = if (k == 3) "1?" else "??"
        val adaptedPattern = adapted.joinToString(" ")

        // Only hand back a pattern that a plain scan would also settle on.
        val adaptedMatches = Scan.scan(adaptedPattern, destination)
        if (adaptedMatches.size != 1 || adaptedMatches[0] != start) return null

        return Resolution(
            adaptedPattern,
            pattern,
            length,
            adaptedMatches,
            sourceMatches,
            Strategy.TRAMPOLINE,
        )
    }

    private fun isAt(matches: List<Int>, offset: Int) = matches.size == 1 && matches[0] == offset
}
