package com.example.myapplication.repository

data class RealtimeRecordingState(
    val isCapturing: Boolean = false,
    val fileName: String = "",
    val localPath: String = "",
    val totalBytes: Long = 0L,
    val lastPacketBytes: Int = 0,
    val packetCount: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val startedAtMillis: Long = 0L,
    val lastError: String? = null
)
