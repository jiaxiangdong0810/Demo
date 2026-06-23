package com.example.myapplication.protocol

import java.util.UUID

/**
 * 音立方蓝牙通信协议常量定义
 *
 * 包含所有 UUID、指令格式、响应前缀等协议相关常量
 * 集中管理，方便维护和复用
 */
object YinLiFangProtocol {

    // ==================== UUID 定义 ====================

    /** 蓝牙主服务 UUID */
    val SERVICE_UUID: UUID = UUID.fromString("001120a0-2233-4455-6677-88995a5b5c5d")

    /** 音频 Notify 特征值 UUID - 设备通过此端口推送实时音频流 */
    val AUDIO_NOTIFY_UUID: UUID = UUID.fromString("001120a1-2233-4455-6677-88995a5b5c5d")

    /** 指令 Notify 特征值 UUID - 设备通过此端口返回指令响应 */
    val COMMAND_NOTIFY_UUID: UUID = UUID.fromString("001120a3-2233-4455-6677-88995a5b5c5d")

    /** 写入特征值 UUID - APP 通过此端口发送指令给设备 */
    val WRITE_UUID: UUID = UUID.fromString("001120a2-2233-4455-6677-88995a5b5c5d")

    // ==================== 指令前缀 ====================

    /** APP 发送指令前缀 */
    const val APP_CMD_PREFIX = "YWT_BLE&"

    /** 部分固件使用的短指令前缀 */
    const val SHORT_APP_CMD_PREFIX = "BLE&"

    /** 设备响应前缀 */
    const val DEV_RSP_PREFIX = "DEV&"

    /** 文档中的设备响应前缀 */
    const val DOCUMENT_DEV_RSP_PREFIX = "YWT_DEV&"

    // ==================== 录音控制指令 ====================

    /** 获取设备录音状态 */
    const val CMD_GET_STATUS = "YWT_BLE&STE"

    /** 开始录音 */
    const val CMD_START_RECORD = "YWT_BLE&STA"

    /** 停止录音 */
    const val CMD_STOP_RECORD = "YWT_BLE&STO"

    // ==================== 设备信息查询指令 ====================

    /** 查询存储容量信息 */
    const val CMD_GET_STORAGE = "YWT_BLE&SPACE"

    /** 获取电池电量 */
    const val CMD_GET_BATTERY = "YWT_BLE&BAT"

    /** 获取固件版本号 */
    const val CMD_GET_FW_VERSION = "YWT_BLE&FW"

    /** 获取 WiFi 版本号 */
    const val CMD_GET_WIFI_VERSION = "YWT_BLE&WF"

    /** 获取设备蓝牙 MAC 地址 */
    const val CMD_GET_MAC = "YWT_BLE&MAC"

    // ==================== 时间设置指令 ====================

    /** 设置设备时间，格式：yyyyMMddHHmmss */
    const val CMD_SET_TIME = "YWT_BLE&T&"

    /** 获取设备当前时间 */
    const val CMD_GET_TIME = "YWT_BLE&GT"

    // ==================== 文件管理指令 ====================

    /** 获取录音文件夹列表 */
    const val CMD_LIST_DIRS = "YWT_BLE&LIST_DIRS"

    /** 获取指定文件夹下的录音文件列表 */
    const val CMD_LIST_FILES = "YWT_BLE&LIST&"

    /** 删除录音文件 */
    const val CMD_DELETE_FILE = "YWT_BLE&D&"

    // ==================== 文件同步指令 ====================

    /** 通过 BLE 获取指定录音文件数据 */
    const val CMD_SYNC_BLE = "YWT_BLE&U&"

    /** 通过 WiFi 获取指定录音文件数据 */
    const val CMD_SYNC_WIFI = "YWT_BLE&W&"

    /** 中断当前文件数据传输 */
    const val CMD_STOP_TRANSFER = "YWT_BLE&SHUT"

    // ==================== WiFi 控制指令 ====================

    /** 打开设备 WiFi */
    const val CMD_WIFI_ON = "YWT_BLE&WIFIO"

    /** 关闭设备 WiFi */
    const val CMD_WIFI_OFF = "YWT_BLE&WIFIC"

    /** 获取 WiFi 名称和密码 */
    const val CMD_GET_WIFI_INFO = "YWT_BLE&WIFI"

    /** 获取当前 WiFi 状态 */
    const val CMD_GET_WIFI_STATUS = "YWT_BLE&WIFIS"

    // ==================== 密钥配对指令 ====================

    /** 发送绑定密钥 */
    const val CMD_BIND_KEY = "YWT_BLE&SK&"

    /** 设备主动断开 BLE 连接并重置密钥 */
    const val CMD_RESET_BLE = "YWT_BLE&BLE&OFF"

    /** 设备主动断开 BLE 连接，重置密钥并格式化磁盘 */
    const val CMD_RESET_ALL = "YWT_BLE&BLE&RESET"

    // ==================== 录音模式指令 ====================

    /** 获取当前录音模式 */
    const val CMD_GET_REC_MODE = "YWT_BLE&REC&SECEN"

    // ==================== 响应关键字 ====================

    /** 录音状态响应 */
    const val RSP_STATUS = "DEV&STE&"

    /** 开始录音响应 */
    const val RSP_START_RECORD = "DEV&STA&"

    /** 停止录音响应 */
    const val RSP_STOP_RECORD = "DEV&STO"

    /** 录音过程中状态推送 */
    const val RSP_RECORDING = "DEV&RT&"

    /** 录音错误 */
    const val RSP_RECORD_ERROR = "DEV&REC&ERR"

    /** 存储已满 */
    const val RSP_DISK_FULL = "DEV&DISK&ERR"

    /** 电量响应 */
    const val RSP_BATTERY = "DEV&BAT&"

    /** 存储容量响应 */
    const val RSP_STORAGE = "DEV&SPA&"

    /** 固件版本响应 */
    const val RSP_FW_VERSION = "DEV&FW&"

    /** WiFi 版本响应 */
    const val RSP_WIFI_VERSION = "DEV&WF&"

    /** MAC 地址响应 */
    const val RSP_MAC = "DEV&MAC&"

    /** 时间设置成功响应 */
    const val RSP_TIME_SET_OK = "DEV&T&OK"

    /** 当前时间响应 */
    const val RSP_CURRENT_TIME = "DEV&CT&"

    /** 文件夹列表响应 */
    const val RSP_DIR = "DEV&DIRS&"

    /** 文件夹列表结束响应 */
    const val RSP_DIR_SUM = "DEV&DIRS_SUM&"

    /** 文件列表响应 */
    const val RSP_FILE = "DEV&F&"

    /** 文件列表结束响应 */
    const val RSP_FILE_LIST_END = "DEV&LIST&"

    /** 删除文件成功响应 */
    const val RSP_DELETE_OK = "DEV&D"

    /** 删除文件失败响应 */
    const val RSP_DELETE_ERROR = "DEV&D&ERR"

    /** BLE 同步文件大小响应 */
    const val RSP_BLE_SYNC_SIZE = "DEV&U&"

    /** WiFi 同步文件大小响应 */
    const val RSP_WIFI_SYNC_SIZE = "DEV&W&"

    /** 文件传输完成 */
    const val RSP_TRANSFER_DONE = "DEV&OFF"

    /** 文件传输错误 */
    const val RSP_TRANSFER_ERROR = "DEV&U&ERR"

    /** 中断传输确认 */
    const val RSP_STOP_TRANSFER = "DEV&SHUT"

    /** WiFi 已开启响应 */
    const val RSP_WIFI_ON = "DEV&WIFIO"

    /** WiFi 已关闭响应 */
    const val RSP_WIFI_OFF = "DEV&WIFIC"

    /** WiFi 信息响应 */
    const val RSP_WIFI_INFO = "DEV&WIFI&"

    /** WiFi 状态响应 */
    const val RSP_WIFI_STATUS = "DEV&WIFIS&"

    /** 密钥配对成功 */
    const val RSP_BIND_OK = "DEV&SK&OK"

    /** 密钥配对失败 */
    const val RSP_BIND_ERROR = "DEV&SK&ERR"

    /** 录音模式 - 通话模式 */
    const val RSP_REC_MODE_CALL = "DEV&REC&CALL"

    /** 录音模式 - 对话模式 */
    const val RSP_REC_MODE_CONVERSATION = "DEV&REC&CON"

    /** 未知指令响应 */
    const val RSP_UNKNOWN = "DEV&UNKNOWN"

    fun toShortCommand(command: String): String {
        return if (command.startsWith(APP_CMD_PREFIX)) {
            SHORT_APP_CMD_PREFIX + command.removePrefix(APP_CMD_PREFIX)
        } else {
            command
        }
    }

    fun normalizeResponse(response: String): String {
        val clean = response.trim().trimEnd('\u0000')
        return if (clean.startsWith(DOCUMENT_DEV_RSP_PREFIX)) {
            DEV_RSP_PREFIX + clean.removePrefix(DOCUMENT_DEV_RSP_PREFIX)
        } else {
            clean
        }
    }

    // ==================== WiFi 状态码 ====================

    /** WiFi 关闭 */
    const val WIFI_STATUS_OFF = 0

    /** WiFi 已连接 */
    const val WIFI_STATUS_CONNECTED = 1

    /** WiFi 未连接 */
    const val WIFI_STATUS_DISCONNECTED = 2

    /** 等待 WiFi 开启 */
    const val WIFI_STATUS_WAITING = 3

    /** 修改密码中 */
    const val WIFI_STATUS_CHANGING_PASSWORD = 4

    /** OTA 升级中 */
    const val WIFI_STATUS_OTA = 5

    /** 密码修改成功，等待系统复位关机 */
    const val WIFI_STATUS_RESETTING = 6

    /** 自动关闭 */
    const val WIFI_STATUS_AUTO_OFF = 7

    // ==================== 录音状态 ====================

    /** 无录音 */
    const val REC_STATUS_IDLE = 0

    /** 录音中 */
    const val REC_STATUS_RECORDING = 1
}
