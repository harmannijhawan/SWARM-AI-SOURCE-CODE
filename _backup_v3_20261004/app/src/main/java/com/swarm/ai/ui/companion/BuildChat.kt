package com.swarm.ai.ui.companion

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

val suggestions = listOf("Build a modern landing page with smooth animations", "Create a desktop app for file management", "Build an Android app with authentication")

@Composable
fun BuildPage(state: CompanionState, online: Boolean, onPrompt: (String) -> Unit, onPlatform: (String) -> Unit, onBuild: () -> Unit, onControl: (String) -> Unit, onRuns: () -> Unit, onAgent: (TeamMember) -> Unit) {
    var mode by rememberSaveable { mutableStateOf("Build") }
    var composing by rememberSaveable { mutableStateOf(false) }
    var cancel by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.prompt) { if (state.prompt.isNotBlank()) composing = true }
    LaunchedEffect(state.runId) { if (state.runId.isNotBlank() && state.prompt.isBlank()) composing = false }
    val execution = state.runId.isNotBlank() && !composing
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("build-list"), contentPadding = PaddingValues(22.dp, 8.dp, 22.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Segments(listOf("Build", "Templates"), mode) { mode = it; if (it == "Templates") composing = true } }
        if (!execution || mode == "Templates") {
            item { PageTitle("What should\nSWARM build?", "Describe the outcome. Your swarm will plan, build, and verify it on your PC.") }
            item { SoftCard {
                OutlinedTextField(state.prompt, onPrompt, Modifier.fillMaxWidth().heightIn(min = 170.dp), placeholder = { Text("Tell the swarm what you want to build…", color = Muted, fontSize = 14.sp) }, minLines = 5, maxLines = 9, shape = RoundedCornerShape(18.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = Line))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp), tint = Blue); Text("  Auto · best agents for the job", fontSize = 11.sp, color = Muted) }
                    Text("${state.prompt.length}/7800", fontSize = 10.sp, color = Muted)
                }
            } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Web", "Windows", "Android", "CLI").forEach { platform ->
                    Surface(onClick = { onPlatform(platform) }, modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                        shape = RoundedCornerShape(12.dp), color = if (state.platform == platform) Color(0xFFE6EDFF) else Color(0xFFF0F3FB),
                        border = BorderStroke(1.dp, if (state.platform == platform) Blue.copy(alpha = .5f) else Line)) {
                        Box(contentAlignment = Alignment.Center) { Text(platform, fontSize = 11.sp, maxLines = 1, color = if (state.platform == platform) Blue else Muted) }
                    }
                }
            } }
            item { PrimaryAction("Build with SWARM", online && state.prompt.isNotBlank(), state.building) { mode = "Build"; onBuild() } }
            if (!online) item { Text("Reconnect to your PC to start a build.", color = Muted, fontSize = 12.sp) }
            item { SectionTitle("${if (mode == "Templates") "Start with an idea" else "Suggested prompts"}") }
            items(suggestions) { suggestion ->
                SoftCard(Modifier.clickable { onPrompt(suggestion); mode = "Build" }, color = Color(0xFFF1F4FC)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = Muted); Text(suggestion, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp); Icon(Icons.Rounded.ChevronRight, null, tint = Muted) }
                }
            }
            if (state.runId.isNotBlank()) item { TextButton(onClick = { composing = false; mode = "Build" }) { Text("Return to current build") } }
        } else {
            item { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.weight(1f)) { PageTitle(if (state.runStatus == "completed") "Built together." else "Your swarm at work") }; IconButton(onClick = { composing = true }) { Icon(Icons.Rounded.Add, "New build") } } }
            item { SoftCard {
                StatusPill(state.runStatus.ifBlank { "Loading" })
                Text(state.objective, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                val done = state.tasks.count { it.status == "completed" }
                val progress by animateFloatAsState(if (state.tasks.isEmpty()) 0f else done.toFloat() / state.tasks.size, label = "build progress")
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), trackColor = Line)
                Text("$done of ${state.tasks.size} tasks completed", color = Muted, fontSize = 12.sp)
                if (state.runStatus == "running" || state.runStatus == "paused") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onControl(if (state.runStatus == "paused") "resume" else "pause") }, enabled = online && !state.building) { Icon(if (state.runStatus == "paused") Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(17.dp)); Text(if (state.runStatus == "paused") "Resume" else "Pause") }
                    TextButton(onClick = { cancel = true }, enabled = online && !state.building) { Text("Stop build", color = MaterialTheme.colorScheme.error) }
                }
            } }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(state.team.filter { it.task.isNotBlank() || it.status != "idle" }.ifEmpty { state.team.take(4) }, key = { it.identity.id }) { member ->
                    Column(Modifier.width(72.dp).clip(RoundedCornerShape(16.dp)).clickable { onAgent(member) }.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        AgentAvatar(desktopAgents.indexOf(member.identity), active = member.status in listOf("working", "planning"))
                        Text(member.identity.name, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Text(member.status, color = Muted, fontSize = 10.sp)
                    }
                }
            } }
            item { SectionTitle("Execution plan") }
            if (state.tasks.isEmpty()) item { SoftCard { Text("Waiting for the desktop’s plan", fontWeight = FontWeight.SemiBold); Text("Tasks appear here as Manager and Planner create them.", color = Muted, fontSize = 13.sp) } }
            items(state.tasks, key = { it.id }) { task ->
                var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
                SoftCard(Modifier.animateContentSize().clickable { expanded = !expanded }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (task.status) { "completed" -> Icons.Rounded.CheckCircle; "running" -> Icons.Rounded.PlayCircle; "failed", "blocked" -> Icons.Rounded.ErrorOutline; else -> Icons.Rounded.RadioButtonUnchecked }, null, tint = when (task.status) { "completed" -> Color(0xFF20AF75); "running" -> Blue; else -> Muted }, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) { Text(task.title, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("${desktopAgents.find { it.id == task.role }?.name ?: task.role} · ${task.status}", color = Muted, fontSize = 11.sp) }
                        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, "Task details", tint = Muted)
                    }
                    if (expanded) { SelectionContainer { Text(task.output.ifBlank { "No result yet." }, fontSize = 12.sp, color = Muted) }; task.files.take(10).forEach { Text("↳ $it", fontSize = 11.sp, color = Blue) } }
                }
            }
            if (state.summary.isNotBlank()) item { SoftCard(color = Color(0xFFE9F8F1)) { Text("Build result", fontWeight = FontWeight.Bold); SelectionContainer { Text(state.summary, fontSize = 13.sp) } } }
        }
        item { TextButton(onClick = onRuns, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Previous builds") } }
    }
    if (cancel) AlertDialog(onDismissRequest = { cancel = false }, title = { Text("Stop this build?") }, text = { Text("The desktop will cancel the active run. Work already saved stays on your PC.") }, confirmButton = { TextButton(onClick = { cancel = false; onControl("cancel") }) { Text("Stop build") } }, dismissButton = { TextButton(onClick = { cancel = false }) { Text("Keep building") } })
}

@Composable
fun ChatPage(state: CompanionState, online: Boolean, onSend: (String) -> Unit, onStop: () -> Unit, onHistory: () -> Unit, onNew: () -> Unit, onAgents: () -> Unit, onBuild: (String) -> Unit) {
    var draft by rememberSaveable(state.chatRole) { mutableStateOf("") }
    var previousChat by rememberSaveable { mutableStateOf(state.chatId) }
    LaunchedEffect(state.chatId) {
        if (previousChat.isNotBlank() && previousChat != state.chatId) draft = ""
        previousChat = state.chatId
    }
    var submitted by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val streaming = state.messages.any { it.status == "streaming" }
    LaunchedEffect(state.messages.lastOrNull()?.text, state.messages.size) {
        if (state.messages.isNotEmpty() && list.layoutInfo.visibleItemsInfo.lastOrNull()?.index.let { it == null || it >= state.messages.size - 3 }) list.animateScrollToItem(state.messages.lastIndex)
        if (submitted != null && state.messages.any { it.user && it.text == submitted }) { draft = ""; submitted = null }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.chatRole?.let { role -> desktopAgents.find { it.id == role }?.name } ?: "Chat with SWARM", fontWeight = FontWeight.Bold, fontSize = 21.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, "Conversation history", tint = Muted) }
            IconButton(onClick = onNew) { Icon(Icons.Rounded.Add, "New conversation", tint = Blue) }
        }
        Row(Modifier.padding(horizontal = 22.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (state.chatRole == null) "Ideas start with a conversation." else "Connected to this build’s ${state.chatRole}.", fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
            TextButton(onClick = onAgents, contentPadding = PaddingValues(0.dp)) { Text("Agents", fontSize = 12.sp) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(22.dp, 12.dp, 22.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.messages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AgentAvatar(modifier = Modifier.size(102.dp), hero = true, active = true)
                    Text("A thought. A question. A big idea.", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text("Your swarm is listening.", color = Muted, fontSize = 13.sp)
                    suggestions.take(2).forEach { suggestion -> OutlinedButton(onClick = { draft = suggestion }, shape = RoundedCornerShape(16.dp)) { Text(suggestion, fontSize = 12.sp) } }
                }
            }
            items(state.messages, key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.user) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (!message.user) Text(state.chatRole?.let { role -> desktopAgents.find { it.id == role }?.name } ?: "SWARM", fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Muted)
                    Surface(color = if (message.user) Color(0xFFE2EBFF) else Color.White, shape = RoundedCornerShape(18.dp, 18.dp, if (message.user) 5.dp else 18.dp, if (message.user) 18.dp else 5.dp), modifier = Modifier.widthIn(max = 330.dp)) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectionContainer { Text(message.text.ifBlank { if (message.status == "streaming") "Thinking…" else "No response text" }, fontSize = 14.sp, lineHeight = 22.sp) }
                            if (message.detail.isNotBlank()) Text(message.detail, fontSize = 11.sp, color = if (message.status == "error") MaterialTheme.colorScheme.error else Muted)
                            if (message.status == "streaming") LinearProgressIndicator(Modifier.width(60.dp).height(2.dp))
                        }
                    }
                    if (message.user && Regex("build|create|develop|make", RegexOption.IGNORE_CASE).containsMatchIn(message.text)) TextButton(onClick = { onBuild(message.text) }) { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("Build this with SWARM", fontSize = 11.sp) }
                }
            }
            if (state.sending && !streaming) item { Text("Sending to your desktop…", color = Muted, fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).background(Color.White, RoundedCornerShape(24.dp)).border(1.dp, Line, RoundedCornerShape(24.dp)).padding(6.dp), verticalAlignment = Alignment.Bottom) {
            TextField(draft, { draft = it.take(8000) }, modifier = Modifier.weight(1f), placeholder = { Text("Ask SWARM anything…", fontSize = 13.sp) }, maxLines = 5,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            FilledIconButton(onClick = { if (streaming) onStop() else { submitted = draft.trim(); onSend(draft.trim()) } }, enabled = online && (streaming || draft.isNotBlank() && !state.sending), modifier = Modifier.size(48.dp)) {
                Icon(if (streaming) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send, if (streaming) "Stop response" else "Send message", Modifier.size(20.dp))
            }
        }
    }
}
