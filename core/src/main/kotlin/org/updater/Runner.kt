package org.updater

import capstone.Capstone
import capstone.api.Disassembler
import capstone.api.DisassemblerFactory
import java.io.File
import org.updater.capstone.DirectCapstone
import org.updater.modern.Metadata
import org.updater.modern.ModernEngine
import org.updater.nullbit.NullUpdaterEngine
import org.updater.utils.FileHandler
import org.updater.utils.OffsetFileParser

/** Something about the inputs that stops a run before it starts. */
class UpdateError(message: String) : Exception(message)

/** Which matching engine to run. */
enum class Engine(val id: String, val aliases: List<String> = emptyList()) {
    /** The original: whole-instruction wildcards, one signature length tuned per offset. */
    CLASSIC("classic"),
    /**
     * The upstream rewrite, ported: nibble-level masks, signatures grown from 48 bytes, and
     * fallbacks through method tables, the call graph and vtables when bytes alone will not do.
     */
    MODERN("modern"),
    /**
     * NullUpdater, ported: one signature per function, from its first instruction to its `ret`,
     * masked by byte, with the window moved rather than the mask when it matches too much.
     */
    NULLBIT("nullupdater", aliases = listOf("null", "nullbit"));

    companion object {
        fun from(value: String): Engine? {
            val wanted = value.trim()
            return entries.firstOrNull { engine ->
                engine.id.equals(wanted, ignoreCase = true) ||
                    engine.aliases.any { it.equals(wanted, ignoreCase = true) }
            }
        }

        val ids: String
            get() = entries.joinToString("|") { it.id }
    }
}

data class RunRequest(
    val oldLib: String,
    val newLib: String,
    val offsetFile: String,
    val outputFile: String?,
    val format: OutputFormat,
    val match: MatchSettings,
    val engine: Engine = Engine.CLASSIC,
    /**
     * Il2CppDumper `script.json` for the old library. Optional, and only the modern engine uses it.
     */
    val oldDump: String? = null,
    /** The same for the new library, which can name where a method went outright. */
    val newDump: String? = null,
)

data class RunSummary(
    val results: List<OffsetResult>,
    val warnings: List<String>,
    val seconds: Double,
    val stopped: Boolean,
) {
    val counts: Map<MatchStatus, Int>
        get() = results.groupingBy { it.status }.eachCount()
}

/**
 * Resolves every offset in [request] against the new library.
 *
 * [onResult] is called as each offset finishes, and [keepGoing] is checked between offsets so a
 * caller can stop a long run. Inputs that cannot be used raise [UpdateError] rather than ending the
 * process, so the same engine serves the command line and the web UI.
 */
fun runUpdate(
    request: RunRequest,
    onWarnings: (List<String>) -> Unit = {},
    onResult: (done: Int, total: Int, result: OffsetResult) -> Unit = { _, _, _ -> },
    keepGoing: () -> Boolean = { true },
): RunSummary {
    val startTime = System.currentTimeMillis()
    val parsed = readOffsets(request.offsetFile)
    val warnings =
        parsed.problems.map {
            "${File(request.offsetFile).name} line ${it.line}: '${it.token}' ${it.reason}"
        }
    onWarnings(warnings)

    val oldBytes = readLibrary(request.oldLib, "old library")
    val newBytes = readLibrary(request.newLib, "new library")

    request.outputFile?.let { FileHandler.writeFile(it, "") }

    val report = { done: Int, total: Int, result: OffsetResult ->
        request.outputFile?.let {
            FileHandler.appendToFile(it, formatResult(result, request.format))
        }
        onResult(done, total, result)
    }

    val results =
        resolveOffsets(
            oldBytes = oldBytes,
            newBytes = newBytes,
            entries = parsed.entries,
            engine = request.engine,
            settings = request.match,
            oldDump = request.oldDump?.let { readDump(it) },
            newDump = request.newDump?.let { readDump(it) },
            onResult = report,
            keepGoing = keepGoing,
        )

    return RunSummary(
        results = results,
        warnings = warnings,
        seconds = (System.currentTimeMillis() - startTime) / 1000.0,
        stopped = results.size < parsed.offsets.size,
    )
}

/**
 * Runs [engine] over libraries already in memory.
 *
 * The one place the two engines are chosen between, so the command line, the web UI and the Android
 * app all get the same behaviour from the same switch. [runUpdate] is this with files read and a
 * report written; the Android app calls it directly, having read the bytes through the system file
 * picker, which gives it no path to open.
 */
fun resolveOffsets(
    oldBytes: ByteArray,
    newBytes: ByteArray,
    entries: List<OffsetFileParser.Entry>,
    engine: Engine,
    settings: MatchSettings,
    oldDump: Metadata? = null,
    newDump: Metadata? = null,
    onResult: (done: Int, total: Int, result: OffsetResult) -> Unit = { _, _, _ -> },
    keepGoing: () -> Boolean = { true },
): List<OffsetResult> =
    when (engine) {
        Engine.CLASSIC ->
            resolveAll(
                oldBytes = oldBytes,
                newBytes = newBytes,
                offsets = entries.map { it.offset },
                settings = settings,
                onResult = onResult,
                keepGoing = keepGoing,
            )
        Engine.MODERN ->
            ModernEngine.run(
                source = oldBytes,
                destination = newBytes,
                entries = namedEntries(entries),
                settings =
                    ModernEngine.Settings(
                        // The modern engine grows a signature rather than tuning one, so the
                        // ceiling is what bounds it.
                        signatureLength = settings.maxSigLen,
                        useFallbacks = true,
                        oldDump = oldDump,
                        newDump = newDump,
                    ),
                onResult = onResult,
                keepGoing = keepGoing,
            )
        Engine.NULLBIT ->
            NullUpdaterEngine.run(
                source = oldBytes,
                destination = newBytes,
                offsets = entries.map { it.offset },
                settings = NullUpdaterEngine.Settings(maxMatches = settings.maxMatches),
                onResult = onResult,
                keepGoing = keepGoing,
            )
    }

/**
 * Resolves [offsets] against libraries already in memory.
 *
 * This is the whole engine; [runUpdate] is the version that reads files and writes a report. An
 * Android app hands the bytes over directly, having read them through the system file picker, which
 * gives it no path to open.
 */
fun resolveAll(
    oldBytes: ByteArray,
    newBytes: ByteArray,
    offsets: List<Int>,
    settings: MatchSettings,
    onResult: (done: Int, total: Int, result: OffsetResult) -> Unit = { _, _, _ -> },
    keepGoing: () -> Boolean = { true },
): List<OffsetResult> {
    val disassembler = createArm64Disassembler()
    val results = mutableListOf<OffsetResult>()

    try {
        for ((index, offset) in offsets.withIndex()) {
            if (!keepGoing()) break

            val result = resolveOffset(disassembler, oldBytes, newBytes, offset, settings)
            results.add(result)
            onResult(index + 1, offsets.size, result)
        }
    } finally {
        disassembler.close()
    }

    return results
}

/**
 * Opens Capstone, preferring the binding whose instruction numbering the wildcard rules expect.
 *
 * The bundled factory prefers a JNI backend, and that backend numbers instructions differently from
 * the `Arm64_const` values [org.updater.utils.insnIsWildCard] compares against: it calls `bl` 46
 * and `adrp` 13 where the constants say 21 and 9. Nothing errors, the rules simply never match, and
 * every signature comes out fully concrete and far more brittle. The JNA binding agrees with the
 * constants, so it goes first.
 *
 * [DirectCapstone] covers Android, where the JNA binding cannot find its library at all.
 */
internal fun createArm64Disassembler(): Disassembler {
    val disassembler =
        runCatching { Capstone(Capstone.CS_ARCH_ARM64, Capstone.CS_MODE_ARM) as Disassembler }
            .recoverCatching { DirectCapstone(Capstone.CS_ARCH_ARM64, Capstone.CS_MODE_ARM) }
            .recoverCatching {
                DisassemblerFactory.createDisassembler(Capstone.CS_ARCH_ARM64, Capstone.CS_MODE_ARM)
            }
            .getOrThrow()

    return disassembler.apply { setDetail(false) }
}

/**
 * Names for the modern engine, made unique.
 *
 * Its fallbacks index methods by name, so two lines sharing a comment would otherwise collide and
 * one would be lost.
 */
private fun namedEntries(entries: List<OffsetFileParser.Entry>): List<ModernEngine.Entry> {
    val used = mutableMapOf<String, Int>()
    return entries.map { entry ->
        val seen = used.merge(entry.name, 1, Int::plus)!!
        val name = if (seen == 1) entry.name else "${entry.name} #$seen"
        ModernEngine.Entry(entry.offset, name)
    }
}

private fun readDump(path: String): Metadata? {
    val file = File(path)
    if (!file.isFile) throw UpdateError("Cannot read the dump: $path")
    return Metadata.parse(file.readText())
        ?: throw UpdateError("No ScriptMethod entries in the dump: $path")
}

/** The results rendered in [format], as the output file would hold them. */
fun renderResults(results: List<OffsetResult>, format: OutputFormat): String =
    results.joinToString("\n") { formatResult(it, format) }

private fun readOffsets(path: String): OffsetFileParser.Result {
    val parsed =
        try {
            OffsetFileParser.parse(path)
        } catch (e: IllegalArgumentException) {
            throw UpdateError(e.message ?: "Could not read $path")
        }

    if (parsed.offsets.isEmpty()) {
        throw UpdateError("No offsets could be read from $path")
    }

    return parsed
}

private fun readLibrary(path: String, what: String): ByteArray {
    val file = File(path)
    if (!file.isFile) throw UpdateError("Cannot read the $what: $path")

    val bytes = FileHandler.readFileAsByteArr(path)
    if (bytes.isEmpty()) throw UpdateError("The $what is empty: $path")

    return bytes
}
