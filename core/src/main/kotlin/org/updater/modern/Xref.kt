package org.updater.modern

/**
 * Places ambiguous methods by who calls them.
 *
 * Bytes move between builds; the call graph largely does not. If a method whose new location is
 * known calls the ambiguous one, then scanning that caller's new body for calls says where the
 * ambiguous one went. Ported from `src/binary/xref.ts`.
 */
object Xref {

    private const val DEST_SCAN_CAP = 512
    private const val MAX_ITERATIONS = 10

    /** Which offsets each resolved body calls, indexed by callee. */
    private fun callerMap(source: ByteArray, resolved: List<ResolvedMethod>): Map<Int, List<Int>> {
        val callers = mutableMapOf<Int, MutableList<Int>>()

        for (method in resolved) {
            val end = minOf(method.sourceOffset + method.sourceBodyLength, source.size - 3)
            var position = method.sourceOffset
            while (position < end) {
                Branch.callTarget(source, position)?.let { target ->
                    callers.getOrPut(target) { mutableListOf() }.add(method.sourceOffset)
                }
                position += 4
            }
        }

        return callers
    }

    /** Every call target inside a window of the new library. */
    private fun callTargets(destination: ByteArray, offset: Int, window: Int): Set<Int> {
        val targets = mutableSetOf<Int>()
        val end = minOf(offset + window, destination.size - 3)
        var position = offset
        while (position < end) {
            Branch.callTarget(destination, position)?.let { targets.add(it) }
            position += 4
        }
        return targets
    }

    /**
     * Resolves what it can, then uses those answers to resolve more, until nothing new appears.
     *
     * A caller whose new body does not show the call at all is skipped rather than counted against
     * the method. Callers that disagree narrow the intersection, and if that empties the method
     * stays pending: a wrong offset is worse than none.
     */
    fun resolve(
        source: ByteArray,
        destination: ByteArray,
        pending: List<PendingMethod>,
        resolved: List<ResolvedMethod>,
    ): Map<String, Int> {
        val known = resolved.toMutableList()
        val bySource = known.associateBy { it.sourceOffset }.toMutableMap()
        var outstanding = pending.toList()
        val found = mutableMapOf<String, Int>()

        repeat(MAX_ITERATIONS) {
            if (outstanding.isEmpty()) return@repeat

            val callers = callerMap(source, known)
            val stillPending = mutableListOf<PendingMethod>()
            var progressed = false

            for (method in outstanding) {
                // What this method calls is independent of its bytes, so it survives relocation.
                if (method.candidates.size > 1) {
                    val bodyLength = minOf(method.sourceBodyLength ?: DEST_SCAN_CAP, DEST_SCAN_CAP)
                    val expected = mutableSetOf<Int>()
                    val end = minOf(method.sourceOffset + bodyLength, source.size - 3)
                    var position = method.sourceOffset
                    while (position < end) {
                        Branch.callTarget(source, position)?.let { target ->
                            bySource[target]?.let { expected.add(it.destOffset) }
                        }
                        position += 4
                    }

                    if (expected.isNotEmpty()) {
                        val matching =
                            method.candidates.filter { candidate ->
                                val targets = callTargets(destination, candidate, bodyLength)
                                expected.all { targets.contains(it) }
                            }
                        if (matching.size == 1) {
                            val destOffset = matching.first()
                            found[method.name] = destOffset
                            val entry =
                                ResolvedMethod(
                                    method.name,
                                    method.sourceOffset,
                                    destOffset,
                                    bodyLength,
                                )
                            known.add(entry)
                            bySource[method.sourceOffset] = entry
                            progressed = true
                            continue
                        }
                    }
                }

                val callingOffsets = callers[method.sourceOffset]
                if (callingOffsets.isNullOrEmpty()) {
                    stillPending.add(method)
                    continue
                }

                var intersection: MutableSet<Int>? = null
                var conflicted = false

                for (callerOffset in callingOffsets) {
                    val caller = bySource[callerOffset] ?: continue
                    val window = minOf(caller.sourceBodyLength * 2, DEST_SCAN_CAP)
                    val targets = callTargets(destination, caller.destOffset, window)
                    val hits = method.candidates.filter { targets.contains(it) }

                    // The call is not visible in this caller's window; that is not evidence.
                    if (hits.isEmpty()) continue

                    if (intersection == null) {
                        intersection = hits.toMutableSet()
                    } else {
                        intersection.retainAll(targets)
                    }

                    if (intersection.isEmpty()) {
                        conflicted = true
                        break
                    }
                }

                val settled = intersection
                if (conflicted || settled == null || settled.size != 1) {
                    stillPending.add(method)
                    continue
                }

                val destOffset = settled.first()
                found[method.name] = destOffset
                val entry =
                    ResolvedMethod(
                        method.name,
                        method.sourceOffset,
                        destOffset,
                        // The real body length is unknown, so stay conservative.
                        minOf(512, source.size - method.sourceOffset),
                    )
                known.add(entry)
                bySource[method.sourceOffset] = entry
                progressed = true
            }

            outstanding = stillPending
            if (!progressed) return found
        }

        return found
    }
}
