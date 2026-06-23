package com.example.myapplication.repository

import com.example.myapplication.ble.ConnectionState

/**
 * 设备状态数据类
 *
 * 包含设备的所有状态信息，UI 层观察此数据类更新界面
 */
data class DeviceState(
    /** 蓝牙连接状态 */
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,

    /** 设备名称 */
    val deviceName: String = "",

    /** 设备 MAC 地址 */
    val macAddress: String = "",

    /** 电池电量百分比 (0-100) */
    val battery: Int = -1,

    /** 总存储容量 (MB) */
    val totalStorage: Int = -1,

    /** 剩余存储容量 (MB) */
    val freeStorage: Int = -1,

    /** 录音状态：true=录音中，false=未录音 */
    val isRecording: Boolean = false,

    /** 当前录音文件名 */
    val recordingFileName: String = "",

    /** 当前录音时长（秒） */
    val recordingDuration: Int = 0,

    /** 固件版本号 */
    val firmwareVersion: String = "",

    /** WiFi 版本号 */
    val wifiVersion: String = "",

    /** 是否已配对绑定 */
    val isBound: Boolean = false,

    /** 最后错误信息 */
    val lastError: String? = null
)
