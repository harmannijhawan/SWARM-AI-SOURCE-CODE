package com.swarm.ai.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agents")
data class Agent(
    @PrimaryKey val id: String,
    val name: String,
    val role: String,
    val status: String, // "Active", "Idle", "Busy"
    val model: String,
    val performance: Float,
    val tasksCompleted: Int,
    val specialty: String
)

@Entity(tableName = "ai_models")
data class AIModel(
    @PrimaryKey val id: String,
    val name: String,
    val provider: String,
    val size: String,
    val status: String, // "Downloaded", "Available", "Downloading"
    val quantization: String,
    val isDefault: Boolean
)

@Entity(tableName = "settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    val themeMode: String = "System", // "Dark", "Light", "System"
    val activeProvider: String = "Local TFLite",
    val localInferenceEnabled: Boolean = true,
    val maxThreads: Int = 4,
    val telemetryEnabled: Boolean = false
)

@Entity(tableName = "build_history")
data class BuildHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prompt: String,
    val swarmName: String,
    val agentCount: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val resultSummary: String
)
