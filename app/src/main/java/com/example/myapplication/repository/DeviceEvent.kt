package com.example.myapplication.repository

import com.example.myapplication.ble.ConnectionState

/**
 * 设备事件 — 通过 EventBus 通知所有订阅者
 *
 * 页面只需 observe 这个 Flow，不需要关心数据从哪来
 */
sealed class DeviceEvent {

    /** 连接状态变化 */
    data class ConnectionChanged(val state: ConnectionState) : DeviceEvent()

    /** 电量更新 */
    data class BatteryChanged(val percent: Int) : DeviceEvent()

    /** 存储更新 */
    data class StorageChanged(val freeMb: Int, val totalMb: Int) : DeviceEvent()

    /** 固件版本更新 */
    data class FirmwareChanged(val version: String) : DeviceEvent()

    /** 录音状态变化 */
    data class RecordingChanged(val isRecording: Boolean, val fileName: String = "") : DeviceEvent()

    /** 发现新设备 */
    data class DeviceDiscovered(val name: String, val address: String) : DeviceEvent()

    /** 错误 */
    data class Error(val message: String) : DeviceEvent()
}
