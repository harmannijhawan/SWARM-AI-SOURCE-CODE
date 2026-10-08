package com.swarm.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swarm.ai.model.Agent
import com.swarm.ai.model.AiModel
import com.swarm.ai.model.SwarmSettings
import com.swarm.ai.ui.screens.*
import com.swarm.ai.ui.theme.SwarmAITheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiScreensInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testHomeScreenUi() {
        val state = HomeUiState(
            activeAgentsCount = 3,
            completedTasksCount = 12,
            systemHealth = 98,
            selectedModelName = "Llama 3 8B",
            recentLogs = listOf("Agent initialized", "Task completed")
        )

        composeTestRule.setContent {
            SwarmAITheme {
                HomeScreen(
                    state = state,
                    onNavigateToModels = {},
                    onNavigateToBuild = {},
                    onRefresh = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Swarm Overview").assertExists()
        composeTestRule.onNodeWithText("Active Agents").assertExists()
        composeTestRule.onNodeWithText("3").assertExists()
        composeTestRule.onNodeWithText("Llama 3 8B").assertExists()
    }

    @Test
    fun testAgentsScreenUi() {
        val agents = listOf(
            Agent(id = "1", name = "PlannerAgent", role = "Planner", status = "Active", currentTask = "Planning swarm topology"),
            Agent(id = "2", name = "WorkerAgent", role = "Worker", status = "Idle")
        )

        composeTestRule.setContent {
            SwarmAITheme {
                AgentsScreen(
                    agents = agents,
                    onAddAgent = {},
                    onToggleAgentStatus = {}
                )
            }
        }

        composeTestRule.onNodeWithText("PlannerAgent").assertExists()
        composeTestRule.onNodeWithText("WorkerAgent").assertExists()
        composeTestRule.onNodeWithText("Planner").assertExists()
    }

    @Test
    fun testSettingsScreenUi() {
        val state = SettingsUiState(
            selectedModelId = "m1",
            localInferenceEnabled = true,
            temperature = 0.7f,
            maxTokens = 2048,
            ollamaEndpoint = "http://localhost:11434"
        )

        composeTestRule.setContent {
            SwarmAITheme {
                SettingsScreen(
                    state = state,
                    onUpdateSettings = {},
                    onResetDefaults = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Settings").assertExists()
        composeTestRule.onNodeWithText("Local TFLite Inference").assertExists()
    }
}
