package com.swarm.ai.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.swarm.ai.remote.ui.RemoteRoute
import com.swarm.ai.ui.screens.*

object Routes {
    const val HOME = "home"
    const val AGENTS = "agents"
    const val BUILD = "build"
    const val SETTINGS = "settings"
    const val MODELS = "models"
    const val REMOTE = "remote"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Default.Home),
    Tab(Routes.AGENTS, "Agents", Icons.Default.Group),
    Tab(Routes.BUILD, "Build", Icons.Default.Build),
    Tab(Routes.SETTINGS, "Settings", Icons.Default.Settings)
)

@Composable
fun SwarmApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            if (current in tabs.map { it.route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) {
                val vm: HomeViewModel = hiltViewModel()
                val state by vm.uiState.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state,
                    onNavigateToModels = { nav.navigate(Routes.MODELS) },
                    onNavigateToBuild = { nav.navigate(Routes.BUILD) },
                    onRefresh = vm::refresh,
                    onNavigateToRemote = { nav.navigate(Routes.REMOTE) }
                )
            }
            composable(Routes.AGENTS) {
                val vm: AgentsViewModel = hiltViewModel()
                val state by vm.uiState.collectAsStateWithLifecycle()
                AgentsScreen(state.agents, onAddAgent = vm::addAgent, onToggleAgentStatus = vm::toggleAgentStatus)
            }
            composable(Routes.BUILD) {
                val vm: BuildViewModel = hiltViewModel()
                val state by vm.uiState.collectAsStateWithLifecycle()
                BuildScreen(state, onPromptChange = vm::updatePrompt, onExecute = vm::executeTask)
            }
            composable(Routes.SETTINGS) {
                val vm: SettingsViewModel = hiltViewModel()
                val state by vm.uiState.collectAsStateWithLifecycle()
                SettingsScreen(state, onUpdateSettings = vm::updateSettings, onResetDefaults = vm::resetDefaults)
            }
            composable(Routes.MODELS) {
                val vm: ModelsViewModel = hiltViewModel()
                val state by vm.uiState.collectAsStateWithLifecycle()
                ModelsScreen(models = state.models)
            }
            composable(Routes.REMOTE) {
                RemoteRoute(onBack = { nav.popBackStack() })
            }
        }
    }
}