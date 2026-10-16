package app.sunosongwriter

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runners.model.Statement
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    /** Getting started opens by itself on a first launch; these tests start from the library. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as App
                runBlocking { app.container.settings.setGettingStartedShown() }
                base.evaluate()
            }
        }
    }.around(composeRule)

    @Test
    fun libraryIsTheHomeScreen() {
        composeRule.onNodeWithContentDescription("New song").assertExists()
        composeRule.onNodeWithContentDescription("Search songs").assertExists()
    }

    @Test
    fun settingsShowsFreeOnlyModeAndBackendPresets() {
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Free-only mode").assertExists()

        composeRule.onNodeWithText("Backends").performClick()
        composeRule.onNodeWithText("On-device models").assertExists()
        composeRule.onNodeWithText("Coming soon").assertExists()
        composeRule.onNodeWithContentDescription("Add backend").performClick()
        composeRule.onNodeWithText("Ollama (on your computer)").assertExists()
        // Lower presets are lazily composed, so scroll to them.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Claude app (relay)"))
        composeRule.onNodeWithText("Claude app (relay)").assertExists()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Google AI Studio (Gemini)"))
        composeRule.onNodeWithText("Google AI Studio (Gemini)").assertExists()
    }

    @Test
    fun gettingStartedListsTheSetupSteps() {
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Getting started").performClick()
        composeRule.onNodeWithText("Connect a model").assertExists()
        composeRule.onNodeWithText("Add a backend").assertExists()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Log what came back"))
        composeRule.onNodeWithText("Log what came back").assertExists()
    }
}
