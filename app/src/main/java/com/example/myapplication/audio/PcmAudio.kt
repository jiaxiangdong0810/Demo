package com.example.myapplication.audio

data class PcmAudio(
    val samples: FloatArray,
    val sampleRate: Int
)
