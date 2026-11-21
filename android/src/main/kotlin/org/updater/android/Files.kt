package org.updater.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import java.io.File

/**
 * A file the run will read.
 *
 * Either a path, from the built-in browser, or a content URI from the system picker. A path is
 * worth preferring: a 130 MB library read straight off the filesystem costs nothing, where the same
 * file through a picker is streamed and copied.
 */
data class PickedFile(
    val uri: Uri? = null,
    val path: String? = null,
    val name: String,
    val bytes: Long,
) {
    /** What is written to preferences, and parsed back by [parse]. */
    fun store(): String = "${path ?: ""}\n${uri ?: ""}\n$name\n$bytes"

    companion object {
        fun parse(stored: String?): PickedFile? {
            val parts = stored?.split("\n") ?: return null
            if (parts.size != 4) return null

            val path = parts[0].takeIf { it.isNotBlank() }
            val uri = parts[1].takeIf { it.isNotBlank() }?.let(Uri::parse)
            if (path == null && uri == null) return null
            // A remembered path that has since been deleted should not come back as a choice.
            if (path != null && !File(path).isFile) return null

            return PickedFile(uri, path, parts[2], parts[3].toLongOrNull() ?: 0L)
        }

        fun of(file: File) =
            PickedFile(path = file.absolutePath, name = file.name, bytes = file.length())

        /**
         * Describes a file the system picker returned, asking the provider for its name and size.
         */
        fun of(context: Context, uri: Uri): PickedFile {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            var size = 0L

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor
                        .getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }
                        ?.let { name = cursor.getString(it) ?: name }
                    cursor
                        .getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 }
                        ?.let { size = cursor.getLong(it) }
                }
            }

            return PickedFile(uri = uri, name = name, bytes = size)
        }
    }
}

/** One line of the browser: a folder, or a file with its size. */
data class BrowseEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val bytes: Long = 0,
    /** Set on a file whose extension is what the browser was opened to find. */
    val interesting: Boolean = false,
)

/** What the browser was opened to find, which decides what it points out. */
enum class BrowseFor(
    @StringRes val title: Int,
    @StringRes val hint: Int,
    val extensions: List<String>,
) {
    OLD_LIBRARY(R.string.file_old_library, R.string.file_old_library_hint, listOf("so")),
    NEW_LIBRARY(R.string.file_new_library, R.string.file_new_library_hint, listOf("so")),
    OFFSETS(R.string.file_offsets, R.string.file_offsets_hint, listOf("txt", "csv", "log")),
    OLD_DUMP(R.string.file_old_dump, R.string.file_old_dump_hint, listOf("json")),
    NEW_DUMP(R.string.file_new_dump, R.string.file_new_dump_hint, listOf("json"));

    /** The extensions as the browser lists them, for whichever sentence is asking. */
    fun extensions(separator: String) = extensions.joinToString(separator)
}

/** Everything the browser screen needs, held by the view model so it survives rotation. */
data class BrowseState(
    val target: BrowseFor,
    val path: String = Storage.root,
    val entries: List<BrowseEntry> = emptyList(),
    val showAll: Boolean = false,
    val loading: Boolean = true,
    val denied: Boolean = false,
)

/** The filesystem, as this app is allowed to see it. */
object Storage {
    val root: String
        get() = Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"

    /** Somewhere to start that is likely to hold what was asked for. */
    fun shortcuts(target: BrowseFor): List<Pair<Int, String>> {
        val places = buildList {
            add(R.string.browser_place_storage to root)
            add(R.string.browser_place_download to "$root/Download")
            add(R.string.browser_place_documents to "$root/Documents")
            if (target == BrowseFor.OFFSETS || target == BrowseFor.OLD_DUMP) {
                add(R.string.browser_place_dumpdroid to "$root/DumpDroid")
            }
        }

        return places.filter { File(it.second).isDirectory }
    }

    fun canRead(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** The settings page that grants access to every file, which is what a browser needs. */
    fun accessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
        }

    /**
     * One directory, folders first and then files, alphabetically within each.
     *
     * Files whose extension is what was asked for come before the rest, and with [showAll] off the
     * rest are left out entirely.
     */
    fun list(path: String, target: BrowseFor, showAll: Boolean): List<BrowseEntry> {
        val children = File(path).listFiles() ?: return emptyList()

        val folders =
            children
                .filter { it.isDirectory && !it.isHidden }
                .sortedBy { it.name.lowercase() }
                .map { BrowseEntry(it.name, it.absolutePath, isDirectory = true) }

        val files =
            children
                .filter { it.isFile && !it.isHidden }
                .map {
                    BrowseEntry(
                        name = it.name,
                        path = it.absolutePath,
                        isDirectory = false,
                        bytes = it.length(),
                        interesting = it.extension.lowercase() in target.extensions,
                    )
                }
                .filter { showAll || it.interesting }
                .sortedWith(
                    compareByDescending<BrowseEntry> { it.interesting }
                        .thenBy { it.name.lowercase() }
                )

        return folders + files
    }

    /** The path split into the pieces a breadcrumb row shows, each with the path it leads to. */
    fun crumbs(path: String): List<Pair<String, String>> {
        val base = root
        if (!path.startsWith(base)) {
            return listOf("/" to "/") +
                path
                    .trim('/')
                    .split("/")
                    .filter { it.isNotEmpty() }
                    .runningFold("" to "") { (_, parent), name -> name to "$parent/$name" }
                    .drop(1)
        }

        val rest = path.removePrefix(base).trim('/')
        val tail =
            if (rest.isEmpty()) emptyList()
            else
                rest
                    .split("/")
                    .runningFold("" to base) { (_, parent), name -> name to "$parent/$name" }
                    .drop(1)

        return listOf(STORAGE_CRUMB to base) + tail
    }

    fun parentOf(path: String): String? = File(path).parentFile?.absolutePath

    /** The stand-in the first crumb carries, which the row swaps for the word for "Storage". */
    const val STORAGE_CRUMB = "\u0000storage"
}
