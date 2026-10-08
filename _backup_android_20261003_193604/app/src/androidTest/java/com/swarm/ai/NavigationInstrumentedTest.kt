package com.swarm.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationInstrumentedTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testAppLaunchAndNavigation() {
        // Verify onboarding or home screen is displayed
        composeTestRule.waitForIdle()
        
        // If onboarding is shown, complete it
        val nextButton = composeTestRule.onNodeWithText("Get Started", ignoreCase = true)
        if (nextButton.fetchSemanticsNodeOrNull() != null) {
            nextButton.performClick()
            composeTestRule.onNodeWithText("Continue", ignoreCase = true).performClick()
            composeTestRule.onNodeWithText("Launch Swarm", ignoreCase = true).performClick()
        }

        // Verify Home screen bottom nav items
        composeTestRule.onNodeWithText("Home").assertExists()
        composeTestRule.onNodeWithText("Agents").assertExists()
        composeTestRule.onNodeWithText("Build").assertExists()
        composeTestRule.onNodeWithText("Settings").assertExists()

        // Navigate to Agents
        composeTestRule.onNodeWithText("Agents").performClick()
        composeTestRule.waitForIdle()

        // Navigate to Build
        composeTestRule.onNodeWithText("Build").performClick()
        composeTestRule.waitForIdle()

        // Navigate to Settings
        composeTestRule.onNodeWithText("Settings").performClick()
        composeTestRule.waitForIdle()
    }
}
