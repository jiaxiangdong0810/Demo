package com.example.myapplication

import android.app.Application
import com.example.myapplication.util.JxdLogger

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        JxdLogger.init()
    }
}
