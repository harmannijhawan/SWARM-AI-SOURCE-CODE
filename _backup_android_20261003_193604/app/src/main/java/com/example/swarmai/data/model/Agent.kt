package com.example.swarmai.data.model

data class Agent(
    val id: String,
    val name: String,
    val role: String,
    val status: AgentStatus,
    val workload: Float,
    val accuracy: Float
)

enum class AgentStatus {
    IDLE, RUNNING, PAUSED, ERROR
}
