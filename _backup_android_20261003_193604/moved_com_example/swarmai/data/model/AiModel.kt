package com.example.swarmai.data.model

data class AiModel(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val sizeMb: Float,
    val isDownloaded: Boolean,
    val category: String,
    val badge: String? = null
)
