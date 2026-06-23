package com.example.myapplication.ble

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.databinding.ActivityRealtimeRecordingBinding
import com.example.myapplication.repository.DeviceEvent
import com.example.myapplication.repository.DeviceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 录音页
 *
 * 状态机：
 *   IDLE → RECORDING → STOPPED → (显示文件路径) → (自动回到 IDLE)
 *
 * 通过 DeviceManager.events 订阅设备事件，无需直接操作 BLE。
 */
class RealtimeRecordingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRealtimeRecordingBinding
    private var timerJob: Job? = null
    private var resetJob: Job? = null
    private var levelJob: Job? = null
    private var isRecording = false
    private var recordingStartMillis = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRealtimeRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeEvents()
        startTimer()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        // 显示设备名
        val deviceName = DeviceManager.deviceState.value.deviceName
        binding.tvDeviceName.text = deviceName.ifEmpty { "音立方" }

        // 显示当前电量和存储（如果已有数据）
        val state = DeviceManager.deviceState.value
        if (state.battery >= 0) binding.tvBattery.text = "${state.battery}%"
        if (state.freeStorage >= 0) binding.tvStorage.text = formatStorageSize(state.freeStorage)
    }

    // ==================== 事件订阅 ====================

    private fun observeEvents() {
        lifecycleScope.launch {
            DeviceManager.events.collect { event ->
                when (event) {
                    is DeviceEvent.ConnectionChanged -> {
                        if (event.state == ConnectionState.DISCONNECTED) {
                            Toast.makeText(this@RealtimeRecordingActivity, "蓝牙已断开", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                    is DeviceEvent.BatteryChanged -> {
                        binding.tvBattery.text = "${event.percent}%"
                    }
                    is DeviceEvent.StorageChanged -> {
                        binding.tvStorage.text = formatStorageSize(event.freeMb)
                    }
                    is DeviceEvent.Error -> {
                        Toast.makeText(this@RealtimeRecordingActivity, event.message, Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }
    }

    // ==================== 录音控制 ====================

    private fun startRecording() {
        isRecording = true
        recordingStartMillis = System.currentTimeMillis()
        resetJob?.cancel()

        DeviceManager.beginRealtimeCapture()
        DeviceManager.startRecording()

        binding.waveformView.startRecording()
        updateRecordButton(recording = true)
        binding.tvStatus.text = "录音中"
        binding.pathContainer.visibility = View.GONE

        startLevelSimulation()
    }

    private fun stopRecording() {
        isRecording = false
        levelJob?.cancel()

        DeviceManager.stopRecording()

        binding.waveformView.stopRecording()
        updateRecordButton(recording = false)
        binding.tvStatus.text = "录音已完成"

        lifecycleScope.launch {
            delay(500L)
            showFilePath()
        }
    }

    private fun showFilePath() {
        val path = DeviceManager.realtimeRecordingState.value.localPath
        if (path.isNotEmpty()) {
            binding.tvFilePath.text = path
            binding.pathContainer.visibility = View.VISIBLE
            binding.pathContainer.alpha = 0f
            binding.pathContainer.animate().alpha(1f).setDuration(300).start()
        }

        resetJob?.cancel()
        resetJob = lifecycleScope.launch {
            delay(5000L)
            resetToIdle()
        }
    }

    private fun resetToIdle() {
        binding.tvDuration.text = "00:00"
        binding.tvStatus.text = "点击下方按钮开始录音"
        binding.pathContainer.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { binding.pathContainer.visibility = View.GONE }
            .start()
    }

    private fun updateRecordButton(recording: Boolean) {
        val dot = binding.recordDot
        if (recording) {
            dot.setBackgroundResource(R.drawable.bg_record_dot_active)
            ValueAnimator.ofFloat(1f, 0.7f).apply {
                duration = 200
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { dot.scaleX = it.animatedValue as Float; dot.scaleY = it.animatedValue as Float }
                start()
            }
        } else {
            dot.setBackgroundResource(R.drawable.bg_record_dot_idle)
            ValueAnimator.ofFloat(0.7f, 1f).apply {
                duration = 200
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { dot.scaleX = it.animatedValue as Float; dot.scaleY = it.animatedValue as Float }
                start()
            }
        }
    }

    // ==================== 声波模拟 ====================

    private fun startLevelSimulation() {
        levelJob?.cancel()
        levelJob = lifecycleScope.launch {
            while (isRecording) {
                val level = 0.3f + Math.random().toFloat() * 0.7f
                binding.waveformView.updateLevel(level)
                delay(80L)
            }
        }
    }

    // ==================== 计时器 ====================

    private fun startTimer() {
        timerJob = lifecycleScope.launch {
            while (true) {
                if (isRecording && recordingStartMillis > 0L) {
                    val elapsed = (System.currentTimeMillis() - recordingStartMillis) / 1000L
                    binding.tvDuration.text = formatDuration(elapsed)
                }
                delay(1000L)
            }
        }
    }

    // ==================== 工具 ====================

    private fun formatStorageSize(sizeMb: Int): String {
        return if (sizeMb >= 1024) {
            String.format(Locale.getDefault(), "%.1fGB", sizeMb / 1024.0)
        } else {
            "${sizeMb}MB"
        }
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remainSeconds = seconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, remainSeconds)
    }

    override fun onDestroy() {
        timerJob?.cancel()
        resetJob?.cancel()
        levelJob?.cancel()
        binding.waveformView.release()
        super.onDestroy()
    }
}
