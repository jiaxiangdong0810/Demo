package com.example.myapplication.repository

import android.content.Context
import com.example.myapplication.ble.BleManager

object DeviceSession {
    private var bleManagerInstance: BleManager? = null
    private var repositoryInstance: DeviceRepository? = null

    fun bleManager(context: Context): BleManager {
        return bleManagerInstance ?: BleManager(context.applicationContext).also {
            bleManagerInstance = it
        }
    }

    fun repository(context: Context): DeviceRepository {
        val appContext = context.applicationContext
        return repositoryInstance ?: DeviceRepository(bleManager(appContext), appContext).also {
            repositoryInstance = it
        }
    }
}
