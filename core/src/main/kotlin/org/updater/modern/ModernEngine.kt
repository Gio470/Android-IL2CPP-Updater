package org.updater.modern

import org.updater.MatchStatus
import org.updater.OffsetResult
import org.updater.createArm64Disassembler

/**
 * The ported engine, end to end.
 *
 * Signatures first: a 48-byte seed for every method, scanned in one batch, then grown per method
 * only where it has to be. Whatever that leaves undecided goes through the fallbacks in order of
 * how much each assumes — read-only data pointer arrays, then the call graph, then vtable slots —
 * with a dump, if one is offered, able to settle a name outright.
 *
 * Ported from `src/updater.ts`.
 */
object ModernEngine {

    /** A method to carry over, and the name the fallbacks will refer to it by. */
    data class Entry(val offset: Int, val name: String)

    data class Settings(
        val signatureLength: Int = 300,
        val useFallbacks: Boolean = true,
        val oldDump: Metadata? = null,
        val newDump: Metadata? = null,
    )

    fun run(
        source: ByteArray,
        destination: ByteArray,
        entries: List<Entry>,
        settings: Settings = Settings(),
        onResult: (done: Int, total: Int, result: OffsetResult) -> Unit = { _, _, _ -> },
        keepGoing: () -> Boolean = { true },
    ): List<OffsetResult> {
        val disassembler = createArm64Disassembler()
        val results = LinkedHashMap<String, OffsetResult>()
        val resolved = mutableListOf<ResolvedMethod>()
        val pending = mutableListOf<PendingMethod>()
        val pendingIndex = mutableMapOf<String, Entry>()

        try {
            // A signature must not run past the end of its method into the next one.
            val lengths =
                entries.map { entry ->
                    val next = settings.oldDump?.nextAddress(entry.offset.toLong())
                    if (next == null) settings.signatureLength
                    else minOf(settings.signatureLength, (next - entry.offset).toInt())
                }

            val seeds =
                entries.mapIndexed { index, entry ->
                    Resolve.initialPattern(disassembler, source, entry.offset, lengths[index]).first
                }

            // Distinct seeds only: many methods share a prologue, and each scan is a pass over the
            // whole library.
            val distinct = seeds.distinct()
            val batched = Scan.batchScan(distinct, destination)
            val byPattern = distinct.zip(batched).toMap()

            entries.forEachIndexed { index, entry ->
                if (!keepGoing()) return@forEachIndexed

                val label = "0x${entry.offset.toString(16)}"
                if (entry.offset % 4 != 0 || entry.offset + 4 > source.size) {
                    val bad =
                        OffsetResult(
                            label,
                            "",
                            emptyList(),
                            0,
                            error = "offset must address an AArch64 instruction inside the library",
                        )
                    results[entry.name] = bad
                    onResult(index + 1, entries.size, bad)
                    return@forEachIndexed
                }

                val length = lengths[index]
                val seed = seeds[index]
                val resolution =
                    Resolve.resolve(
                        disassembler,
                        source,
                        destination,
                        entry.offset,
                        length,
                        byPattern[seed]?.let { Triple(seed, seed.split(" ").size, it) },
                    )

                val bodyLength =
                    settings.oldDump?.nextAddress(entry.offset.toLong())?.let {
                        (it - entry.offset).toInt()
                    } ?: minOf(length * 2, 512)

                val unique =
                    resolution.sourceMatches.size == 1 &&
                        resolution.sourceMatches[0] == entry.offset &&
                        resolution.matches.size == 1

                // A dump of the new library can name the answer outright.
                val fromDump = dumpAnswer(settings, destination, entry)
                val corrected = unique && fromDump != null && resolution.matches[0] != fromDump

                val result =
                    when {
                        corrected ->
                            OffsetResult(
                                label,
                                resolution.pattern,
                                listOf("0x${fromDump!!.toString(16)}"),
                                resolution.length,
                                tuned = true,
                                note = "corrected against the new dump",
                            )
                        unique ->
                            OffsetResult(
                                label,
                                resolution.pattern,
                                listOf("0x${resolution.matches[0].toString(16)}"),
                                resolution.length,
                                tuned = resolution.strategy != Strategy.EXACT,
                                note = strategyNote(resolution.strategy),
                            )
                        else ->
                            OffsetResult(
                                label,
                                resolution.pattern,
                                resolution.matches.map { "0x${it.toString(16)}" },
                                resolution.length,
                                tuned = resolution.strategy != Strategy.EXACT,
                            )
                    }

                results[entry.name] = result

                when {
                    corrected ->
                        resolved.add(
                            ResolvedMethod(entry.name, entry.offset, fromDump!!, bodyLength)
                        )
                    unique ->
                        resolved.add(
                            ResolvedMethod(
                                entry.name,
                                entry.offset,
                                resolution.matches[0],
                                bodyLength,
                            )
                        )
                    settings.useFallbacks -> {
                        pending.add(
                            PendingMethod(entry.name, entry.offset, resolution.matches, bodyLength)
                        )
                        pendingIndex[entry.name] = entry
                    }
                }

                onResult(index + 1, entries.size, result)
            }

            if (settings.useFallbacks && pending.isNotEmpty() && keepGoing()) {
                runFallbacks(source, destination, pending, resolved, results, pendingIndex)
            }
        } finally {
            disassembler.close()
        }

        return entries.mapNotNull { results[it.name] }
    }

    /** Read-only data, then the call graph, then vtables — each only on what is still unplaced. */
    private fun runFallbacks(
        source: ByteArray,
        destination: ByteArray,
        pending: MutableList<PendingMethod>,
        resolved: MutableList<ResolvedMethod>,
        results: MutableMap<String, OffsetResult>,
        entries: Map<String, Entry>,
    ) {
        fun accept(answers: Map<String, Int>, how: String) {
            for ((name, offset) in answers) {
                val entry = entries[name] ?: continue
                val previous = results[name]
                results[name] =
                    OffsetResult(
                        offset = previous?.offset ?: "0x${entry.offset.toString(16)}",
                        pattern = previous?.pattern ?: "",
                        foundOffsets = listOf("0x${offset.toString(16)}"),
                        sigLen = previous?.sigLen ?: 0,
                        tuned = true,
                        note = how,
                    )
                resolved.add(ResolvedMethod(name, entry.offset, offset, 512))
            }
            pending.removeAll { answers.containsKey(it.name) }
        }

        accept(Rodata.resolve(source, destination, pending.toList()), "placed by its method table")
        if (pending.isEmpty()) return

        accept(
            Xref.resolve(source, destination, pending.toList(), resolved.toList()),
            "placed by what calls it",
        )
        if (pending.isEmpty()) return

        accept(
            Vtable.resolve(source, destination, pending.toList(), resolved.toList()),
            "placed by its vtable slot",
        )
    }

    private fun dumpAnswer(settings: Settings, destination: ByteArray, entry: Entry): Int? {
        val newDump = settings.newDump ?: return null
        val candidates =
            settings.oldDump
                ?.identify(entry.name)
                ?.firstOrNull { it.address == entry.offset.toLong() }
                ?.let { newDump.identify(entry.name, it.signature) } ?: newDump.identify(entry.name)

        if (candidates.size != 1) return null
        return Elf.vaToFile(Elf.segments(destination), candidates[0].address)
    }

    private fun strategyNote(strategy: Strategy): String? =
        when (strategy) {
            Strategy.EXACT -> null
            Strategy.EXTENDED -> "needed a longer signature"
            Strategy.MULTI_WINDOW -> "pieced together from several windows"
            Strategy.TRAMPOLINE -> "followed a trampoline"
        }

    /** Whether a result counts as settled, for the caller's tally. */
    fun isResolved(result: OffsetResult) = result.status == MatchStatus.RESOLVED
}
