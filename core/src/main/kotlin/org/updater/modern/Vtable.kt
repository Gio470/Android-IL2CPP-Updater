package org.updater.modern

import kotlin.math.abs

/**
 * Places ambiguous methods by their slot in a C++ vtable.
 *
 * Virtual methods are 64-bit function pointers in read-only data, and a method's slot index does
 * not change between versions. Finding a neighbour in the same table whose new location is already
 * known fixes where the ambiguous method's slot must be, and the pointer there is the answer.
 * Ported from `src/binary/vtable.ts`.
 */
object Vtable {

    /** How many slots either side to search for a neighbour that is already placed. */
    private const val SCAN_RADIUS = 2048

    fun resolve(
        source: ByteArray,
        destination: ByteArray,
        pending: List<PendingMethod>,
        resolved: List<ResolvedMethod>,
    ): Map<String, Int> {
        val sourceSegments = Elf.segments(source)
        val destSegments = Elf.segments(destination)
        // Vtables live in read-only data, never in executable segments.
        val sourceData = sourceSegments.filterNot { it.executable }
        val destData = destSegments.filterNot { it.executable }

        val resolvedSourceVa = mutableMapOf<Long, Int>()
        val resolvedDestVa = mutableMapOf<Int, Long>()
        for (method in resolved) {
            Elf.fileToVa(sourceSegments, method.sourceOffset)?.let {
                resolvedSourceVa[it] = method.destOffset
            }
            Elf.fileToVa(destSegments, method.destOffset)?.let {
                resolvedDestVa[method.destOffset] = it
            }
        }

        val pendingByVa = mutableMapOf<Long, Pair<PendingMethod, Set<Int>>>()
        for (method in pending) {
            Elf.fileToVa(sourceSegments, method.sourceOffset)?.let {
                pendingByVa[it] = method to method.candidates.toSet()
            }
        }

        val interestingSource = pendingByVa.keys + resolvedSourceVa.keys
        val interestingDest = resolvedDestVa.values.toSet()

        // One pass over the old data segments, keeping only slots that matter.
        val sourceSlots = mutableMapOf<Int, Long>()
        for (segment in sourceData) {
            var position = segment.file
            val end = minOf(segment.file + segment.fileSize - 8, source.size - 8)
            while (position <= end) {
                val va = Elf.readVa(source, position)
                if (va != 0L && interestingSource.contains(va)) sourceSlots[position] = va
                position += 8
            }
        }

        // One pass over the new data segments, recording where known destinations appear.
        val destSlots = mutableMapOf<Long, MutableList<Int>>()
        for (segment in destData) {
            var position = segment.file
            val end = minOf(segment.file + segment.fileSize - 8, destination.size - 8)
            while (position <= end) {
                val va = Elf.readVa(destination, position)
                if (va != 0L && interestingDest.contains(va)) {
                    destSlots.getOrPut(va) { mutableListOf() }.add(position)
                }
                position += 8
            }
        }

        // Nearer neighbours count for more, and each distinct neighbour is counted once.
        val votes = mutableMapOf<String, MutableMap<Int, Double>>()
        val supporters = mutableMapOf<String, MutableMap<Int, MutableSet<Long>>>()

        for ((slot, va) in sourceSlots) {
            val entry = pendingByVa[va] ?: continue
            val (method, candidates) = entry
            val methodVotes = votes.getOrPut(method.name) { mutableMapOf() }

            for (delta in -SCAN_RADIUS..SCAN_RADIUS) {
                if (delta == 0) continue

                val neighbourSlot = slot + delta * 8
                val neighbourVa = sourceSlots[neighbourSlot] ?: continue
                val neighbourDest = resolvedSourceVa[neighbourVa] ?: continue
                val neighbourDestVa = resolvedDestVa[neighbourDest] ?: continue
                val neighbourSlots = destSlots[neighbourDestVa] ?: continue

                for (destNeighbourSlot in neighbourSlots) {
                    val candidateSlot = destNeighbourSlot - delta * 8
                    if (candidateSlot < 0 || candidateSlot + 8 > destination.size) continue

                    val candidateVa = Elf.readVa(destination, candidateSlot)
                    if (candidateVa == 0L) continue

                    val candidate = Elf.vaToFile(destSegments, candidateVa) ?: continue
                    if (candidates.isNotEmpty() && !candidates.contains(candidate)) continue

                    methodVotes[candidate] = (methodVotes[candidate] ?: 0.0) + 1.0 / abs(delta)
                    supporters
                        .getOrPut(method.name) { mutableMapOf() }
                        .getOrPut(candidate) { mutableSetOf() }
                        .add(neighbourVa)
                }
            }
        }

        val answers = mutableMapOf<String, Int>()
        for ((name, methodVotes) in votes) {
            if (methodVotes.isEmpty()) continue

            var best = -1
            var bestVotes = 0.0
            var tied = false
            for ((candidate, count) in methodVotes) {
                if (count > bestVotes) {
                    bestVotes = count
                    best = candidate
                    tied = false
                } else if (count == bestVotes) {
                    tied = true
                }
            }

            val total = methodVotes.values.sum()
            val method = pending.firstOrNull { it.name == name }
            val hadNoCandidates = method?.candidates?.isEmpty() ?: false

            if (hadNoCandidates) {
                // With nothing from the byte scan to check against, demand real consensus.
                val support = supporters[name]?.get(best)?.size ?: 0
                if (!tied && support >= 2 && bestVotes >= 0.1 && bestVotes / total >= 0.8) {
                    answers[name] = best
                }
            } else if (!tied) {
                answers[name] = best
            }
        }

        return answers
    }
}
