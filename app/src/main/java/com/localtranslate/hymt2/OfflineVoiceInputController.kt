package com.localtranslate.hymt2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import java.util.concurrent.Executors

internal data class RecognizedSpeech(val text: String, val language: String)

internal class OfflineVoiceInputController(
    context: Context,
    private val models: BundledSpeechModelManager,
) {
    interface Listener {
        fun onDurationLimitReached()
        fun onRecognitionFinished(result: RecognizedSpeech?, error: String?)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hy-sensevoice-asr").apply { isDaemon = true }
    }
    private val audioLock = Any()
    private val chunks = ArrayList<ShortArray>()
    @Volatile private var recording = false
    @Volatile private var recognizing = false
    @Volatile private var closed = false
    private var recorder: AudioRecord? = null
    private var sampleCount = 0
    private var activeListener: Listener? = null
    private var beforeRecognition: (() -> Unit)? = null

    private val durationLimit = Runnable {
        activeListener?.onDurationLimitReached()
        stopAndRecognize()
    }

    fun isRecording(): Boolean = recording

    @Throws(Exception::class)
    fun startRecording(listener: Listener, releaseTranslationModel: () -> Unit) {
        check(!closed) { "语音输入已关闭。" }
        check(!recording && !recognizing) { "语音输入正在进行。" }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("需要麦克风权限才能进行离线语音输入。")
        }
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "设备不支持 16 kHz 单声道录音。" }

        val candidate = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum * 2, SAMPLE_RATE),
        )
        if (candidate.state != AudioRecord.STATE_INITIALIZED) {
            candidate.release()
            throw IllegalStateException("无法初始化麦克风。")
        }

        try {
            synchronized(audioLock) {
                chunks.clear()
                sampleCount = 0
                activeListener = listener
                beforeRecognition = releaseTranslationModel
                recorder = candidate
                recording = true
            }
            candidate.startRecording()
            executor.execute { captureLoop(candidate) }
            main.removeCallbacks(durationLimit)
            main.postDelayed(durationLimit, MAX_RECORDING_MS)
        } catch (error: Exception) {
            synchronized(audioLock) {
                recording = false
                recorder = null
                activeListener = null
                beforeRecognition = null
            }
            runCatching { candidate.release() }
            throw error
        }
    }

    fun stopAndRecognize(): Boolean {
        val active: AudioRecord
        val listener: Listener
        synchronized(audioLock) {
            if (!recording || recognizing) return false
            recording = false
            recognizing = true
            active = recorder ?: run {
                recognizing = false
                return false
            }
            listener = activeListener ?: run {
                recognizing = false
                return false
            }
        }
        main.removeCallbacks(durationLimit)
        runCatching { active.stop() }
        executor.execute { recognize(listener) }
        return true
    }

    fun cancelRecording() {
        val active = synchronized(audioLock) {
            if (!recording) return
            recording = false
            activeListener = null
            beforeRecognition = null
            chunks.clear()
            sampleCount = 0
            recorder
        }
        main.removeCallbacks(durationLimit)
        runCatching { active?.stop() }
    }

    private fun captureLoop(active: AudioRecord) {
        val buffer = ShortArray(2048)
        var captureError: String? = null
        try {
            while (recording && !closed) {
                val count = active.read(buffer, 0, buffer.size)
                if (count > 0) {
                    synchronized(audioLock) {
                        val remaining = MAX_SAMPLES - sampleCount
                        if (remaining > 0) {
                            val keep = minOf(count, remaining)
                            chunks += buffer.copyOf(keep)
                            sampleCount += keep
                        }
                    }
                } else if (count < 0) {
                    captureError = when (count) {
                        AudioRecord.ERROR_DEAD_OBJECT -> "麦克风录音中断，设备已释放，请重新尝试。"
                        AudioRecord.ERROR_INVALID_OPERATION -> "麦克风录音失败，请重新尝试。"
                        else -> "麦克风录音失败（错误 $count）。"
                    }
                    break
                }
            }
        } catch (error: Exception) {
            captureError = "麦克风录音中断：${error.message ?: error.javaClass.simpleName}"
        } finally {
            var interruptedListener: Listener? = null
            synchronized(audioLock) {
                runCatching { active.release() }
                if (recorder === active) recorder = null
                if (recording && !closed && captureError != null) {
                    recording = false
                    activeListener?.let {
                        recognizing = true
                        interruptedListener = it
                    } ?: run { recognizing = false }
                }
            }
            interruptedListener?.let { listener ->
                main.removeCallbacks(durationLimit)
                finish(listener, null, captureError)
            }
        }
    }

    private fun recognize(listener: Listener) {
        val samples: FloatArray = synchronized(audioLock) {
            if (sampleCount < SAMPLE_RATE / 3) {
                FloatArray(0)
            } else {
                FloatArray(sampleCount).also { result ->
                    var offset = 0
                    chunks.forEach { chunk ->
                        chunk.forEach { value -> result[offset++] = value / 32768f }
                    }
                }
            }
        }
        if (samples.isEmpty()) {
            finish(listener, null, "录音太短，请再说一次。")
            return
        }

        var stream: OfflineStream? = null
        var recognizer: OfflineRecognizer? = null
        var result: RecognizedSpeech? = null
        var errorMessage: String? = null
        try {
            synchronized(audioLock) {
                chunks.clear()
                sampleCount = 0
            }
            beforeRecognition?.invoke()
            models.verifyInstalledModel()
            val senseVoice = OfflineSenseVoiceModelConfig().apply {
                model = models.modelFile.absolutePath
                language = "auto"
                useInverseTextNormalization = true
            }
            val modelConfig = OfflineModelConfig().apply {
                this.senseVoice = senseVoice
                tokens = models.tokensFile.absolutePath
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                provider = "cpu"
            }
            val config = OfflineRecognizerConfig().apply {
                this.modelConfig = modelConfig
                decodingMethod = "greedy_search"
            }
            recognizer = OfflineRecognizer(null, config)
            stream = recognizer.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val decoded = recognizer.getResult(stream)
            val text = decoded.text?.trim().orEmpty()
            if (text.isBlank()) {
                errorMessage = "没有识别到清晰语音，请靠近麦克风再试。"
            } else {
                result = RecognizedSpeech(text, decoded.lang.orEmpty().lowercase())
            }
        } catch (error: Throwable) {
            errorMessage = "离线语音识别失败：${error.message ?: error.javaClass.simpleName}"
        } finally {
            runCatching { stream?.release() }
            runCatching { recognizer?.release() }
            finish(listener, result, errorMessage)
        }
    }

    private fun finish(listener: Listener, result: RecognizedSpeech?, error: String?) {
        synchronized(audioLock) {
            recognizing = false
            activeListener = null
            beforeRecognition = null
            chunks.clear()
            sampleCount = 0
        }
        main.post {
            if (!closed) listener.onRecognitionFinished(result, error)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        cancelRecording()
        main.removeCallbacks(durationLimit)
        executor.shutdownNow()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_DURATION_SECONDS = 60
        const val MAX_SAMPLES = SAMPLE_RATE * MAX_DURATION_SECONDS
        const val MAX_RECORDING_MS = MAX_DURATION_SECONDS * 1000L
    }
}
