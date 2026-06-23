package com.example.myapplication.ble

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.databinding.ActivityBleDemoBinding
import com.example.myapplication.repository.DeviceEvent
import com.example.myapplication.repository.DeviceManager
import com.example.myapplication.util.JxdLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * BLE 设备扫描连接页
 *
 * 状态机：
 *   PREPARING → SCANNING → FOUND / TIMEOUT
 *                ↓              ↓
 *           CONNECTING ← (自动连接第一个)
 *                ↓
 *           CONNECTED → 自动跳转录音页
 *                ↓
 *         (从录音页返回) → 显示已连接状态，手动进入
 */
class BleDemoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBleDemoBinding

    private val scannedDeviceList = mutableListOf<BluetoothDevice>()
    private lateinit var deviceListAdapter: ArrayAdapter<String>
    private var hasOpenedRecorder = false
    private var scanAnimatorSet: AnimatorSet? = null
    private var scanTimeoutJob: Job? = null

    companion object {
        private const val SCAN_TIMEOUT_MS = 15_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBleDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化全局设备管理器
        DeviceManager.init(this)

        setupUI()
        observeEvents()

        // 自动检查权限并扫描
        checkPermissionAndScan()
    }

    // ==================== 初始化 ====================

    private fun setupUI() {
        deviceListAdapter = ArrayAdapter(this, R.layout.item_ble_device, R.id.tvDeviceName)
        binding.lvDevices.adapter = deviceListAdapter

        binding.lvDevices.setOnItemClickListener { _, _, position, _ ->
            if (position < scannedDeviceList.size) {
                connectDevice(scannedDeviceList[position])
            }
        }

        binding.btnRetry.setOnClickListener {
            checkPermissionAndScan()
        }

        binding.btnEnterRecording.setOnClickListener {
            openRecorder(force = true)
        }
    }

    // ==================== 事件订阅 ====================

    private fun observeEvents() {
        lifecycleScope.launch {
            DeviceManager.events.collect { event ->
                when (event) {
                    is DeviceEvent.ConnectionChanged -> onConnectionChanged(event.state)
                    is DeviceEvent.DeviceDiscovered -> onDeviceDiscovered(event.name, event.address)
                    is DeviceEvent.Error -> Toast.makeText(this@BleDemoActivity, event.message, Toast.LENGTH_SHORT).show()
                    else -> {} // 其他事件在录音页处理
                }
            }
        }
    }

    // ==================== 连接状态处理 ====================

    @SuppressLint("MissingPermission")
    private fun onConnectionChanged(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                stopScanAnimation()
                if (hasOpenedRecorder) {
                    showEmptyState("设备已断开连接")
                } else {
                    setStatus("准备扫描")
                }
            }
            ConnectionState.SCANNING -> {
                showScanningState()
            }
            ConnectionState.CONNECTING -> {
                setStatus("正在连接设备…")
                stopScanAnimation()
                hideAllSections()
            }
            ConnectionState.DISCOVERING_SERVICES -> {
                setStatus("正在初始化服务…")
            }
            ConnectionState.CONNECTED -> {
                onDeviceConnected()
            }
            ConnectionState.FAILED -> {
                onConnectionFailed()
            }
        }
    }

    private fun onDeviceConnected() {
        val deviceName = DeviceManager.getConnectedDeviceName() ?: "设备"
        setStatus("已连接 $deviceName")
        stopScanAnimation()
        scanTimeoutJob?.cancel()

        binding.tvConnectedName.text = deviceName

        if (!hasOpenedRecorder) {
            appendLog("连接成功，自动跳转录音页")
            openRecorder()
        } else {
            showConnectedState()
        }
    }

    private fun onConnectionFailed() {
        setStatus("连接失败")
        stopScanAnimation()
        Toast.makeText(this, "连接失败，请重试", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            delay(1500)
            if (DeviceManager.connectionState.value == ConnectionState.FAILED) {
                showEmptyState("连接失败，请确认设备在附近")
            }
        }
    }

    // ==================== 设备发现 ====================

    @SuppressLint("MissingPermission")
    private fun onDeviceDiscovered(name: String, address: String) {
        if (scannedDeviceList.any { it.address == address }) return

        // 设备对象通过 scannedDevices flow 的 addScannedDevice 处理
        // 这里只做日志
        appendLog("发现: $name ($address)")
    }

    /**
     * 处理扫描到的真实 BluetoothDevice（从 Flow 获取）
     */
    private fun addScannedDevice(device: BluetoothDevice) {
        if (scannedDeviceList.any { it.address == device.address }) return

        scannedDeviceList.add(device)
        val name = device.name ?: "未知设备"
        deviceListAdapter.add(name)
        deviceListAdapter.notifyDataSetChanged()

        appendLog("发现: $name (${device.address})")

        scanTimeoutJob?.cancel()
        showDeviceList()

        // 自动连接第一个
        if (scannedDeviceList.size == 1) {
            connectDevice(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        DeviceManager.connect(device)
        setStatus("正在连接 ${device.name ?: "设备"}…")
        appendLog("开始连接: ${device.name ?: device.address}")
    }

    // ==================== UI 状态 ====================

    private fun showScanningState() {
        setStatus("正在扫描附近设备…")
        startScanAnimation()
        binding.deviceListContainer.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        binding.connectedState.visibility = View.GONE
        binding.tvDeviceCount.visibility = View.GONE
    }

    private fun showDeviceList() {
        binding.deviceListContainer.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        binding.connectedState.visibility = View.GONE
        binding.tvDeviceCount.visibility = View.VISIBLE
        binding.tvDeviceCount.text = "发现 ${scannedDeviceList.size} 台设备"
    }

    private fun showEmptyState(message: String = "未发现附近设备") {
        stopScanAnimation()
        binding.deviceListContainer.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.connectedState.visibility = View.GONE
        binding.tvDeviceCount.visibility = View.GONE
        setStatus(message)
    }

    private fun showConnectedState() {
        stopScanAnimation()
        binding.deviceListContainer.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        binding.connectedState.visibility = View.VISIBLE
        binding.tvDeviceCount.visibility = View.GONE
    }

    private fun hideAllSections() {
        binding.deviceListContainer.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        binding.connectedState.visibility = View.GONE
        binding.tvDeviceCount.visibility = View.GONE
    }

    private fun setStatus(text: String) {
        binding.tvStatus.text = text
    }

    // ==================== 扫描动画 ====================

    private fun startScanAnimation() {
        if (scanAnimatorSet?.isRunning == true) return

        binding.scanRingOuter.visibility = View.VISIBLE
        binding.scanRingInner.visibility = View.VISIBLE

        val outerScaleX = ObjectAnimator.ofFloat(binding.scanRingOuter, "scaleX", 0.88f, 1.0f).apply {
            duration = 2200; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE
        }
        val outerScaleY = ObjectAnimator.ofFloat(binding.scanRingOuter, "scaleY", 0.88f, 1.0f).apply {
            duration = 2200; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE
        }
        val outerAlpha = ObjectAnimator.ofFloat(binding.scanRingOuter, "alpha", 0.4f, 1.0f).apply {
            duration = 2200; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE
        }
        val innerRotation = ObjectAnimator.ofFloat(binding.scanRingInner, "rotation", 0f, 360f).apply {
            duration = 10000; repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator()
        }
        val innerAlpha = ObjectAnimator.ofFloat(binding.scanRingInner, "alpha", 0.3f, 0.7f).apply {
            duration = 2800; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE
        }

        scanAnimatorSet = AnimatorSet().apply {
            playTogether(outerScaleX, outerScaleY, outerAlpha, innerRotation, innerAlpha)
            start()
        }
    }

    private fun stopScanAnimation() {
        scanAnimatorSet?.cancel()
        scanAnimatorSet = null
        binding.scanRingOuter.animate().scaleX(1f).scaleY(1f).alpha(0.3f).setDuration(200).start()
        binding.scanRingInner.animate().alpha(0.2f).setDuration(200).start()
    }

    // ==================== 权限处理 ====================

    private fun checkPermissionAndScan() {
        scannedDeviceList.clear()
        deviceListAdapter.clear()
        showScanningState()

        if (!DeviceManager.isBluetoothEnabled()) {
            setStatus("请先开启蓝牙")
            stopScanAnimation()
            showEmptyState("请先开启蓝牙")
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return
        }

        if (!PermissionHelper.isLocationEnabled(this)) {
            setStatus("请先开启定位服务")
            stopScanAnimation()
            showEmptyState("请先开启定位服务")
            Toast.makeText(this, "请先开启手机定位服务", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        PermissionHelper(this).requestPermission(
            onGranted = {
                appendLog("权限已授予")
                startScanWithTimeout()
            },
            onDenied = { deniedPermissions ->
                val names = deniedPermissions.map { PermissionHelper.getPermissionDisplayName(it) }
                stopScanAnimation()
                showEmptyState("需要蓝牙权限才能扫描设备")
                appendLog("权限被拒绝: ${names.joinToString(", ")}")
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun startScanWithTimeout() {
        DeviceManager.startScan()
        appendLog("开始扫描")

        // 同时监听 scannedDevices flow 来获取 BluetoothDevice 对象
        lifecycleScope.launch {
            DeviceManager.scannedDevices.collect { device ->
                addScannedDevice(device)
            }
        }

        scanTimeoutJob?.cancel()
        scanTimeoutJob = lifecycleScope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (scannedDeviceList.isEmpty() &&
                DeviceManager.connectionState.value == ConnectionState.SCANNING
            ) {
                DeviceManager.stopScan()
                showEmptyState("未发现附近设备")
                appendLog("扫描超时，未发现设备")
            }
        }
    }

    // ==================== 工具 ====================

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        JxdLogger.d("BleDemoActivity", "[$time] $message")
    }

    private fun openRecorder(force: Boolean = false) {
        if (!force && hasOpenedRecorder) return
        hasOpenedRecorder = true
        startActivity(Intent(this, RealtimeRecordingActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        if (DeviceManager.connectionState.value == ConnectionState.CONNECTED) {
            hasOpenedRecorder = true
            showConnectedState()
        } else {
            hasOpenedRecorder = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanAnimatorSet?.cancel()
        scanTimeoutJob?.cancel()
        DeviceManager.stopScan()
    }
}
