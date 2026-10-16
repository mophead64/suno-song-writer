package app.sunosongwriter

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun libraryIsTheHomeScreen() {
        composeRule.onNodeWithContentDescription("New song").assertExists()
        composeRule.onNodeWithContentDescription("Search songs").assertExists()
    }

    @Test
    fun settingsShowsFreeOnlyModeAndBackendPresets() {
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Free-only mode").assertExists()
        composeRule.onNodeWithText("On-device models").assertExists()

        composeRule.onNodeWithText("Backends").performClick()
        composeRule.onNodeWithContentDescription("Add backend").performClick()
        composeRule.onNodeWithText("Ollama (on your computer)").assertExists()
        // Lower presets are lazily composed, so scroll to them.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Claude app (relay)"))
        composeRule.onNodeWithText("Claude app (relay)").assertExists()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Google AI Studio (Gemini)"))
        composeRule.onNodeWithText("Google AI Studio (Gemini)").assertExists()
    }
}
