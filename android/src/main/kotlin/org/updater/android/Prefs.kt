package org.updater.android

import android.content.Context
import org.updater.Engine
import org.updater.OutputFormat

/**
 * What the app remembers between launches.
 *
 * Picking two libraries and an offsets file every time is the tedious part of using this, so the
 * choices are kept, along with everything else the form holds. A remembered file that has since
 * been deleted is dropped when it is read back, so the app never starts by pointing at nothing.
 */
class Prefs(context: Context) {
    private val store = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    fun load(): UiState =
        UiState(
            oldLib = PickedFile.parse(store.getString(OLD_LIB, null)),
            newLib = PickedFile.parse(store.getString(NEW_LIB, null)),
            offsetsFile = PickedFile.parse(store.getString(OFFSETS, null)),
            offsetsText = store.getString(OFFSETS_TEXT, "") ?: "",
            oldDump = PickedFile.parse(store.getString(OLD_DUMP, null)),
            newDump = PickedFile.parse(store.getString(NEW_DUMP, null)),
            engine = store.getString(ENGINE, null)?.let(Engine::from) ?: Engine.CLASSIC,
            format = store.getString(FORMAT, null)?.let(OutputFormat::from) ?: OutputFormat.PAIRS,
            sigLen = store.getString(SIG_LEN, "64") ?: "64",
            maxSigLen = store.getString(MAX_SIG_LEN, "1024") ?: "1024",
            maxMatches = store.getString(MAX_MATCHES, "1") ?: "1",
            autoTune = store.getBoolean(AUTO_TUNE, true),
        )

    fun save(state: UiState) {
        store
            .edit()
            .putString(OLD_LIB, state.oldLib?.store())
            .putString(NEW_LIB, state.newLib?.store())
            .putString(OFFSETS, state.offsetsFile?.store())
            .putString(OFFSETS_TEXT, state.offsetsText)
            .putString(OLD_DUMP, state.oldDump?.store())
            .putString(NEW_DUMP, state.newDump?.store())
            .putString(ENGINE, state.engine.id)
            .putString(FORMAT, state.format.id)
            .putString(SIG_LEN, state.sigLen)
            .putString(MAX_SIG_LEN, state.maxSigLen)
            .putString(MAX_MATCHES, state.maxMatches)
            .putBoolean(AUTO_TUNE, state.autoTune)
            .apply()
    }

    private companion object {
        const val OLD_LIB = "oldLib"
        const val NEW_LIB = "newLib"
        const val OFFSETS = "offsets"
        const val OFFSETS_TEXT = "offsetsText"
        const val OLD_DUMP = "oldDump"
        const val NEW_DUMP = "newDump"
        const val ENGINE = "engine"
        const val FORMAT = "format"
        const val SIG_LEN = "sigLen"
        const val MAX_SIG_LEN = "maxSigLen"
        const val MAX_MATCHES = "maxMatches"
        const val AUTO_TUNE = "autoTune"
    }
}
