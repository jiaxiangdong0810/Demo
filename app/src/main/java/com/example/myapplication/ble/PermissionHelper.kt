package com.example.myapplication.ble

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * 蓝牙权限处理帮助类
 *
 * 权限处理流程：
 * 1. 检查权限是否已授予 → 直接回调 onGranted
 * 2. 直接请求系统权限弹窗
 * 3. 根据回调结果处理：
 *    - 全部授予 → onGranted
 *    - 部分拒绝（可再次请求）→ 弹窗说明 + 可重新请求
 *    - 部分永久拒绝（勾选不再询问）→ 引导去设置页面
 *
 * 关键点：
 * shouldShowRequestPermissionRationale() 在首次安装时返回 false，
 * 在永久拒绝时也返回 false，无法区分这两种情况。
 * 所以必须先请求权限，再根据回调判断。
 */
class PermissionHelper(private val activity: ComponentActivity) {

    /** 权限授予回调 */
    private var onGranted: (() -> Unit)? = null

    /** 权限拒绝回调 */
    private var onDenied: ((List<String>) -> Unit)? = null

    /** 当前请求的权限列表（用于在回调中判断） */
    private var requestedPermissions: Array<String> = emptyArray()

    /** 权限请求启动器 */
    private val launcher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            handlePermissionResult(permissions)
        }

    /**
     * 请求蓝牙权限
     *
     * @param onGranted 所有权限都授予时回调
     * @param onDenied 权限被拒绝时回调（包含被拒绝的权限列表）
     */
    fun requestPermission(
        onGranted: () -> Unit,
        onDenied: ((List<String>) -> Unit)? = null
    ) {
        this.onGranted = onGranted
        this.onDenied = onDenied

        // 先检查是否已有权限
        if (hasAllPermissions(activity)) {
            onGranted.invoke()
            return
        }

        // 获取未授予的权限
        val ungrantedPermissions = getUngrantedPermissions()
        if (ungrantedPermissions.isEmpty()) {
            onGranted.invoke()
            return
        }

        // 记录请求的权限
        requestedPermissions = ungrantedPermissions

        // 直接请求权限（系统弹窗）
        launcher.launch(ungrantedPermissions)
    }

    /**
     * 处理权限请求结果
     *
     * 核心逻辑：
     * - 全部授予 → 回调 onGranted
     * - 有拒绝 → 检查是否可以再次请求
     *   - 可以再次请求（普通拒绝）→ 显示说明弹窗，用户可选择重新请求
     *   - 不可再次请求（永久拒绝）→ 显示引导去设置弹窗
     */
    private fun handlePermissionResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.all { it.value }

        if (allGranted) {
            // 所有权限都授予了
            onGranted?.invoke()
            return
        }

        // 有权限被拒绝，找出被拒绝的权限
        val deniedPermissions = permissions.filter { !it.value }.map { it.key }

        // 判断是否是永久拒绝（用户勾选了"不再询问"）
        // shouldShowRequestPermissionRationale 返回 false 表示永久拒绝
        val permanentlyDenied = deniedPermissions.filter { permission ->
            !activity.shouldShowRequestPermissionRationale(permission)
        }

        if (permanentlyDenied.isNotEmpty()) {
            // 有权限被永久拒绝，引导去设置页面
            showGoToSettingsDialog(permanentlyDenied)
        } else {
            // 普通拒绝，显示说明弹窗，用户可选择重新请求
            showPermissionDeniedDialog(deniedPermissions)
        }
    }

    /**
     * 显示权限被拒绝对话框
     * 用户拒绝了权限但没有勾选"不再询问"
     */
    private fun showPermissionDeniedDialog(deniedPermissions: List<String>) {
        val permissionNames = deniedPermissions.map { getPermissionDisplayName(it) }

        AlertDialog.Builder(activity)
            .setTitle("权限被拒绝")
            .setMessage(
                "以下权限被拒绝：\n${permissionNames.joinToString("\n")}\n\n" +
                "这些权限是连接蓝牙设备所必需的，请授予以正常使用。"
            )
            .setPositiveButton("重新请求") { _, _ ->
                // 重新请求被拒绝的权限
                launcher.launch(deniedPermissions.toTypedArray())
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                onDenied?.invoke(deniedPermissions)
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 显示去设置页面对话框
     * 用户勾选了"不再询问"，需要手动去设置页面开启
     */
    private fun showGoToSettingsDialog(permanentlyDenied: List<String>) {
        val permissionNames = permanentlyDenied.map { getPermissionDisplayName(it) }

        AlertDialog.Builder(activity)
            .setTitle("需要手动开启权限")
            .setMessage(
                "以下权限被永久拒绝：\n${permissionNames.joinToString("\n")}\n\n" +
                "请前往设置页面手动开启这些权限，否则无法使用蓝牙功能。"
            )
            .setPositiveButton("去设置") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                onDenied?.invoke(permanentlyDenied)
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 打开应用设置页面
     */
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        activity.startActivity(intent)
    }

    /**
     * 获取未授予的权限列表
     */
    private fun getUngrantedPermissions(): Array<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(activity, permission) !=
                    PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    companion object {
        /**
         * 获取蓝牙相关权限列表
         */
        fun getRequiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ 主要需要附近设备权限；部分机型扫描仍依赖定位权限和定位开关。
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            } else {
                // Android 12 以下需要 ACCESS_FINE_LOCATION（BLE 扫描需要）
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        /**
         * 检查是否所有权限都已授予
         */
        fun hasAllPermissions(context: Context): Boolean {
            return getRequiredPermissions().all { permission ->
                ContextCompat.checkSelfPermission(context, permission) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }

        /**
         * BLE 扫描在不少 Android 机型上要求系统定位服务处于开启状态。
         */
        fun isLocationEnabled(context: Context): Boolean {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled
            } else {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }

        /**
         * 获取权限的显示名称
         */
        fun getPermissionDisplayName(permission: String): String {
            return when (permission) {
                Manifest.permission.BLUETOOTH_SCAN -> "蓝牙扫描"
                Manifest.permission.BLUETOOTH_CONNECT -> "蓝牙连接"
                Manifest.permission.ACCESS_FINE_LOCATION -> "精确位置"
                Manifest.permission.BLUETOOTH_ADVERTISE -> "蓝牙广播"
                else -> permission.substringAfterLast(".")
            }
        }
    }
}
