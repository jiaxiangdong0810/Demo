package com.example.myapplication.ble

/**
 * BLE 连接状态枚举
 *
 * 表示蓝牙设备连接的各个阶段
 */
enum class ConnectionState {
    /** 未连接 */
    DISCONNECTED,

    /** 正在扫描 */
    SCANNING,

    /** 正在连接 */
    CONNECTING,

    /** 已连接，正在发现服务 */
    DISCOVERING_SERVICES,

    /** 已连接，服务发现完成，可以通信 */
    CONNECTED,

    /** 连接失败 */
    FAILED;

    /** 是否已连接（可以通信） */
    val isReady: Boolean
        get() = this == CONNECTED

    /** 是否正在连接过程中 */
    val isConnecting: Boolean
        get() = this in listOf(SCANNING, CONNECTING, DISCOVERING_SERVICES)
}
