package org.updater.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.updater.Engine
import org.updater.OffsetResult
import org.updater.OutputFormat
import org.updater.android.theme.UpdaterTheme

/**
 * Renders the screens off-device and writes them to build/screenshots, which is the only way to
 * look at the layout from a machine with no Android on it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h915dp-xhdpi")
class ScreenshotTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val filled =
        UiState(
            oldLib =
                PickedFile(
                    path = "/sdcard/old/libil2cpp.so",
                    name = "OLDlibil2cpp.so",
                    bytes = 132L * 1024 * 1024,
                ),
            newLib =
                PickedFile(
                    path = "/sdcard/new/libil2cpp.so",
                    name = "NEWlibil2cpp.so",
                    bytes = 123L * 1024 * 1024,
                ),
            offsetsFile = PickedFile(path = "/sdcard/Offsets.txt", name = "Offsets.txt", bytes = 9),
            format = OutputFormat.PAIRS,
            done = 3,
            total = 3,
            seconds = 7.4,
            results =
                listOf(
                    OffsetResult("0x2209c04", "", listOf("0x29d9828"), 256, tuned = true),
                    OffsetResult("0x3ea393c", "", listOf("0x28256c0"), 64),
                    OffsetResult(
                        "0x3ea3708",
                        "",
                        emptyList(),
                        16,
                        tuned = true,
                        note = "nothing matched at 64 bytes",
                    ),
                    OffsetResult(
                        "0x41f02c0",
                        "",
                        listOf("0x2a1b140", "0x2a1b2c8", "0x2a1b450"),
                        1024,
                        tuned = true,
                    ),
                ),
            warnings = listOf("Line 4: '0X50' hexadecimal prefix must be lowercase, write 0x50"),
        )

    private fun capture(
        name: String,
        dark: Boolean,
        afterLayout: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        // The wavy progress indicator animates forever, so the composition never goes idle and
        // waiting for it times out. Drive the clock by hand and grab a settled frame instead.
        compose.mainClock.autoAdvance = false
        compose.setContent { UpdaterTheme(darkTheme = dark, dynamicColor = false) { content() } }
        compose.mainClock.advanceTimeBy(600)
        afterLayout()
        compose.mainClock.advanceTimeBy(600)

        // Drawing the view hierarchy directly, because Compose's own capture waits for the
        // composition to go idle and the wavy indicator never lets it.
        val view = compose.activity.window.decorView
        if (view.width == 0 || view.height == 0) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
    }

    private fun screen(name: String, dark: Boolean, state: UiState, scroll: Boolean = false) =
        capture(
            name,
            dark,
            afterLayout = {
                if (scroll) {
                    compose.onRoot().performTouchInput {
                        swipeUp(startY = bottom * 0.8f, endY = bottom * 0.2f)
                    }
                }
            },
        ) {
            UpdaterContent(state)
        }

    // ------------------------------------------------------------------ setup

    @Test fun `setup, dark`() = screen("setup-dark", dark = true, state = filled)

    @Test fun `setup, light`() = screen("setup-light", dark = false, state = filled)

    @Test fun `setup empty, dark`() = screen("setup-empty-dark", dark = true, state = UiState())

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun `setup on a small phone, light`() =
        screen("setup-small-light", dark = false, state = filled)

    @Test
    fun `setup with large text, light`() =
        capture("setup-large-text-light", dark = false) {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 1.5f)
            ) {
                UpdaterContent(filled)
            }
        }

    @Test
    fun `setup with options open, light`() =
        screen(
            "setup-options-light",
            dark = false,
            state = filled.copy(showOptions = true),
            scroll = true,
        )

    @Test
    fun `setup on the modern engine, dark`() =
        screen(
            "setup-modern-dark",
            dark = true,
            scroll = true,
            state =
                filled.copy(
                    engine = Engine.MODERN,
                    oldDump =
                        PickedFile(
                            path = "/sdcard/script.json",
                            name = "old-script.json",
                            bytes = 44L * 1024 * 1024,
                        ),
                ),
        )

    @Test
    fun `setup on the nullbit engine, light`() =
        screen(
            "setup-nullbit-light",
            dark = false,
            state = filled.copy(engine = Engine.NULLBIT),
            scroll = true,
        )

    // ------------------------------------------------------------------ results

    @Test
    fun `results, dark`() =
        screen("results-dark", dark = true, state = filled.copy(pane = Pane.RESULTS))

    @Test
    fun `results, light`() =
        screen("results-light", dark = false, state = filled.copy(pane = Pane.RESULTS))

    @Test
    fun `results empty, light`() =
        screen("results-empty-light", dark = false, state = UiState(pane = Pane.RESULTS))

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun `results on a small phone, light`() =
        screen("results-small-light", dark = false, state = filled.copy(pane = Pane.RESULTS))

    @Test
    fun `results with candidates open, dark`() =
        screen(
            "results-candidates-dark",
            dark = true,
            state = filled.copy(pane = Pane.RESULTS, expandedResults = setOf("0x41f02c0")),
        )

    @Test
    fun `results while running, dark`() =
        screen(
            "results-running-dark",
            dark = true,
            state =
                filled.copy(
                    pane = Pane.RESULTS,
                    running = true,
                    done = 1,
                    total = 3,
                    results = filled.results.take(1),
                ),
        )

    // A long list, scrolled, to show that the toolbar keeps Setup and Results either side of
    // Start rather than folding them away.
    @Test
    fun `results scrolled, dark`() {
        val many =
            filled.copy(
                pane = Pane.RESULTS,
                results =
                    (1..24).map {
                        OffsetResult(
                            "0x${(0x2209c04 + it * 0x40).toString(16)}",
                            "",
                            listOf("0x${(0x29d9828 + it * 0x40).toString(16)}"),
                            64,
                        )
                    },
            )

        capture(
            "results-scrolled-dark",
            dark = true,
            afterLayout = {
                compose.onRoot().performTouchInput {
                    swipeUp(startY = bottom * 0.8f, endY = bottom * 0.2f)
                }
            },
        ) {
            UpdaterContent(many)
        }
    }

    // ------------------------------------------------------------------ the file browser

    private val browsing =
        BrowseState(
            target = BrowseFor.OLD_LIBRARY,
            path = "/storage/emulated/0/Download/apk",
            loading = false,
            entries =
                listOf(
                    BrowseEntry("armeabi-v7a", "/x/armeabi-v7a", isDirectory = true),
                    BrowseEntry("arm64-v8a", "/x/arm64-v8a", isDirectory = true),
                    BrowseEntry("lib", "/x/lib", isDirectory = true),
                    BrowseEntry(
                        "libil2cpp.so",
                        "/x/libil2cpp.so",
                        isDirectory = false,
                        bytes = 132L * 1024 * 1024,
                        interesting = true,
                    ),
                    BrowseEntry(
                        "libunity.so",
                        "/x/libunity.so",
                        isDirectory = false,
                        bytes = 17L * 1024 * 1024,
                        interesting = true,
                    ),
                    BrowseEntry(
                        "libmain.so",
                        "/x/libmain.so",
                        isDirectory = false,
                        bytes = 9L * 1024,
                        interesting = true,
                    ),
                ),
        )

    @Test fun `browser, dark`() = capture("browser-dark", dark = true) { FileBrowser(browsing) }

    @Test fun `browser, light`() = capture("browser-light", dark = false) { FileBrowser(browsing) }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun `browser on a small phone, light`() =
        capture("browser-small-light", dark = false) { FileBrowser(browsing) }

    // The launcher icon, drawn as the system composes it: the foreground over its background.
    @Test
    fun `app icon`() =
        capture("app-icon", dark = true) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.size(108.dp)
                    .background(androidx.compose.ui.graphics.Color(0xFF1B2430)),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter =
                        androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = androidx.compose.ui.Modifier.size(108.dp),
                )
            }
        }

    @Test
    fun `browser without permission, dark`() =
        capture("browser-denied-dark", dark = true) {
            FileBrowser(browsing.copy(denied = true, entries = emptyList()))
        }
}
