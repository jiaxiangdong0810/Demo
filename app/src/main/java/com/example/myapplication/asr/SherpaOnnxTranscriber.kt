package com.example.myapplication.asr

import android.content.Context
import com.example.myapplication.audio.PcmAudio
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

class SherpaOnnxTranscriber(
    context: Context,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET_PATH,
    private val tokensAssetPath: String = DEFAULT_TOKENS_ASSET_PATH
) {
    private val assetManager = context.applicationContext.assets
    private var recognizer: OfflineRecognizer? = null
    private var unavailableReason: String? = null

    fun isReady(): Boolean {
        return prepare().isSuccess
    }

    fun readinessError(): String? {
        return unavailableReason
    }

    fun transcribe(audio: PcmAudio): Result<String> {
        if (audio.samples.isEmpty()) {
            return Result.success("")
        }

        val prepared = prepare()
        if (prepared.isFailure) {
            return Result.failure(prepared.exceptionOrNull() ?: IllegalStateException("sherpa-onnx 未就绪"))
        }

        return runCatching {
            val activeRecognizer = recognizer ?: error("sherpa-onnx 未初始化")
            val stream = activeRecognizer.createStream()
            try {
                stream.acceptWaveform(audio.samples, audio.sampleRate)
                activeRecognizer.decode(stream)
                activeRecognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }
    }

    fun release() {
        recognizer?.release()
        recognizer = null
    }

    private fun prepare(): Result<Unit> {
        if (recognizer != null) {
            return Result.success(Unit)
        }

        val missingAsset = when {
            !assetExists(modelAssetPath) -> modelAssetPath
            !assetExists(tokensAssetPath) -> tokensAssetPath
            else -> null
        }
        if (missingAsset != null) {
            unavailableReason = "缺少 sherpa-onnx 文件: assets/$missingAsset"
            return Result.failure(IllegalStateException(unavailableReason))
        }

        return runCatching {
            recognizer = OfflineRecognizer(
                assetManager = assetManager,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        paraformer = OfflineParaformerModelConfig(
                            model = modelAssetPath
                        ),
                        tokens = tokensAssetPath,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu"
                    )
                )
            )
            unavailableReason = null
        }.onFailure {
            unavailableReason = it.message ?: "sherpa-onnx 初始化失败"
        }
    }

    private fun assetExists(path: String): Boolean {
        return runCatching {
            assetManager.open(path).use { true }
        }.getOrDefault(false)
    }

    companion object {
        const val DEFAULT_MODEL_ASSET_PATH = "sherpa-onnx/paraformer-zh-small/model.int8.onnx"
        const val DEFAULT_TOKENS_ASSET_PATH = "sherpa-onnx/paraformer-zh-small/tokens.txt"
    }
}
