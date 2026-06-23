package com.example.myapplication.ble

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.databinding.ActivityRealtimeRecordingBinding
import com.example.myapplication.repository.DeviceSession
import com.example.myapplication.repository.RealtimeRecordingState
import com.example.myapplication.repository.TranscriptionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class RealtimeRecordingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRealtimeRecordingBinding
    private val repository by lazy { DeviceSession.repository(this) }
    private var timerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRealtimeRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
        observeState()
        startTimer()
    }

    private fun setupUi() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnStopRecording.setOnClickListener {
            repository.stopRecording()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repository.deviceState.collectLatest { state ->
                binding.tvDeviceName.text = state.deviceName.ifEmpty { "音立方设备" }
                binding.tvRecordingStatus.text = if (state.isRecording) "录音中" else "录音已停止"
                binding.tvRecordingName.visibility = if (state.recordingFileName.isNotEmpty()) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                binding.tvRecordingName.text = state.recordingFileName
                binding.btnStopRecording.isEnabled = state.isRecording
            }
        }

        lifecycleScope.launch {
            repository.connectionState.collectLatest { state ->
                binding.tvConnectionStatus.text = when (state) {
                    ConnectionState.CONNECTED -> "蓝牙已连接"
                    ConnectionState.CONNECTING -> "连接中"
                    ConnectionState.DISCOVERING_SERVICES -> "发现服务中"
                    ConnectionState.SCANNING -> "扫描中"
                    ConnectionState.FAILED -> "连接失败"
                    ConnectionState.DISCONNECTED -> "蓝牙已断开"
                }
            }
        }

        lifecycleScope.launch {
            repository.realtimeRecordingState.collectLatest { state ->
                updateRealtimeUi(state)
                state.lastError?.let {
                    Toast.makeText(this@RealtimeRecordingActivity, it, Toast.LENGTH_SHORT).show()
                }
            }
        }

        lifecycleScope.launch {
            repository.transcriptionState.collectLatest { state ->
                updateTranscriptionUi(state)
            }
        }
    }

    private fun updateRealtimeUi(state: RealtimeRecordingState) {
        binding.tvCaptureStatus.text = when {
            state.isCapturing && state.totalBytes == 0L -> "等待音频数据"
            state.isCapturing -> "正在接收音频"
            state.totalBytes > 0L -> "接收完成"
            else -> "未开始接收"
        }
        binding.tvTotalBytes.text = formatBytes(state.totalBytes)
        binding.tvPacketSize.text = "${state.lastPacketBytes} 字节"
        binding.tvPacketCount.text = "${state.packetCount} 包"
        binding.tvReceiveRate.text = "${formatBytes(state.bytesPerSecond)}/s"
        binding.tvLocalPath.text = state.localPath.ifEmpty { "生成音频文件后显示保存位置" }
        binding.progressReceiving.visibility = if (state.isCapturing) View.VISIBLE else View.GONE
    }

    private fun updateTranscriptionUi(state: TranscriptionState) {
        binding.tvTranscriptionStatus.text = when {
            state.isTranscribing -> state.statusText
            state.lastError != null -> state.lastError
            else -> state.statusText
        }
        binding.progressTranscribing.visibility = if (state.isTranscribing) View.VISIBLE else View.GONE
        binding.tvTranscriptionText.text = state.finalText.ifEmpty {
            if (state.lastError != null) {
                "放入 sherpa-onnx 模型和 native 库后，这里会显示本地转写结果。"
            } else {
                "暂无转写内容"
            }
        }
    }

    private fun startTimer() {
        timerJob = lifecycleScope.launch {
            while (true) {
                val state = repository.realtimeRecordingState.value
                binding.tvDuration.text = if (state.startedAtMillis > 0L) {
                    formatDuration((System.currentTimeMillis() - state.startedAtMillis) / 1000L)
                } else {
                    "00:00"
                }
                delay(1000L)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remainSeconds = seconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, remainSeconds)
    }

    override fun onDestroy() {
        timerJob?.cancel()
        super.onDestroy()
    }
}
