package com.example.myapplication

data class Message(
    val id: Long,
    val content: String,
    val isSelf: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)