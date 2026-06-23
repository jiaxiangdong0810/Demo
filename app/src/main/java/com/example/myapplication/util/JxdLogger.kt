package com.example.myapplication.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object JxdLogger {

    private const val PREFIX = "jxd"
    @Volatile
    private var initialized = false

    fun init() {
        initialized = true
    }

    fun d(tag: String, message: String) {
        ensureInit()
        log(Log.DEBUG, tag, message, null)
    }

    fun i(tag: String, message: String) {
        ensureInit()
        log(Log.INFO, tag, message, null)
    }

    fun w(tag: String, message: String) {
        ensureInit()
        log(Log.WARN, tag, message, null)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        ensureInit()
        log(Log.ERROR, tag, message, throwable)
    }

    private fun ensureInit() {
        if (!initialized) {
            initialized = true
        }
    }

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        val safeTag = tag.ifBlank { PREFIX }
        val formatted = buildString {
            append(PREFIX)
//            append("_time=")
//            append(now())
//            append(" | tag=")
//            append(safeTag)
            append(" | message=")
            append(message)
        }

//        val safeTag = "jxd"
//        val formatted = message
        when (priority) {
            Log.VERBOSE -> Log.v(safeTag, formatted, throwable)
            Log.DEBUG -> Log.d(safeTag, formatted, throwable)
            Log.INFO -> Log.i(safeTag, formatted, throwable)
            Log.WARN -> Log.w(safeTag, formatted, throwable)
            Log.ERROR -> Log.e(safeTag, formatted, throwable)
            else -> Log.d(safeTag, formatted, throwable)
        }
    }

    private fun now(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        return formatter.format(Date())
    }
}
