package com.example.myapplication.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.example.myapplication.util.JxdLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * BLE 蓝牙管理器
 *
 * 职责：扫描、连接、MTU 协商、数据收发
 * 不关心任何业务协议，只负责"通道"
 *
 * 设计要点：
 * 1. 只暴露 StateFlow 和 SharedFlow，上层用协程收集
 * 2. receivedData 发出的是原始字节，不做任何解析
 * 3. 连接成功后自动做 MTU 协商（请求 244）
 */
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val MTU_SIZE = 244
        private const val SCAN_TIMEOUT_MS = 30000L // 扫描超时 30 秒
        private const val TARGET_DEVICE_NAME = "YLF20_f0beb49b"
    }

    // ==================== 蓝牙核心对象 ====================

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null

    private var bleScanner: BluetoothLeScanner? = null

    private var isScanning = false

    private var isClassicReceiverRegistered = false

    private var scanTargetDeviceName: String? = null

    // ==================== 状态暴露 ====================

    /** 连接状态 */
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    /** 接收到的原始数据流 */
    private val _receivedData = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedData: SharedFlow<ByteArray> = _receivedData

    /** 扫描到的设备列表 */
    private val _scannedDevices = MutableSharedFlow<BluetoothDevice>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val scannedDevices: SharedFlow<BluetoothDevice> = _scannedDevices

    /** 当前 MTU 大小 */
    private val _mtu = MutableStateFlow(20) // 默认 20 字节
    val mtu: StateFlow<Int> = _mtu

    // ==================== 权限检查 ====================

    /**
     * 检查蓝牙权限
     */
    fun hasBluetoothPermission(): Boolean {
        return PermissionHelper.hasAllPermissions(context)
    }

    /**
     * 检查蓝牙是否可用
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    // ==================== 扫描功能 ====================

    /**
     * 开始扫描 BLE 设备
     *
     * @param filterService 可选的服务 UUID 过滤器。默认不过滤，因为部分设备不会在广播包中携带服务 UUID。
     * @param targetDeviceName 目标设备名称。当前产品只展示指定的音立方设备。
     * @param timeoutMs 扫描超时时间（毫秒）
     */
    @SuppressLint("MissingPermission")
    fun startScan(
        filterService: UUID? = null,
        targetDeviceName: String? = TARGET_DEVICE_NAME,
        timeoutMs: Long = SCAN_TIMEOUT_MS
    ) {
        if (!hasBluetoothPermission()) {
            JxdLogger.e(TAG, "缺少蓝牙权限")
            return
        }

        if (isScanning) {
            JxdLogger.w(TAG, "已在扫描中")
            return
        }

        scanTargetDeviceName = targetDeviceName

        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            JxdLogger.e(TAG, "无法获取 BLE 扫描器")
            return
        }

        _connectionState.value = ConnectionState.SCANNING

        val filters = if (filterService != null) {
            listOf(ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(filterService))
                .build())
        } else {
            emptyList()
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()

        try {
            bleScanner?.startScan(filters, settings, scanCallback)
            isScanning = true
            JxdLogger.d(TAG, "开始扫描 BLE 设备，filterService=$filterService, targetDeviceName=$targetDeviceName")
        } catch (exception: IllegalArgumentException) {
            _connectionState.value = ConnectionState.FAILED
            JxdLogger.e(TAG, "扫描参数错误", exception)
            return
        } catch (exception: SecurityException) {
            _connectionState.value = ConnectionState.FAILED
            JxdLogger.e(TAG, "扫描权限异常", exception)
            return
        }

        // 超时自动停止扫描
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isScanning) {
                stopScan()
            }
        }, timeoutMs)
    }

    /**
     * 停止扫描
     */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return

        bleScanner?.stopScan(scanCallback)
        stopClassicDiscovery()
        isScanning = false

        // 如果还在扫描状态，恢复为未连接
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
        JxdLogger.d(TAG, "停止扫描")
    }

    @SuppressLint("MissingPermission")
    private fun startClassicDiscovery() {
        val adapter = bluetoothAdapter ?: return
        if (!isClassicReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.registerReceiver(classicDiscoveryReceiver, filter)
            isClassicReceiverRegistered = true
        }

        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }

        val started = adapter.startDiscovery()
        JxdLogger.d(TAG, "经典蓝牙发现启动结果: $started")
    }

    @SuppressLint("MissingPermission")
    private fun stopClassicDiscovery() {
        val adapter = bluetoothAdapter
        if (adapter?.isDiscovering == true) {
            adapter.cancelDiscovery()
        }

        if (isClassicReceiverRegistered) {
            runCatching {
                context.unregisterReceiver(classicDiscoveryReceiver)
            }
            isClassicReceiverRegistered = false
        }
    }

    /** 扫描回调 */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            emitScannedDevice(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                emitScannedDevice(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            JxdLogger.e(TAG, "扫描失败: $errorCode")
            isScanning = false
            _connectionState.value = ConnectionState.FAILED
        }
    }

    private val classicDiscoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return

                    JxdLogger.d(TAG, "经典蓝牙发现设备: ${device.name ?: "未知设备"} - ${device.address}")
                    _scannedDevices.tryEmit(device)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    JxdLogger.d(TAG, "经典蓝牙发现结束")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun emitScannedDevice(result: ScanResult) {
        val device = result.device ?: return
        val advertisedName = result.scanRecord?.deviceName
        val displayName = advertisedName ?: device.name ?: "未知设备"
        val targetName = scanTargetDeviceName
        if (targetName != null && displayName != targetName) {
            return
        }

        val serviceUuids = result.scanRecord?.serviceUuids?.joinToString { it.uuid.toString() }.orEmpty()
        JxdLogger.d(
            TAG,
            "发现设备: $displayName - ${device.address}, rssi=${result.rssi}, services=$serviceUuids"
        )
        _scannedDevices.tryEmit(device)
    }

    // ==================== 连接功能 ====================

    /**
     * 连接到指定 BLE 设备
     *
     * @param device 要连接的蓝牙设备
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) {
            JxdLogger.e(TAG, "缺少蓝牙权限")
            return
        }

        stopScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = ConnectionState.CONNECTING
        JxdLogger.d(TAG, "正在连接设备: ${device.name} - ${device.address}, type=${device.type}")

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    /**
     * 断开连接
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (!hasBluetoothPermission()) return

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _mtu.value = 20
        JxdLogger.d(TAG, "断开连接")
    }

    /** GATT 回调 */
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            JxdLogger.d(
                TAG,
                "连接状态变化: status=$status(${gattStatusName(status)}), newState=$newState(${profileStateName(newState)})"
            )

            if (status != BluetoothGatt.GATT_SUCCESS) {
                JxdLogger.e(TAG, "GATT 连接异常断开: ${gattStatusName(status)}")
                closeGatt(gatt)
                _connectionState.value = ConnectionState.FAILED
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    JxdLogger.d(TAG, "设备已连接，正在发现服务...")
                    _connectionState.value = ConnectionState.DISCOVERING_SERVICES
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    }
                    val started = gatt.discoverServices()
                    JxdLogger.d(TAG, "启动服务发现: $started")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    JxdLogger.d(TAG, "设备已断开")
                    closeGatt(gatt)
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                JxdLogger.d(TAG, "服务发现完成")
                // 服务发现完成后，请求更大的 MTU
                requestMtu(gatt)
            } else {
                JxdLogger.e(TAG, "服务发现失败: $status")
                _connectionState.value = ConnectionState.FAILED
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                JxdLogger.d(TAG, "MTU 协商成功: $mtu")
                _mtu.value = mtu
            } else {
                JxdLogger.w(TAG, "MTU 协商失败，使用默认值 20")
                _mtu.value = 20
            }
            // MTU 协商完成后，标记为已连接
            _connectionState.value = ConnectionState.CONNECTED
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // 收到 Notify 数据
            characteristic.value?.let { data ->
                JxdLogger.d(TAG, "收到数据: ${data.size} 字节")
                _receivedData.tryEmit(data)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                characteristic.value?.let { data ->
                    _receivedData.tryEmit(data)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                JxdLogger.d(TAG, "写入成功")
            } else {
                JxdLogger.e(TAG, "写入失败: $status")
            }
        }
    }

    // ==================== MTU 协商 ====================

    /**
     * 请求更大的 MTU
     * 连接成功后自动调用，提升单包有效载荷
     */
    @SuppressLint("MissingPermission")
    private fun requestMtu(gatt: BluetoothGatt) {
        JxdLogger.d(TAG, "请求 MTU: $MTU_SIZE")
        gatt.requestMtu(MTU_SIZE)
    }

    private fun closeGatt(gatt: BluetoothGatt) {
        runCatching {
            gatt.close()
        }
        if (bluetoothGatt == gatt) {
            bluetoothGatt = null
        }
        _mtu.value = 20
    }

    private fun gattStatusName(status: Int): String {
        return when (status) {
            BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
            8 -> "GATT_CONN_TIMEOUT"
            19 -> "GATT_CONN_TERMINATE_PEER_USER"
            22 -> "GATT_CONN_TERMINATE_LOCAL_HOST"
            62 -> "GATT_CONN_FAIL_ESTABLISH"
            133 -> "GATT_ERROR"
            else -> "UNKNOWN"
        }
    }

    private fun profileStateName(state: Int): String {
        return when (state) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "UNKNOWN"
        }
    }

    // ==================== 数据收发 ====================

    /**
     * 订阅 Notify 特征值
     *
     * @param serviceUUID 服务 UUID
     * @param charUUID 特征值 UUID
     */
    @SuppressLint("MissingPermission")
    fun enableNotify(serviceUUID: UUID, charUUID: UUID) {
        val gatt = bluetoothGatt ?: run {
            JxdLogger.e(TAG, "未连接设备")
            return
        }

        val service = gatt.getService(serviceUUID) ?: run {
            JxdLogger.e(TAG, "未找到服务: $serviceUUID")
            return
        }

        val characteristic = service.getCharacteristic(charUUID) ?: run {
            JxdLogger.e(TAG, "未找到特征值: $charUUID")
            return
        }

        gatt.setCharacteristicNotification(characteristic, true)

        // 写入 CCCD 描述符以启用 Notify
        val descriptor = characteristic.getDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        )
        descriptor?.let {
            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(it)
        }

        JxdLogger.d(TAG, "已订阅 Notify: $charUUID")
    }

    /**
     * 写入数据到特征值
     *
     * @param serviceUUID 服务 UUID
     * @param charUUID 特征值 UUID
     * @param data 要写入的数据
     */
    @SuppressLint("MissingPermission")
    fun writeData(serviceUUID: UUID, charUUID: UUID, data: ByteArray) {
        val gatt = bluetoothGatt ?: run {
            JxdLogger.e(TAG, "未连接设备")
            return
        }

        val service = gatt.getService(serviceUUID) ?: run {
            JxdLogger.e(TAG, "未找到服务: $serviceUUID")
            return
        }

        val characteristic = service.getCharacteristic(charUUID) ?: run {
            JxdLogger.e(TAG, "未找到特征值: $charUUID")
            return
        }

        characteristic.value = data
        gatt.writeCharacteristic(characteristic)
        JxdLogger.d(TAG, "写入数据: ${String(data)}")
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取已连接的设备名称
     */
    @SuppressLint("MissingPermission")
    fun getConnectedDeviceName(): String? {
        return bluetoothGatt?.device?.name
    }

    /**
     * 获取已连接设备的 MAC 地址
     */
    @SuppressLint("MissingPermission")
    fun getConnectedDeviceAddress(): String? {
        return bluetoothGatt?.device?.address
    }

    /**
     * 是否已连接
     */
    fun isConnected(): Boolean {
        return _connectionState.value.isReady
    }
}
