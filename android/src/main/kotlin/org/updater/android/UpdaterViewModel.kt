package org.updater.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.updater.Engine
import org.updater.MatchSettings
import org.updater.MatchStatus
import org.updater.OffsetResult
import org.updater.OutputFormat
import org.updater.modern.Metadata
import org.updater.renderResults
import org.updater.resolveOffsets
import org.updater.utils.OffsetFileParser

/** Which half of the app is on screen. Not named Tab, which Material already uses. */
enum class Pane {
    SETUP,
    RESULTS,
}

data class UiState(
    val oldLib: PickedFile? = null,
    val newLib: PickedFile? = null,
    val offsetsFile: PickedFile? = null,
    val offsetsText: String = "",
    val engine: Engine = Engine.CLASSIC,
    val oldDump: PickedFile? = null,
    val newDump: PickedFile? = null,
    val sigLen: String = "64",
    val maxSigLen: String = "1024",
    val maxMatches: String = "1",
    val autoTune: Boolean = true,
    val format: OutputFormat = OutputFormat.PAIRS,
    val pane: Pane = Pane.SETUP,
    val showOptions: Boolean = false,
    val browse: BrowseState? = null,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val seconds: Double = 0.0,
    val results: List<OffsetResult> = emptyList(),
    val warnings: List<String> = emptyList(),
    val message: String? = null,
    val filter: ResultFilter = ResultFilter.ALL,
    /** The offsets whose candidate list is open, by their old address. */
    val expandedResults: Set<String> = emptySet(),
) {
    val counts: Map<MatchStatus, Int>
        get() = results.groupingBy { it.status }.eachCount()

    /** The results the list shows, which is all of them until a filter chip says otherwise. */
    val shownResults: List<OffsetResult>
        get() = results.filter(filter::matches)

    val canStart: Boolean
        get() =
            !running &&
                oldLib != null &&
                newLib != null &&
                (offsetsFile != null || offsetsText.isNotBlank())
}

class UpdaterViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = Prefs(application)
    private val _state = MutableStateFlow(prefs.load())
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile private var cancelled = false

    /** Every change goes through here, so nothing can be changed without being remembered. */
    private fun edit(block: (UiState) -> UiState) {
        prefs.save(_state.updateAndGet(block))
    }

    // ------------------------------------------------------------------ choosing files

    fun browse(target: BrowseFor) {
        val denied = !Storage.canRead(getApplication())
        val start = Storage.shortcuts(target).firstOrNull()?.second ?: Storage.root
        _state.update {
            it.copy(browse = BrowseState(target = target, path = start, denied = denied))
        }
        if (!denied) open(start)
    }

    fun open(path: String) {
        val current = _state.value.browse ?: return
        _state.update { it.copy(browse = current.copy(path = path, loading = true)) }

        viewModelScope.launch {
            val entries =
                withContext(Dispatchers.IO) { Storage.list(path, current.target, current.showAll) }
            _state.update { state ->
                val browse = state.browse ?: return@update state
                if (browse.path != path) state
                else state.copy(browse = browse.copy(entries = entries, loading = false))
            }
        }
    }

    fun up() {
        val browse = _state.value.browse ?: return
        Storage.parentOf(browse.path)?.let(::open)
    }

    fun showAllFiles(value: Boolean) {
        val browse = _state.value.browse ?: return
        _state.update { it.copy(browse = browse.copy(showAll = value)) }
        open(browse.path)
    }

    /** Called when the permission screen has been visited, to pick the browsing back up. */
    fun recheckAccess() {
        val browse = _state.value.browse ?: return
        if (browse.denied && Storage.canRead(getApplication())) {
            _state.update { it.copy(browse = browse.copy(denied = false)) }
            open(browse.path)
        }
    }

    fun closeBrowser() = _state.update { it.copy(browse = null) }

    fun choose(path: String) {
        val target = _state.value.browse?.target ?: return
        accept(target, PickedFile.of(File(path)))
        closeBrowser()
    }

    /** A file from the system picker, for anything the browser cannot reach. */
    fun choose(target: BrowseFor, uri: Uri) {
        accept(target, PickedFile.of(getApplication(), uri))
        closeBrowser()
    }

    private fun accept(target: BrowseFor, file: PickedFile) = edit {
        when (target) {
            BrowseFor.OLD_LIBRARY -> it.copy(oldLib = file, message = null)
            BrowseFor.NEW_LIBRARY -> it.copy(newLib = file, message = null)
            BrowseFor.OFFSETS -> it.copy(offsetsFile = file, message = null)
            BrowseFor.OLD_DUMP -> it.copy(oldDump = file, message = null)
            BrowseFor.NEW_DUMP -> it.copy(newDump = file, message = null)
        }
    }

    fun clear(target: BrowseFor) = edit {
        when (target) {
            BrowseFor.OLD_LIBRARY -> it.copy(oldLib = null)
            BrowseFor.NEW_LIBRARY -> it.copy(newLib = null)
            BrowseFor.OFFSETS -> it.copy(offsetsFile = null)
            BrowseFor.OLD_DUMP -> it.copy(oldDump = null)
            BrowseFor.NEW_DUMP -> it.copy(newDump = null)
        }
    }

    // ------------------------------------------------------------------ the form

    fun setOffsetsText(text: String) = edit { it.copy(offsetsText = text) }

    fun setSigLen(value: String) = edit { it.copy(sigLen = value.filter(Char::isDigit)) }

    fun setMaxSigLen(value: String) = edit { it.copy(maxSigLen = value.filter(Char::isDigit)) }

    fun setMaxMatches(value: String) = edit { it.copy(maxMatches = value.filter(Char::isDigit)) }

    fun setAutoTune(value: Boolean) = edit { it.copy(autoTune = value) }

    fun setFormat(value: OutputFormat) = edit { it.copy(format = value) }

    fun setEngine(value: Engine) = edit { it.copy(engine = value, message = null) }

    fun setPane(value: Pane) = _state.update { it.copy(pane = value) }

    fun setFilter(value: ResultFilter) = _state.update { it.copy(filter = value) }

    fun toggleResult(offset: String) =
        _state.update {
            val open = it.expandedResults
            it.copy(expandedResults = if (offset in open) open - offset else open + offset)
        }

    fun showOptions(value: Boolean) = _state.update { it.copy(showOptions = value) }

    fun stop() {
        cancelled = true
    }

    fun resultsAsText(): String = renderResults(_state.value.results, _state.value.format)

    // ------------------------------------------------------------------ the run

    fun start() {
        val current = _state.value
        val oldLib = current.oldLib ?: return
        val newLib = current.newLib ?: return

        cancelled = false
        _state.update {
            it.copy(
                running = true,
                pane = Pane.RESULTS,
                results = emptyList(),
                warnings = emptyList(),
                filter = ResultFilter.ALL,
                expandedResults = emptySet(),
                done = 0,
                total = 0,
                seconds = 0.0,
                message = null,
            )
        }

        viewModelScope.launch(Dispatchers.Default) {
            val startedAt = System.currentTimeMillis()
            try {
                val text =
                    current.offsetsFile?.let { String(read(it), Charsets.UTF_8) }
                        ?: current.offsetsText
                val parsed = OffsetFileParser.parseText(text)

                if (parsed.offsets.isEmpty()) {
                    finish(startedAt, say(R.string.error_no_offsets))
                    return@launch
                }

                _state.update {
                    it.copy(
                        total = parsed.offsets.size,
                        warnings =
                            parsed.problems.map { p ->
                                say(R.string.warning_line, p.line, p.token, p.reason)
                            },
                    )
                }

                val sigLen = current.sigLen.toIntOrNull()?.takeIf { it > 0 } ?: 64
                val maxSigLen =
                    maxOf(current.maxSigLen.toIntOrNull()?.takeIf { it > 0 } ?: 1024, sigLen)
                val oldBytes = read(oldLib)
                val newBytes = read(newLib)

                resolveOffsets(
                    oldBytes = oldBytes,
                    newBytes = newBytes,
                    entries = parsed.entries,
                    engine = current.engine,
                    settings =
                        MatchSettings(
                            sigLen = sigLen,
                            maxSigLen = maxSigLen,
                            autoTune = current.autoTune,
                            maxMatches = current.maxMatches.toIntOrNull()?.takeIf { it > 0 } ?: 1,
                        ),
                    oldDump = current.oldDump?.let { readDump(it) },
                    newDump = current.newDump?.let { readDump(it) },
                    onResult = { done, total, result ->
                        _state.update {
                            it.copy(done = done, total = total, results = it.results + result)
                        }
                    },
                    keepGoing = { !cancelled },
                )

                finish(startedAt, if (cancelled) say(R.string.message_stopped) else null)
            } catch (e: OutOfMemoryError) {
                finish(startedAt, say(R.string.error_out_of_memory))
            } catch (e: Throwable) {
                finish(startedAt, NativeLibrary.explain(getApplication(), e))
            }
        }
    }

    private fun finish(startedAt: Long, message: String?) {
        _state.update {
            it.copy(
                running = false,
                seconds = (System.currentTimeMillis() - startedAt) / 1000.0,
                message = message,
            )
        }
    }

    /** A dump the modern engine can use, or null with a warning when it holds no methods. */
    private fun readDump(file: PickedFile): Metadata? {
        val parsed = Metadata.parse(String(read(file), Charsets.UTF_8))
        if (parsed == null) {
            _state.update {
                it.copy(warnings = it.warnings + say(R.string.warning_dump_empty, file.name))
            }
        }
        return parsed
    }

    /** A line of text from the resources, which is where every word the app says lives. */
    private fun say(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun read(file: PickedFile): ByteArray {
        file.path?.let {
            return File(it).readBytes()
        }

        val uri =
            file.uri ?: throw IllegalStateException(say(R.string.error_nothing_to_read, file.name))
        return getApplication<Application>().contentResolver.openInputStream(uri)?.use {
            it.readBytes()
        } ?: throw IllegalStateException(say(R.string.error_cannot_open, file.name))
    }
}
