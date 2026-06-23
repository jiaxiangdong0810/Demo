package com.example.myapplication.repository

import android.annotation.SuppressLint
import com.example.myapplication.ble.BleManager
import com.example.myapplication.ble.ConnectionState
import com.example.myapplication.protocol.YinLiFangProtocol
import com.example.myapplication.util.JxdLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 设备业务仓库
 *
 * 职责：指令组装、协议解析、状态管理
 * 组合 BleManager 完成业务逻辑
 *
 * 设计要点：
 * 1. 收到 BleManager 的原始数据后，解析协议，分发到不同的 Flow
 * 2. 音频数据（001120a1 端口）→ audioStream
 * 3. 指令响应（001120a3 端口）→ commandResponse + 更新 deviceState
 * 4. 上层只需调方法，不需要知道 UUID 和协议格式
 */
class DeviceRepository(private val bleManager: BleManager) {

    companion object {
        private const val TAG = "DeviceRepository"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ==================== 状态暴露 ====================

    /** 设备状态 */
    private val _deviceState = MutableStateFlow(DeviceState())
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    /** 实时音频数据流 */
    private val _audioStream = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val audioStream: SharedFlow<ByteArray> = _audioStream

    /** 指令响应流 */
    private val _commandResponse = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val commandResponse: SharedFlow<String> = _commandResponse

    /** 扫描到的设备列表 */
    val scannedDevices = bleManager.scannedDevices

    /** 连接状态 */
    val connectionState = bleManager.connectionState

    // ==================== 初始化 ====================

    init {
        // 监听 BLE 连接状态
        scope.launch {
            bleManager.connectionState.collect { state ->
                _deviceState.update { it.copy(connectionState = state) }
            }
        }

        // 监听接收到的数据并解析
        scope.launch {
            bleManager.receivedData.collect { data ->
                parseResponse(data)
            }
        }
    }

    // ==================== 业务操作 ====================

    /**
     * 开始扫描设备
     */
    fun startScan() {
        bleManager.startScan()
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        bleManager.stopScan()
    }

    /**
     * 连接设备
     */
    @SuppressLint("MissingPermission")
    fun connect(device: android.bluetooth.BluetoothDevice) {
        _deviceState.update {
            it.copy(
                deviceName = device.name ?: "未知设备",
                macAddress = device.address
            )
        }
        bleManager.connect(device)
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        bleManager.disconnect()
        _deviceState.value = DeviceState()
    }

    /**
     * 订阅音频 Notify
     * 设备在录音时会通过此端口推送实时音频流
     */
    fun subscribeAudioStream() {
        bleManager.enableNotify(
            YinLiFangProtocol.SERVICE_UUID,
            YinLiFangProtocol.AUDIO_NOTIFY_UUID
        )
        JxdLogger.d(TAG, "已订阅音频流")
    }

    /**
     * 订阅指令 Notify
     * 设备通过此端口返回指令响应
     */
    fun subscribeCommandNotify() {
        bleManager.enableNotify(
            YinLiFangProtocol.SERVICE_UUID,
            YinLiFangProtocol.COMMAND_NOTIFY_UUID
        )
        JxdLogger.d(TAG, "已订阅指令响应")
    }

    /**
     * 开始录音
     */
    fun startRecording() {
        sendCommand(YinLiFangProtocol.CMD_START_RECORD)
    }

    /**
     * 停止录音
     */
    fun stopRecording() {
        sendCommand(YinLiFangProtocol.CMD_STOP_RECORD)
    }

    /**
     * 查询录音状态
     */
    fun queryRecordingStatus() {
        sendCommand(YinLiFangProtocol.CMD_GET_STATUS)
    }

    /**
     * 查询电量
     */
    fun queryBattery() {
        sendCommand(YinLiFangProtocol.CMD_GET_BATTERY)
    }

    /**
     * 查询存储容量
     */
    fun queryStorage() {
        sendCommand(YinLiFangProtocol.CMD_GET_STORAGE)
    }

    /**
     * 查询固件版本
     */
    fun queryFirmwareVersion() {
        sendCommand(YinLiFangProtocol.CMD_GET_FW_VERSION)
    }

    /**
     * 查询 MAC 地址
     */
    fun queryMacAddress() {
        sendCommand(YinLiFangProtocol.CMD_GET_MAC)
    }

    /**
     * 设置设备时间
     */
    fun setDeviceTime(time: String) {
        sendCommand("${YinLiFangProtocol.CMD_SET_TIME}$time")
    }

    /**
     * 发送绑定密钥
     */
    fun bindDevice(key: String) {
        sendCommand("${YinLiFangProtocol.CMD_BIND_KEY}$key")
    }

    /**
     * 获取录音文件夹列表
     */
    fun listDirectories() {
        sendCommand(YinLiFangProtocol.CMD_LIST_DIRS)
    }

    /**
     * 获取指定文件夹下的文件列表
     */
    fun listFiles(dirName: String) {
        sendCommand("${YinLiFangProtocol.CMD_LIST_FILES}$dirName")
    }

    /**
     * 发送自定义指令
     */
    fun sendCommand(cmd: String) {
        val data = cmd.toByteArray()
        bleManager.writeData(
            YinLiFangProtocol.SERVICE_UUID,
            YinLiFangProtocol.WRITE_UUID,
            data
        )
        JxdLogger.d(TAG, "发送指令: $cmd")
    }

    // ==================== 协议解析 ====================

    /**
     * 解析设备响应
     *
     * 根据响应前缀分发到不同的处理器
     */
    private fun parseResponse(data: ByteArray) {
        val response = String(data, Charsets.UTF_8)
        JxdLogger.d(TAG, "收到响应: $response")

        // 发送到指令响应流
        _commandResponse.tryEmit(response)

        when {
            // 录音状态响应
            response.startsWith(YinLiFangProtocol.RSP_STATUS) -> {
                val value = response.removePrefix(YinLiFangProtocol.RSP_STATUS).toIntOrNull()
                _deviceState.update { it.copy(isRecording = value == 1) }
            }

            // 开始录音响应
            response.startsWith(YinLiFangProtocol.RSP_START_RECORD) -> {
                val fileName = response.removePrefix(YinLiFangProtocol.RSP_START_RECORD)
                _deviceState.update { it.copy(isRecording = true, recordingFileName = fileName) }
            }

            // 停止录音响应
            response.startsWith(YinLiFangProtocol.RSP_STOP_RECORD) -> {
                _deviceState.update { it.copy(isRecording = false, recordingFileName = "", recordingDuration = 0) }
            }

            // 录音过程中状态推送
            response.startsWith(YinLiFangProtocol.RSP_RECORDING) -> {
                parseRecordingStatus(response)
            }

            // 录音错误
            response == YinLiFangProtocol.RSP_RECORD_ERROR -> {
                _deviceState.update { it.copy(lastError = "录音错误") }
            }

            // 存储已满
            response == YinLiFangProtocol.RSP_DISK_FULL -> {
                _deviceState.update { it.copy(lastError = "存储已满") }
            }

            // 电量响应
            response.startsWith(YinLiFangProtocol.RSP_BATTERY) -> {
                val battery = response.removePrefix(YinLiFangProtocol.RSP_BATTERY).toIntOrNull()
                battery?.let {
                    _deviceState.update { state -> state.copy(battery = it) }
                }
            }

            // 存储容量响应
            response.startsWith(YinLiFangProtocol.RSP_STORAGE) -> {
                parseStorageInfo(response)
            }

            // 固件版本响应
            response.startsWith(YinLiFangProtocol.RSP_FW_VERSION) -> {
                val version = response.removePrefix(YinLiFangProtocol.RSP_FW_VERSION)
                _deviceState.update { it.copy(firmwareVersion = version) }
            }

            // WiFi 版本响应
            response.startsWith(YinLiFangProtocol.RSP_WIFI_VERSION) -> {
                val version = response.removePrefix(YinLiFangProtocol.RSP_WIFI_VERSION)
                _deviceState.update { it.copy(wifiVersion = version) }
            }

            // MAC 地址响应
            response.startsWith(YinLiFangProtocol.RSP_MAC) -> {
                val mac = response.removePrefix(YinLiFangProtocol.RSP_MAC)
                _deviceState.update { it.copy(macAddress = mac) }
            }

            // 时间设置成功
            response == YinLiFangProtocol.RSP_TIME_SET_OK -> {
                JxdLogger.d(TAG, "时间设置成功")
            }

            // 密钥配对成功
            response == YinLiFangProtocol.RSP_BIND_OK -> {
                _deviceState.update { it.copy(isBound = true) }
                JxdLogger.d(TAG, "密钥配对成功")
            }

            // 密钥配对失败
            response == YinLiFangProtocol.RSP_BIND_ERROR -> {
                _deviceState.update { it.copy(isBound = false, lastError = "密钥配对失败") }
            }

            // 其他响应
            else -> {
                JxdLogger.d(TAG, "未处理的响应: $response")
        }
    }
    }

    /**
     * 解析录音状态推送
     * 格式: YWT_DEV&RT&REC_NAME&TIME 或 YWT_DEV&RT&NAME&TIME
     */
    private fun parseRecordingStatus(response: String) {
        val parts = response.split("&")
        if (parts.size >= 4) {
            val fileName = parts[2]
            val duration = parts[3].toIntOrNull() ?: 0
            _deviceState.update {
                it.copy(
                    isRecording = true,
                    recordingFileName = fileName,
                    recordingDuration = duration
                )
            }
        }
    }

    /**
     * 解析存储容量信息
     * 格式: YWT_DEV&SPA&F&T (F=剩余MB, T=总MB)
     */
    private fun parseStorageInfo(response: String) {
        val parts = response.split("&")
        if (parts.size >= 4) {
            val free = parts[2].toIntOrNull() ?: -1
            val total = parts[3].toIntOrNull() ?: -1
            _deviceState.update { it.copy(freeStorage = free, totalStorage = total) }
        }
    }

    /**
     * 清除错误信息
     */
    fun clearError() {
        _deviceState.update { it.copy(lastError = null) }
    }
}
