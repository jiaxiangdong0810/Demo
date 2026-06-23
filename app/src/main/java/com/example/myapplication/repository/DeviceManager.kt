package com.example.myapplication.repository

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.example.myapplication.ble.BleManager
import com.example.myapplication.ble.ConnectionState
import com.example.myapplication.util.JxdLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 设备管理器（全局单例）
 *
 * 职责：
 * 1. 持有 BleManager 和 DeviceRepository 的唯一实例
 * 2. 监听底层数据变化，自动通过 EventBus 推送给所有页面
 * 3. 页面只需订阅 EventBus，不需要直接操作 BLE
 *
 * 用法：
 *   // 在 Application 或首个 Activity 初始化
 *   DeviceManager.init(context)
 *
 *   // 页面订阅事件
 *   DeviceManager.events.collect { event -> ... }
 *
 *   // 操作设备
 *   DeviceManager.scan()
 *   DeviceManager.connect(device)
 *   DeviceManager.startRecording()
 */
object DeviceManager {

    private const val TAG = "DeviceManager"

    private lateinit var bleManager: BleManager
    private lateinit var repository: DeviceRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 是否已初始化 */
    val isInitialized: Boolean get() = ::bleManager.isInitialized

    /** 事件流 — 页面订阅这个 */
    val events = DeviceEventBus.events

    /** 连接状态 */
    val connectionState get() = bleManager.connectionState

    /** 设备状态快照 */
    val deviceState get() = repository.deviceState

    /** 扫描到的设备 */
    val scannedDevices get() = repository.scannedDevices

    /** 实时录音状态 */
    val realtimeRecordingState get() = repository.realtimeRecordingState

    // ==================== 初始化 ====================

    /**
     * 初始化，整个 App 生命周期只需调用一次
     */
    fun init(context: Context) {
        if (isInitialized) return

        val appContext = context.applicationContext
        bleManager = BleManager(appContext)
        repository = DeviceRepository(bleManager, appContext)

        observeAndPush()
        JxdLogger.d(TAG, "DeviceManager 初始化完成")
    }

    /**
     * 监听底层数据变化，自动推送到 EventBus
     */
    private fun observeAndPush() {
        // 连接状态
        scope.launch {
            bleManager.connectionState.collect { state ->
                DeviceEventBus.emit(DeviceEvent.ConnectionChanged(state))

                // 连接成功后自动订阅 notify
                if (state == ConnectionState.CONNECTED) {
                    repository.subscribeCommandNotify()
                    // 延迟查询设备信息
                    kotlinx.coroutines.delay(500)
                    queryDeviceInfo()
                }
            }
        }

        // 设备状态
        scope.launch {
            repository.deviceState.collect { state ->
                // 只在数据有效时推送
                if (state.battery >= 0) {
                    DeviceEventBus.emit(DeviceEvent.BatteryChanged(state.battery))
                }
                if (state.freeStorage >= 0) {
                    DeviceEventBus.emit(DeviceEvent.StorageChanged(state.freeStorage, state.totalStorage))
                }
                if (state.firmwareVersion.isNotEmpty()) {
                    DeviceEventBus.emit(DeviceEvent.FirmwareChanged(state.firmwareVersion))
                }
                if (state.lastError != null) {
                    DeviceEventBus.emit(DeviceEvent.Error(state.lastError))
                    repository.clearError()
                }
            }
        }

        // 扫描到的设备
        scope.launch {
            repository.scannedDevices.collect { device ->
                val name = device.name ?: "未知设备"
                DeviceEventBus.emit(DeviceEvent.DeviceDiscovered(name, device.address))
            }
        }
    }

    /**
     * 连接成功后查询设备信息（电量、存储、固件）
     */
    private fun queryDeviceInfo() {
        scope.launch {
            repository.queryBattery()
            kotlinx.coroutines.delay(150)
            repository.queryStorage()
            kotlinx.coroutines.delay(150)
            repository.queryFirmwareVersion()
        }
    }

    // ==================== 操作接口 ====================

    fun isBluetoothEnabled(): Boolean = bleManager.isBluetoothEnabled()

    @SuppressLint("MissingPermission")
    fun startScan() {
        repository.startScan()
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        repository.connect(device)
        bleManager.stopScan()
    }

    fun startRecording() {
        repository.startRecording()
    }

    fun stopRecording() {
        repository.stopRecording()
    }

    fun beginRealtimeCapture() {
        repository.beginRealtimeCapture()
    }

    fun getConnectedDeviceName(): String? {
        return bleManager.getConnectedDeviceName()
    }

    fun subscribeCommandNotify() {
        repository.subscribeCommandNotify()
    }
}
