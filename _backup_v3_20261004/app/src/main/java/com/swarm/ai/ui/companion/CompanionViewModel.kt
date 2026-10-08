package com.swarm.ai.ui.companion

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swarm.ai.remote.RemoteClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

data class AgentIdentity(val id: String, val name: String, val role: String)
// Names and descriptions match src/components/status.ts in the desktop application.
val desktopAgents = listOf(
    AgentIdentity("manager", "Manager", "Planning & orchestration"),
    AgentIdentity("planner", "Planner", "Task graph"),
    AgentIdentity("researcher", "Researcher", "Web research"),
    AgentIdentity("designer", "Designer", "UI/UX design"),
    AgentIdentity("architect", "Architect", "System architecture"),
    AgentIdentity("coder", "Coder", "Development"),
    AgentIdentity("tester", "Tester", "Testing & QA"),
    AgentIdentity("reviewer", "Reviewer", "Code & UX review"),
    AgentIdentity("optimizer", "Optimizer", "Performance"),
    AgentIdentity("vision", "Vision QA", "Visual inspection"),
    AgentIdentity("finalizer", "Finalizer", "Verification & delivery")
)
data class TeamMember(val identity: AgentIdentity, val status: String = "idle", val task: String = "", val activity: String = "", val model: String = "")
data class RunItem(val id: String, val objective: String, val status: String)
data class TaskItem(val id: String, val title: String, val role: String, val status: String, val output: String = "", val files: List<String> = emptyList())
data class ChatItem(val id: String, val text: String, val user: Boolean, val status: String, val detail: String = "")
data class ConversationItem(val id: String, val title: String)
data class CompanionState(
    val loading: Boolean = false,
    val runs: List<RunItem> = emptyList(),
    val runId: String = "",
    val runStatus: String = "",
    val objective: String = "",
    val summary: String = "",
    val tasks: List<TaskItem> = emptyList(),
    val team: List<TeamMember> = desktopAgents.map { TeamMember(it) },
    val activity: List<String> = emptyList(),
    val prompt: String = "",
    val platform: String = "Web",
    val building: Boolean = false,
    val sending: Boolean = false,
    val chatId: String = "",
    val chatRole: String? = null,
    val chats: List<ConversationItem> = emptyList(),
    val messages: List<ChatItem> = emptyList(),
    val error: String? = null,
    val motion: Boolean = true,
    val haptics: Boolean = true
)

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.value(key: String): String = if (isNull(key)) "" else optString(key)

@HiltViewModel
class CompanionViewModel @Inject constructor(
    private val api: CompanionApi,
    private val connection: RemoteClient,
    @ApplicationContext context: Context
) : ViewModel() {
    private val preferences = context.getSharedPreferences("companion-ui", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(CompanionState(motion = preferences.getBoolean("motion", true), haptics = preferences.getBoolean("haptics", true)))
    val state = _state.asStateFlow()
    private var polling: Job? = null
    private var command: Job? = null
    private var sendJob: Job? = null
    private var selection = 0
    private var chatSelection = 0
    private var projectForDraft: Pair<String, String>? = null

    fun start() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            while (isActive && connection.isPaired) {
                try { refreshData() } catch (e: CancellationException) { throw e }
                catch (e: Exception) { _state.update { it.copy(error = e.message, loading = false) } }
                delay(2500)
            }
        }
    }
    fun stop() { polling?.cancel(); polling = null }
    fun clear() {
        stop(); command?.cancel(); sendJob?.cancel(); selection++; chatSelection++; projectForDraft = null
        _state.update { CompanionState(motion = it.motion, haptics = it.haptics) }
    }
    fun dismissError() { _state.update { it.copy(error = null) } }
    fun prompt(value: String) { _state.update { it.copy(prompt = value.take(7800)) } }
    fun platform(value: String) { _state.update { it.copy(platform = value) } }
    fun preference(motion: Boolean = state.value.motion, haptics: Boolean = state.value.haptics) {
        preferences.edit().putBoolean("motion", motion).putBoolean("haptics", haptics).apply()
        _state.update { it.copy(motion = motion, haptics = haptics) }
    }
    fun selectRun(id: String) {
        selection++; chatSelection++
        _state.update { it.copy(runId = id, runStatus = "", objective = "", summary = "", activity = emptyList(), tasks = emptyList(), team = desktopAgents.map { a -> TeamMember(a) }, messages = if (it.chatRole != null) emptyList() else it.messages) }
        stop(); start()
    }
    fun selectChat(id: String = "", role: String? = null) {
        chatSelection++
        _state.update { it.copy(chatId = id, chatRole = role, messages = emptyList()) }
        stop(); start()
    }

    private suspend fun refreshData() {
        val version = selection
        val runs = (api.invoke("runs:list") as? JSONArray).objects().map { RunItem(it.value("id"), it.value("objective"), it.value("status")) }
        if (version != selection) return
        val selected = state.value.runId.ifEmpty { runs.firstOrNull()?.id ?: "" }
        val snapshot = if (selected.isNotBlank()) api.invoke("runs:snapshot", selected) as? JSONObject else null
        val run = snapshot?.optJSONObject("run")
        val agents = snapshot?.optJSONArray("agents").objects()
        val tasks = snapshot?.optJSONArray("tasks").objects().map { t ->
            val files = t.optJSONArray("filesTouched")
            TaskItem(t.value("id"), t.value("title"), t.value("role"), t.value("status"), t.value("error").ifBlank { t.value("output") },
                if (files == null) emptyList() else (0 until files.length()).map { files.optString(it) })
        }
        if (version != selection) return
        _state.update { s -> s.copy(loading = false, runs = runs, runId = selected,
            runStatus = run?.value("status") ?: "", objective = run?.value("objective") ?: "", summary = run?.value("summary") ?: "",
            tasks = tasks, team = desktopAgents.map { identity ->
                val agent = agents.firstOrNull { it.value("role") == identity.id }
                TeamMember(identity, agent?.value("status") ?: "idle", agent?.value("taskTitle") ?: "", agent?.value("lastAction") ?: "", agent?.value("modelId") ?: "")
            }, activity = tasks.filter { it.status != "waiting" && it.status != "ready" }.takeLast(12).reversed().map { "${desktopAgents.find { a -> a.id == it.role }?.name ?: it.role} · ${it.title} · ${it.status}" }) }
        refreshChat()
    }

    private suspend fun refreshChat() {
        val version = chatSelection
        val s = state.value
        val chats = (api.invoke("chat:list") as? JSONArray).objects().map { ConversationItem(it.value("id"), it.value("title")) }
        val messages = if (s.chatRole != null && s.runId.isNotBlank()) {
            (api.invoke("run:chat:history", "${s.runId}:${s.chatRole}") as? JSONArray).objects().map {
                ChatItem(it.value("id"), it.value("text"), it.value("senderType") == "user", it.value("status"), it.value("error"))
            }
        } else if (s.chatId.isNotBlank()) {
            (api.invoke("chat:get", s.chatId) as? JSONObject)?.optJSONArray("turns").objects().map {
                ChatItem(it.value("id"), it.value("text"), it.value("role") == "user", it.value("status"), it.value("error").ifBlank { it.value("routing") })
            }
        } else emptyList()
        if (version == chatSelection) _state.update { it.copy(chats = chats, messages = messages) }
    }

    fun build() {
        val s = state.value
        if (s.building || s.prompt.isBlank()) return
        _state.update { it.copy(building = true, error = null) }
        command = viewModelScope.launch {
            try {
                val objective = "Build for ${s.platform}.\n\n${s.prompt.trim()}"
                val projectId = projectForDraft?.takeIf { it.first == objective }?.second ?: run {
                    val project = api.invoke("projects:create", JSONObject().put("objective", objective)) as JSONObject
                    project.getString("id").also { projectForDraft = objective to it }
                }
                val run = api.invoke("runs:start", projectId, objective, JSONObject()) as JSONObject
                selection++
                _state.update { it.copy(runId = run.getString("id"), objective = objective, runStatus = run.value("status"), prompt = "", tasks = emptyList()) }
                projectForDraft = null
                stop(); start()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "${e.message} Check the run list before retrying if the connection was interrupted.") } }
            finally { _state.update { it.copy(building = false) } }
        }
    }
    fun control(action: String) {
        val id = state.value.runId
        if (id.isBlank() || state.value.building) return
        _state.update { it.copy(building = true) }
        command = viewModelScope.launch {
            try { api.invoke("runs:$action", id); stop(); start() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message) } }
            finally { _state.update { it.copy(building = false) } }
        }
    }
    fun send(text: String) {
        val s = state.value
        if (s.sending || text.isBlank()) return
        val version = chatSelection
        _state.update { it.copy(sending = true, error = null) }
        sendJob = viewModelScope.launch {
            try {
                if (s.chatRole != null) {
                    check(s.runId.isNotBlank()) { "Select a build to talk to its agents." }
                    api.invoke(if (s.chatRole == "manager") "run:chat:send" else "run:chat:agent:send",
                        JSONObject().put("runId", s.runId).put("agentRole", s.chatRole).put("text", text.take(8000)))
                } else {
                    val id = s.chatId.ifBlank { (api.invoke("chat:new") as JSONObject).getString("id") }
                    if (version == chatSelection) _state.update { it.copy(chatId = id) }
                    api.invoke("chat:send", JSONObject().put("id", id).put("text", text.take(8000)))
                }
                if (version == chatSelection) refreshChat()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message) } }
            finally { _state.update { it.copy(sending = false) } }
        }
    }
    fun stopResponse() {
        val s = state.value
        viewModelScope.launch {
            try { if (s.chatRole != null) api.invoke("run:chat:stop", "${s.runId}:${s.chatRole}") else if (s.chatId.isNotBlank()) api.invoke("chat:stop", s.chatId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message) } }
        }
    }
}
