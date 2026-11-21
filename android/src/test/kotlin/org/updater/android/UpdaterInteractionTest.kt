package org.updater.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.updater.Engine
import org.updater.OffsetResult
import org.updater.android.theme.UpdaterTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
class UpdaterInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val ready =
        UiState(
            oldLib = PickedFile(path = "/old.so", name = "old.so", bytes = 1),
            newLib = PickedFile(path = "/new.so", name = "new.so", bytes = 1),
            offsetsText = "0x1000",
        )

    @Test
    fun `toolbar preserves navigation and start gating on a small phone`() {
        var state by mutableStateOf(UiState())
        var starts = 0
        compose.setContent {
            UpdaterTheme(dynamicColor = false) {
                UpdaterContent(
                    state,
                    ScreenActions(setPane = { state = state.copy(pane = it) }, start = { starts++ }),
                )
            }
        }
        compose.onNodeWithText("Start").assertIsNotEnabled()
        compose.onNodeWithText("Results").performClick().assertIsSelected()
        compose.onNodeWithText("Your results start here").assertIsDisplayed()
        compose.onNodeWithText("Go to Setup").performClick()
        compose.onNodeWithText("Setup").assertIsSelected()
        compose.runOnIdle { state = ready }
        compose.onNodeWithText("Start").assertIsEnabled().performClick()
        assertEquals(1, starts)
    }

    @Test
    fun `engine selection still updates the analysis configuration`() {
        var state by mutableStateOf(ready)
        compose.setContent {
            UpdaterTheme(dynamicColor = false) {
                UpdaterContent(
                    state,
                    ScreenActions(setEngine = { state = state.copy(engine = it) }),
                )
            }
        }
        compose.onNodeWithTag("setup-list").performScrollToNode(hasText("Enhanced"))
        compose.onNodeWithText("Enhanced").performClick()
        compose.runOnIdle { assertEquals(Engine.MODERN, state.engine) }
    }

    @Test
    fun `results expose working copy share and individual offset actions`() {
        var copies = 0
        var shares = 0
        var copiedOffset = ""
        val state =
            ready.copy(
                pane = Pane.RESULTS,
                results = listOf(OffsetResult("0x1000", "", listOf("0x2000"), 64)),
            )
        compose.setContent {
            UpdaterTheme(dynamicColor = false) {
                UpdaterContent(
                    state,
                    ScreenActions(
                        copy = { copies++ },
                        share = { shares++ },
                        copyText = { copiedOffset = it },
                    ),
                )
            }
        }
        compose.onNodeWithTag("results-list").performScrollToNode(hasContentDescription("Copy"))
        compose.onNodeWithContentDescription("Copy").performClick()
        compose.onNodeWithContentDescription("Share").performClick()
        compose.onNodeWithTag("results-list").performScrollToNode(hasText("0x2000"))
        compose.onNodeWithText("0x2000").performClick()
        compose.runOnIdle {
            assertEquals(1, copies)
            assertEquals(1, shares)
            assertEquals("0x2000", copiedOffset)
        }
    }

    @Test
    fun `navigation floats over the list and the last settings remain reachable`() {
        compose.setContent { UpdaterTheme(dynamicColor = false) { UpdaterContent(ready) } }
        compose.onNodeWithTag("setup-list").performScrollToNode(hasText("Advanced settings"))
        val dock = compose.onNodeWithTag("floating-navigation").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("setup-list").fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithText("Advanced settings").fetchSemanticsNode().boundsInRoot
        assertTrue("Dock has horizontal margins", dock.left > list.left && dock.right < list.right)
        assertTrue("Content continues behind the dock", list.bottom > dock.bottom)
        assertTrue("Last setting scrolls above the dock", settings.bottom <= dock.top)
    }

    @Test
    fun `diagnostics expand without burying the results`() {
        compose.setContent {
            UpdaterTheme(dynamicColor = false) {
                UpdaterContent(ready.copy(pane = Pane.RESULTS, warnings = listOf("Check line 4")))
            }
        }
        compose.onNodeWithText("Check line 4").assertDoesNotExist()
        compose.onNodeWithTag("results-list").performScrollToNode(hasText("1 diagnostic"))
        compose.onNodeWithText("1 diagnostic").performClick()
        compose.onNodeWithText("Check line 4").assertIsDisplayed()
        compose.onNodeWithText("1 diagnostic").performClick()
        compose.onNodeWithText("Check line 4").assertDoesNotExist()
    }

    @Test
    fun `stop stays available from setup while an update runs`() {
        var stops = 0
        compose.setContent {
            UpdaterTheme(dynamicColor = false) {
                UpdaterContent(ready.copy(running = true), ScreenActions(stop = { stops++ }))
            }
        }
        compose.onNodeWithText("Stop").assertIsEnabled().performClick()
        assertEquals(1, stops)
    }
}
