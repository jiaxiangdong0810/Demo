package com.example.myapplication.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 设备事件总线（全局单例）
 *
 * 所有页面通过 DeviceManager.observe() 订阅事件，
 * 数据变化时自动推送，无需手动查询。
 */
object DeviceEventBus {

    private val _events = MutableSharedFlow<DeviceEvent>(
        replay = 1,                    // 新订阅者立即收到最近一条事件
        extraBufferCapacity = 16
    )
    val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    fun emit(event: DeviceEvent) {
        _events.tryEmit(event)
    }
}
