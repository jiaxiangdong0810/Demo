package com.example.myapplication.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.databinding.ActivityBleDemoBinding
import com.example.myapplication.repository.DeviceRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * BLE 音频流 Demo 界面
 *
 * 展示蓝牙连接状态、设备信息、录音控制等功能
 */
class BleDemoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBleDemoBinding
    private lateinit var bleManager: BleManager
    private lateinit var repository: DeviceRepository
    private lateinit var permissionHelper: PermissionHelper

    /** 扫描到的设备列表 */
    private val scannedDeviceList = mutableListOf<BluetoothDevice>()
    private lateinit var deviceListAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBleDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initBle()
        initPermission()
        setupUI()
        observeState()
    }

    // ==================== 初始化 ====================

    private fun initBle() {
        bleManager = BleManager(this)
        repository = DeviceRepository(bleManager)
    }

    private fun initPermission() {
        permissionHelper = PermissionHelper(this)
    }

    private fun setupUI() {
        // 设备列表适配器
        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        binding.lvDevices.adapter = deviceListAdapter

        // 扫描按钮
        binding.btnScan.setOnClickListener {
            if (bleManager.connectionState.value == ConnectionState.SCANNING) {
                bleManager.stopScan()
            } else {
                checkPermissionAndScan()
            }
        }

        // 断开按钮
        binding.btnDisconnect.setOnClickListener {
            repository.disconnect()
            scannedDeviceList.clear()
            deviceListAdapter.clear()
            updateScanSummary()
        }

        // 录音控制按钮
        binding.btnRecord.setOnClickListener {
            if (repository.deviceState.value.isRecording) {
                repository.stopRecording()
            } else {
                repository.startRecording()
            }
        }

        // 查询电量按钮
        binding.btnQueryBattery.setOnClickListener {
            repository.queryBattery()
        }

        // 查询存储按钮
        binding.btnQueryStorage.setOnClickListener {
            repository.queryStorage()
        }

        // 设备列表点击连接
        binding.lvDevices.setOnItemClickListener { _, _, position, _ ->
            val device = scannedDeviceList[position]
            repository.connect(device)
            bleManager.stopScan()
        }
    }

    // ==================== 状态观察 ====================

    @SuppressLint("MissingPermission")
    private fun observeState() {
        // 观察连接状态
        lifecycleScope.launch {
            repository.connectionState.collectLatest { state ->
                updateConnectionUI(state)
            }
        }

        // 观察设备状态
        lifecycleScope.launch {
            repository.deviceState.collectLatest { state ->
                updateDeviceUI(state)
            }
        }

        // 观察扫描到的设备
        lifecycleScope.launch {
            repository.scannedDevices.collectLatest { device ->
                addScannedDevice(device)
            }
        }

        // 观察指令响应
        lifecycleScope.launch {
            repository.commandResponse.collectLatest { response ->
                appendLog("收到: $response")
            }
        }

        // 观察音频流（Demo 阶段只记录日志）
        lifecycleScope.launch {
            repository.audioStream.collectLatest { data ->
                appendLog("音频数据: ${data.size} 字节")
            }
        }
    }

    // ==================== UI 更新 ====================

    @SuppressLint("MissingPermission")
    private fun updateConnectionUI(state: ConnectionState) {
        binding.tvConnectionStatus.text = when (state) {
            ConnectionState.DISCONNECTED -> "未连接"
            ConnectionState.SCANNING -> "扫描中..."
            ConnectionState.CONNECTING -> "连接中..."
            ConnectionState.DISCOVERING_SERVICES -> "发现服务中..."
            ConnectionState.CONNECTED -> "已连接"
            ConnectionState.FAILED -> "连接失败"
        }

        // 更新按钮状态
        binding.btnScan.isEnabled = state != ConnectionState.CONNECTING &&
                state != ConnectionState.DISCOVERING_SERVICES
        binding.btnScan.text = if (state == ConnectionState.SCANNING) "停止扫描" else "扫描设备"
        binding.btnDisconnect.isEnabled = state.isReady
        binding.btnRecord.isEnabled = state.isReady
        binding.btnQueryBattery.isEnabled = state.isReady
        binding.btnQueryStorage.isEnabled = state.isReady

        val showDevicePanel = state == ConnectionState.CONNECTING ||
                state == ConnectionState.DISCOVERING_SERVICES ||
                state == ConnectionState.CONNECTED
        binding.panelScan.visibility = if (showDevicePanel) View.GONE else View.VISIBLE
        binding.panelDevice.visibility = if (showDevicePanel) View.VISIBLE else View.GONE
        updateScanSummary()

        // 连接成功后订阅 Notify
        if (state == ConnectionState.CONNECTED) {
            repository.subscribeAudioStream()
            repository.subscribeCommandNotify()
            appendLog("已连接设备: ${bleManager.getConnectedDeviceName()}")

            // 查询设备信息
            repository.queryBattery()
            repository.queryStorage()
            repository.queryFirmwareVersion()
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateDeviceUI(state: com.example.myapplication.repository.DeviceState) {
        // 设备信息
        binding.tvDeviceName.text = "设备: ${state.deviceName}"
        binding.tvMacAddress.text = "MAC: ${state.macAddress}"
        binding.tvBattery.text = "电量: ${if (state.battery >= 0) "${state.battery}%" else "未知"}"
        binding.tvStorage.text = "存储: ${if (state.freeStorage >= 0) "${state.freeStorage}/${state.totalStorage} MB" else "未知"}"
        binding.tvFirmware.text = "固件: ${state.firmwareVersion.ifEmpty { "未知" }}"

        // 录音状态
        binding.tvRecordingStatus.text = if (state.isRecording) {
            "录音中: ${state.recordingFileName} (${state.recordingDuration}秒)"
        } else {
            "未录音"
        }
        binding.btnRecord.text = if (state.isRecording) "停止录音" else "开始录音"

        // 错误信息
        state.lastError?.let { error ->
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            repository.clearError()
        }
    }

    @SuppressLint("MissingPermission")
    private fun addScannedDevice(device: BluetoothDevice) {
        // 避免重复添加
        if (scannedDeviceList.any { it.address == device.address }) return

        scannedDeviceList.add(device)
        val name = device.name ?: "未知设备"
        deviceListAdapter.add("$name  ${deviceTypeLabel(device)}\n${device.address}")
        deviceListAdapter.notifyDataSetChanged()
        updateScanSummary()
        appendLog("发现设备: $name - ${device.address}")
    }

    private fun deviceTypeLabel(device: BluetoothDevice): String {
        return when (device.type) {
            BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "双模"
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "经典蓝牙"
            else -> "未知类型"
        }
    }

    private fun updateScanSummary() {
        binding.tvScanSummary.text = when (bleManager.connectionState.value) {
            ConnectionState.SCANNING -> "正在扫描附近设备，已发现 ${scannedDeviceList.size} 台。"
            ConnectionState.FAILED -> "扫描或连接失败，请确认设备靠近手机并处于可连接状态。"
            else -> if (scannedDeviceList.isEmpty()) {
                "打开蓝牙和定位后开始扫描，选择设备即可连接。"
            } else {
                "已发现 ${scannedDeviceList.size} 台设备，点击列表中的设备进行连接。"
            }
        }
    }

    // ==================== 权限处理 ====================

    /**
     * 检查权限并开始扫描
     *
     * 流程：
     * 1. 检查蓝牙是否开启
     * 2. 检查权限是否已授予
     * 3. 如果没有权限，请求权限（系统弹窗）
     * 4. 权限授予后开始扫描
     * 5. 如果拒绝，弹窗说明或引导去设置
     */
    private fun checkPermissionAndScan() {
        // 先检查蓝牙是否开启
        if (!bleManager.isBluetoothEnabled()) {
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return
        }

        if (!PermissionHelper.isLocationEnabled(this)) {
            appendLog("手机定位服务未开启，可能导致扫描不到蓝牙设备")
            Toast.makeText(this, "请先开启手机定位服务", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        // 使用 PermissionHelper 请求权限
        permissionHelper.requestPermission(
            onGranted = {
                // 权限已授予，开始扫描
                appendLog("蓝牙权限已授予")
                startScan()
            },
            onDenied = { deniedPermissions ->
                // 权限被拒绝（包括普通拒绝和永久拒绝）
                // PermissionHelper 内部会弹窗处理，这里只是记录日志
                val names = deniedPermissions.map { PermissionHelper.getPermissionDisplayName(it) }
                appendLog("权限被拒绝: ${names.joinToString(", ")}")
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        scannedDeviceList.clear()
        deviceListAdapter.clear()
        updateScanSummary()
        repository.startScan()
        appendLog("开始扫描...")
    }

    // ==================== 日志 ====================

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val log = "[$time] $message"
        binding.tvLog.append("$log\n")

        // 自动滚动到底部
        binding.svLog.post {
            binding.svLog.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.stopScan()
        repository.disconnect()
    }
}
