package com.example.myapplication.repository

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import com.example.myapplication.asr.SherpaOnnxTranscriber
import com.example.myapplication.audio.Mp3FileDecoder
import com.example.myapplication.ble.BleManager
import com.example.myapplication.ble.ConnectionState
import com.example.myapplication.protocol.YinLiFangProtocol
import com.example.myapplication.util.JxdLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
class DeviceRepository(
    private val bleManager: BleManager,
    context: Context
) {

    companion object {
        private const val TAG = "DeviceRepository"
        private const val TRANSCRIPTION_INTERVAL_MS = 5_000L
        private const val MIN_TRANSCRIPTION_BYTES = 16 * 1024L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appContext = context.applicationContext

    private var useShortCommandPrefix = false

    private var lastDocumentCommand: String? = null
    private var recordingOutputStream: FileOutputStream? = null
    private var bytesInRateWindow = 0L
    private var rateWindowStartMillis = 0L
    private var lastTranscriptionAtMillis = 0L
    private var lastTranscribedBytes = 0L
    @Volatile
    private var isTranscriptionRunning = false

    private val mp3FileDecoder = Mp3FileDecoder()
    private val transcriber = SherpaOnnxTranscriber(appContext)

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

    /** 当前实时录音接收状态 */
    private val _realtimeRecordingState = MutableStateFlow(RealtimeRecordingState())
    val realtimeRecordingState: StateFlow<RealtimeRecordingState> =
        _realtimeRecordingState.asStateFlow()

    /** 本地语音转文字状态 */
    private val _transcriptionState = MutableStateFlow(TranscriptionState())
    val transcriptionState: StateFlow<TranscriptionState> =
        _transcriptionState.asStateFlow()

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
                if (
                    (state == ConnectionState.DISCONNECTED || state == ConnectionState.FAILED) &&
                    _realtimeRecordingState.value.isCapturing
                ) {
                    stopRealtimeCapture("蓝牙连接已断开")
                }
            }
        }

        // 监听 Notify 数据：指令和音频必须按不同特征值分开处理
        scope.launch {
            bleManager.notifyPackets.collect { packet ->
                when (packet.characteristicUuid) {
                    YinLiFangProtocol.COMMAND_NOTIFY_UUID -> parseResponse(packet.data)
                    YinLiFangProtocol.AUDIO_NOTIFY_UUID -> {
                        handleAudioPacket(packet.data)
                        _audioStream.tryEmit(packet.data)
                    }
                    else -> JxdLogger.d(TAG, "未知 Notify: ${packet.characteristicUuid}")
                }
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
        stopRealtimeCapture()
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

    fun beginRealtimeCapture() {
        val now = System.currentTimeMillis()
        bytesInRateWindow = 0L
        rateWindowStartMillis = now
        lastTranscriptionAtMillis = 0L
        lastTranscribedBytes = 0L
        isTranscriptionRunning = false
        _realtimeRecordingState.value = RealtimeRecordingState(
            isCapturing = true,
            startedAtMillis = now
        )
        _transcriptionState.value = TranscriptionState(
            isEnabled = true,
            isModelReady = transcriber.isReady(),
            statusText = transcriber.readinessError() ?: "等待音频"
        )
        JxdLogger.d(TAG, "准备接收实时录音")
    }

    fun stopRealtimeCapture(error: String? = null) {
        val pathBeforeClose = _realtimeRecordingState.value.localPath
        closeRecordingFile()
        bytesInRateWindow = 0L
        rateWindowStartMillis = 0L
        _realtimeRecordingState.update {
            it.copy(
                isCapturing = false,
                bytesPerSecond = 0L,
                lastError = error
            )
        }
        if (error == null && pathBeforeClose.isNotEmpty()) {
            requestTranscription(File(pathBeforeClose), finalPass = true)
        }
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
        lastDocumentCommand = cmd
        val actualCommand = if (useShortCommandPrefix) {
            YinLiFangProtocol.toShortCommand(cmd)
        } else {
            cmd
        }
        val data = actualCommand.toByteArray()
        bleManager.writeData(
            YinLiFangProtocol.SERVICE_UUID,
            YinLiFangProtocol.WRITE_UUID,
            data
        )
        JxdLogger.d(TAG, "发送指令: $actualCommand")
    }

    // ==================== 协议解析 ====================

    /**
     * 解析设备响应
     *
     * 根据响应前缀分发到不同的处理器
     */
    private fun parseResponse(data: ByteArray) {
        val rawResponse = String(data, Charsets.UTF_8)
        val response = YinLiFangProtocol.normalizeResponse(rawResponse)
        JxdLogger.d(TAG, "收到响应: $response")

        // 发送到指令响应流
        _commandResponse.tryEmit(response)

        if (response == YinLiFangProtocol.RSP_UNKNOWN && !useShortCommandPrefix) {
            retryLastCommandWithShortPrefix()
            return
        }

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
                updateCaptureFileName(fileName)
            }

            // 停止录音响应
            response.startsWith(YinLiFangProtocol.RSP_STOP_RECORD) -> {
                _deviceState.update { it.copy(isRecording = false, recordingFileName = "", recordingDuration = 0) }
                stopRealtimeCapture()
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

    private fun retryLastCommandWithShortPrefix() {
        val lastCommand = lastDocumentCommand ?: return
        val retryCommand = YinLiFangProtocol.toShortCommand(lastCommand)
        if (retryCommand == lastCommand) return

        useShortCommandPrefix = true
        JxdLogger.w(TAG, "设备不识别文档长前缀，切换短前缀重试: $retryCommand")
        _commandResponse.tryEmit("设备使用短协议，重试: $retryCommand")
        sendCommand(lastCommand)
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

    private fun handleAudioPacket(data: ByteArray) {
        if (!_realtimeRecordingState.value.isCapturing) return

        try {
            ensureRecordingFile()
            recordingOutputStream?.write(data)
            recordingOutputStream?.flush()
            updateAudioStats(data.size)
            maybeRequestRealtimeTranscription()
        } catch (exception: IOException) {
            JxdLogger.e(TAG, "写入实时录音失败", exception)
            stopRealtimeCapture("保存音频失败: ${exception.message ?: "未知错误"}")
        }
    }

    private fun ensureRecordingFile() {
        if (recordingOutputStream != null) return

        val current = _realtimeRecordingState.value
        val fileName = sanitizeFileName(current.fileName.ifEmpty {
            "realtime_${System.currentTimeMillis()}.mp3"
        })
        val file = File(recordingDirectory(), fileName.ensureMp3Suffix())
        file.parentFile?.mkdirs()
        recordingOutputStream = FileOutputStream(file, false)
        _realtimeRecordingState.update { it.copy(localPath = file.absolutePath) }
    }

    private fun updateCaptureFileName(fileName: String) {
        _realtimeRecordingState.update {
            it.copy(
                isCapturing = true,
                fileName = fileName,
                startedAtMillis = if (it.startedAtMillis == 0L) System.currentTimeMillis() else it.startedAtMillis
            )
        }
    }

    private fun updateAudioStats(packetSize: Int) {
        val now = System.currentTimeMillis()
        if (rateWindowStartMillis == 0L) {
            rateWindowStartMillis = now
        }
        bytesInRateWindow += packetSize

        val elapsed = now - rateWindowStartMillis
        val currentRate = if (elapsed >= 1000L) {
            val rate = bytesInRateWindow * 1000L / elapsed
            bytesInRateWindow = 0L
            rateWindowStartMillis = now
            rate
        } else {
            _realtimeRecordingState.value.bytesPerSecond
        }

        _realtimeRecordingState.update {
            it.copy(
                totalBytes = it.totalBytes + packetSize,
                lastPacketBytes = packetSize,
                packetCount = it.packetCount + 1,
                bytesPerSecond = currentRate
            )
        }
    }

    private fun maybeRequestRealtimeTranscription() {
        val state = _realtimeRecordingState.value
        if (!state.isCapturing || state.localPath.isEmpty()) return
        if (state.totalBytes < MIN_TRANSCRIPTION_BYTES) return

        val now = System.currentTimeMillis()
        val enoughTimePassed = now - lastTranscriptionAtMillis >= TRANSCRIPTION_INTERVAL_MS
        val enoughNewBytes = state.totalBytes - lastTranscribedBytes >= MIN_TRANSCRIPTION_BYTES
        if (!enoughTimePassed || !enoughNewBytes) return

        requestTranscription(File(state.localPath), finalPass = false)
    }

    private fun requestTranscription(file: File, finalPass: Boolean) {
        if (isTranscriptionRunning) return
        if (!file.exists() || file.length() == 0L) return

        isTranscriptionRunning = true
        lastTranscriptionAtMillis = System.currentTimeMillis()
        lastTranscribedBytes = _realtimeRecordingState.value.totalBytes
        _transcriptionState.update {
            it.copy(
                isEnabled = true,
                isModelReady = transcriber.isReady(),
                isTranscribing = true,
                statusText = if (finalPass) "正在生成最终文字" else "正在识别当前录音",
                lastError = null
            )
        }

        scope.launch {
            try {
                if (finalPass) {
                    closeRecordingFile()
                } else {
                    recordingOutputStream?.flush()
                }

                val pcm = mp3FileDecoder.decode(file)
                if (pcm.samples.isEmpty()) {
                    _transcriptionState.update {
                        it.copy(
                            isTranscribing = false,
                            statusText = "还没有可识别的音频"
                        )
                    }
                    return@launch
                }

                val result = transcriber.transcribe(pcm)
                result.fold(
                    onSuccess = { text ->
                        _transcriptionState.update {
                            it.copy(
                                isModelReady = true,
                                isTranscribing = false,
                                latestText = text,
                                finalText = text.ifEmpty { it.finalText },
                                statusText = if (text.isEmpty()) "暂未识别到文字" else "已更新转写内容",
                                lastError = null
                            )
                        }
                    },
                    onFailure = { throwable ->
                        _transcriptionState.update {
                            it.copy(
                                isModelReady = false,
                                isTranscribing = false,
                                statusText = "转写未就绪",
                                lastError = throwable.message ?: "语音识别失败"
                            )
                        }
                    }
                )
            } catch (exception: Exception) {
                JxdLogger.e(TAG, "本地转写失败", exception)
                _transcriptionState.update {
                    it.copy(
                        isTranscribing = false,
                        statusText = "转写失败",
                        lastError = exception.message ?: "未知错误"
                    )
                }
            } finally {
                isTranscriptionRunning = false
            }
        }
    }

    private fun closeRecordingFile() {
        runCatching {
            recordingOutputStream?.flush()
            recordingOutputStream?.close()
        }
        recordingOutputStream = null
    }

    private fun recordingDirectory(): File {
        val externalMusic = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        return File(externalMusic ?: appContext.filesDir, "yinlifang_recordings")
    }

    private fun sanitizeFileName(value: String): String {
        return value.replace(Regex("""[\\/:*?"<>|]"""), "_").ifEmpty {
            "realtime_${System.currentTimeMillis()}.mp3"
        }
    }

    private fun String.ensureMp3Suffix(): String {
        return if (endsWith(".mp3", ignoreCase = true)) this else "$this.mp3"
    }

    /**
     * 清除错误信息
     */
    fun clearError() {
        _deviceState.update { it.copy(lastError = null) }
    }
}
