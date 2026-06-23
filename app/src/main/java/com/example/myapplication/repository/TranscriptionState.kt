package com.example.myapplication.repository

data class TranscriptionState(
    val isEnabled: Boolean = true,
    val isModelReady: Boolean = false,
    val isTranscribing: Boolean = false,
    val statusText: String = "等待录音",
    val latestText: String = "",
    val finalText: String = "",
    val lastError: String? = null
)
