package com.swarm.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.*
import com.swarm.ai.remote.ui.*
import com.swarm.ai.ui.companion.*
import com.swarm.ai.ui.screens.*

object Routes {
    const val HOME = "home"; const val CHAT = "chat"; const val SWARM = "swarm"; const val REMOTE = "remote"
    const val AGENTS = "agents"; const val SETTINGS = "settings"; const val BUILD = "build"; const val MODELS = "models"
}

@Composable
fun SwarmApp() {
    val remote: RemoteViewModel = hiltViewModel()
    val companion: CompanionViewModel = hiltViewModel()
    val settings: SettingsViewModel = hiltViewModel()
    val link by remote.state.collectAsStateWithLifecycle()
    val state by companion.state.collectAsStateWithLifecycle()
    val local by settings.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(link.paired) {
        if (link.paired) { remote.startLive(); companion.start() }
        onStopOrDispose { remote.stopLive(); companion.stop() }
    }
    LaunchedEffect(link.paired) { if (!link.paired) companion.clear() }
    CompanionShell(state, link, local, companion::prompt, companion::platform, companion::build, companion::control,
        companion::send, companion::stopResponse, companion::selectRun, companion::selectChat, companion::preference,
        settings::updateSettings, remote::pair, { remote.stopLive(); remote.startLive(); remote.refresh() },
        remote::unpair, companion::dismissError)
}

@Composable
fun CompanionShell(
    state: CompanionState, link: RemoteUiState, local: com.swarm.ai.ui.screens.SettingsUiState,
    onPrompt: (String) -> Unit, onPlatform: (String) -> Unit, onBuild: () -> Unit, onControl: (String) -> Unit,
    onSend: (String) -> Unit, onStop: () -> Unit, onRun: (String) -> Unit, onChat: (String, String?) -> Unit,
    onPreference: (Boolean, Boolean) -> Unit, onLocal: (com.swarm.ai.ui.screens.SettingsUiState) -> Unit,
    onPair: (String) -> Unit, onReconnect: () -> Unit, onDisconnect: () -> Unit, onDismissError: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(Routes.HOME) }
    var agentId by rememberSaveable { mutableStateOf<String?>(null) }
    var runs by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var models by rememberSaveable { mutableStateOf(false) }
    val tabState = rememberSaveableStateHolder()
    val haptics = LocalHapticFeedback.current
    val online = link.link in listOf(LinkMode.LIVE, LinkMode.POLLING)
    val changeTab: (String) -> Unit = { route -> if (state.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); tab = route }
    BackHandler(link.paired && tab != Routes.HOME) { tab = Routes.HOME }
    CompositionLocalProvider(LocalMotion provides state.motion) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (link.paired) Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Brand() }
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).clickable { tab = Routes.SETTINGS }) { StatusPill(linkLabel(link), online) }
                    IconButton(onClick = { tab = Routes.SETTINGS }) { Icon(Icons.Rounded.AccountCircle, "Open settings", tint = Muted, modifier = Modifier.size(25.dp)) }
                }
            }, bottomBar = {
                if (link.paired) NavigationBar(containerColor = Color.White, tonalElevation = 0.dp, modifier = Modifier.border(BorderStroke(1.dp, Line))) {
                    val routes = listOf(Routes.HOME, Routes.CHAT, Routes.SWARM, Routes.AGENTS, Routes.REMOTE, Routes.SETTINGS)
                    val labels = listOf("Home", "Chat", "Swarm", "Agents", "Remote", "Settings")
                    val icons = listOf(Icons.Rounded.Home, Icons.Rounded.ChatBubbleOutline, Icons.Rounded.Hub, Icons.Rounded.Groups, Icons.Rounded.DesktopWindows, Icons.Rounded.Settings)
                    routes.forEachIndexed { i, route ->
                        NavigationBarItem(modifier = Modifier.testTag("tab-$route"), selected = tab == route, onClick = { changeTab(route) }, icon = {
                            Icon(icons[i], null, Modifier.size(22.dp))
                        }, label = { Text(labels[i], fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Blue, selectedTextColor = Blue, unselectedIconColor = Muted, unselectedTextColor = Muted, indicatorColor = Color(0xFFEEF3FF)))
                    }
                }
            }) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                if (!link.paired) ConnectionScreen(link, onPair)
                else Column(Modifier.fillMaxSize()) {
                    if (!online) Row(Modifier.fillMaxWidth().background(Color(0xFFFFF2DD)).padding(horizontal = 20.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Reconnecting to your PC…", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = onReconnect) { Text("Retry", fontSize = 12.sp) }
                    }
                    state.error?.let { error -> Row(Modifier.fillMaxWidth().background(Color(0xFFFFEDEF)).padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f), maxLines = 4)
                        IconButton(onClick = onDismissError) { Icon(Icons.Rounded.Close, "Dismiss error", Modifier.size(18.dp)) }
                    } }
                    AnimatedContent(tab, modifier = Modifier.weight(1f), transitionSpec = { fadeIn(tween(if (state.motion) 160 else 0)) togetherWith fadeOut(tween(if (state.motion) 100 else 0)) }, label = "navigation") { current ->
                        tabState.SaveableStateProvider(current) {
                        when (current) {
                            Routes.HOME -> HomePage(state, link, { changeTab(Routes.BUILD) }, { changeTab(Routes.SWARM) }, { runs = true }, { models = true }, { changeTab(Routes.REMOTE) })
                            Routes.SWARM -> SwarmPage(state, online, { agentId = it.identity.id }, { changeTab(Routes.AGENTS) })
                            Routes.AGENTS -> AgentsPage(state, online, { agentId = it.identity.id }, { runs = true })
                            Routes.CHAT -> ChatPage(state, online, onSend, onStop, { history = true }, { onChat("", null) }, { changeTab(Routes.AGENTS) }, { onPrompt(it); changeTab(Routes.BUILD) })
                            Routes.REMOTE -> RemotePCPage(state, link, online)
                            Routes.SETTINGS -> SettingsPage(state, link, local, onLocal, onPreference, onReconnect, onDisconnect, { models = true })
                            Routes.BUILD -> BuildPage(state, online, onPrompt, onPlatform, onBuild, onControl, { runs = true }, { agentId = it.identity.id })
                        }
                        }
                    }
                }
            }
        }
        agentId?.let { id -> state.team.find { it.identity.id == id }?.let { member -> AgentDetail(member, state.runId.isNotBlank() && online, { agentId = null }) { onChat("", id); agentId = null; changeTab(Routes.CHAT) } } }
        if (runs) RunPicker(state, { runs = false }) { onRun(it); changeTab(Routes.BUILD) }
        if (history) ModalBottomSheet(onDismissRequest = { history = false }) {
            LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { PageTitle("Conversations", "Pick up where you left off.") }
                item { PrimaryAction("New conversation") { onChat("", null); history = false } }
                items(state.chats, key = { it.id }) { chat -> SoftCard(Modifier.clickable { onChat(chat.id, null); history = false }) { Text(chat.title, fontWeight = FontWeight.Medium) } }
                if (state.chats.isEmpty()) item { Text("Your conversations will appear here.", color = Muted) }
            }
        }
        if (models) ModalBottomSheet(onDismissRequest = { models = false }) {
            LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { PageTitle("Models in your swarm", "Your desktop routes each task to an available model.") }
                val assigned = state.team.filter { it.model.isNotBlank() }
                if (assigned.isEmpty()) item { SoftCard { Text("Auto routing", fontWeight = FontWeight.Bold); Text("Models appear here when your agents begin working. Configure providers in desktop Settings.", color = Muted) } }
                items(assigned, key = { it.identity.id }) { member -> SoftCard { Text(member.identity.name, fontWeight = FontWeight.SemiBold); Text(member.model, color = Muted, fontSize = 13.sp) } }
            }
        }
    }
}
