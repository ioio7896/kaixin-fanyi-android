package com.localtranslate.hymt2

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localtranslate.hymt2.data.TranslationDatabase
import com.localtranslate.hymt2.data.TranslationPreferences
import com.localtranslate.hymt2.data.TranslationRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SpeakingTarget { SOURCE, TRANSLATION }

data class TranslatorUiState(
    val modelStatus: String = "准备本地模型…",
    val installProgress: Float = 0f,
    val modelReady: Boolean = false,
    val modelInitializationFailed: Boolean = false,
    val translating: Boolean = false,
    val sourceText: String = "",
    val targetLanguage: String = "中文",
    val translation: String = "",
    val translatedSourceText: String? = null,
    val translatedTargetLanguage: String? = null,
    val processTextReadOnly: Boolean? = null,
    val recentTranslations: List<TranslationRecord> = emptyList(),
    val error: String? = null,
    val voicePreparing: Boolean = false,
    val speechModelInstalling: Boolean = false,
    val speechModelInstallProgress: Float = 0f,
    val recording: Boolean = false,
    val recognizingSpeech: Boolean = false,
    val speechStatus: String? = null,
    val recognizedSourceLanguage: String? = null,
    val autoBilingualVoiceDirection: Boolean = true,
    val ttsReady: Boolean = false,
    val speaking: Boolean = false,
    val speakingTarget: SpeakingTarget? = null,
) {
    val voiceBusy: Boolean get() = recording || recognizingSpeech || voicePreparing || speechModelInstalling
    val inputBusy: Boolean get() = translating || voiceBusy
    val translationCurrent: Boolean get() = translation.isNotBlank() &&
        sourceText == translatedSourceText && targetLanguage == translatedTargetLanguage
}

class TranslationViewModel(application: Application) : AndroidViewModel(application) {
    private val installer = BundledModelInstaller(application)
    private val engine = NativeLlamaEngine()
    private val historyDao = TranslationDatabase.get(application).translationHistoryDao()
    private val preferences = TranslationPreferences(application)
    private val speechModels = BundledSpeechModelManager(application)
    private val voiceInput = OfflineVoiceInputController(application, speechModels)
    private val _state = MutableStateFlow(TranslatorUiState())
    val state: StateFlow<TranslatorUiState> = _state.asStateFlow()
    private val textToSpeech = OfflineTextToSpeech(application, object : OfflineTextToSpeech.Listener {
        override fun onReady(ready: Boolean) {
            _state.update { it.copy(ttsReady = ready) }
        }

        override fun onSpeakingChanged(speaking: Boolean) {
            _state.update { it.copy(speaking = speaking, speakingTarget = it.speakingTarget.takeIf { speaking }) }
        }

        override fun onError(message: String) {
            _state.update { it.copy(error = message, speaking = false, speakingTarget = null) }
        }
    })

    private var preferencesReady = false
    private var pendingAutoTranslation = false
    private var translationGeneration = 0
    private var voiceStartInProgress = false
    private var voiceStartGeneration = 0
    private var voiceRecognitionGeneration = 0
    private var modelInitializationInProgress = false
    @Volatile private var translationModelPath: String? = null

    init {
        viewModelScope.launch {
            preferences.targetLanguage.collect { language ->
                _state.update { it.copy(targetLanguage = language) }
                preferencesReady = true
                startPendingAutoTranslation()
            }
        }
        viewModelScope.launch {
            preferences.autoBilingualVoiceDirection.collect { enabled ->
                _state.update { it.copy(autoBilingualVoiceDirection = enabled) }
            }
        }
        viewModelScope.launch {
            historyDao.observeRecent().collect { records ->
                _state.update { it.copy(recentTranslations = records) }
            }
        }
        initializeModel()
    }

    fun retryModelInitialization() {
        if (_state.value.modelReady || _state.value.inputBusy || modelInitializationInProgress) return
        initializeModel()
    }

    private fun initializeModel() {
        if (modelInitializationInProgress) return
        modelInitializationInProgress = true
        viewModelScope.launch {
            try {
                _state.update { it.copy(modelStatus = "正在从安装包解压模型…", modelInitializationFailed = false, error = null) }
                val modelFile = withContext(Dispatchers.IO) {
                    installer.install { progress ->
                        _state.update { it.copy(installProgress = progress) }
                    }
                }
                translationModelPath = modelFile.absolutePath
                _state.update { it.copy(modelStatus = "正在载入本地推理引擎…", installProgress = 1f) }
                withContext(Dispatchers.IO) { engine.load(modelFile.absolutePath) }
                _state.update {
                    it.copy(modelStatus = "本地模型已就绪", modelReady = true, modelInitializationFailed = false, error = null)
                }
                startPendingAutoTranslation()
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        modelStatus = "模型初始化失败",
                        modelReady = false,
                        modelInitializationFailed = true,
                        error = error.message ?: "无法安装或载入本地模型。",
                    )
                }
            } finally {
                modelInitializationInProgress = false
            }
        }
    }

    fun setSourceText(value: String) {
        if (_state.value.inputBusy || value == _state.value.sourceText) return
        textToSpeech.stop()
        _state.update { it.copy(sourceText = value, recognizedSourceLanguage = null, error = null) }
    }

    fun receiveText(value: String, processTextReadOnly: Boolean? = null) {
        if (_state.value.translating) {
            translationGeneration++
            engine.cancel()
        }
        if (voiceInput.isRecording() || _state.value.voicePreparing || _state.value.recognizingSpeech) {
            voiceStartGeneration++
            voiceRecognitionGeneration++
            voiceStartInProgress = false
            voiceInput.cancelRecording()
        }
        _state.update {
            it.copy(
                sourceText = value,
                translation = "",
                processTextReadOnly = processTextReadOnly,
                recognizedSourceLanguage = null,
                recording = false,
                recognizingSpeech = false,
                voicePreparing = false,
                speechModelInstalling = false,
                speechStatus = null,
                error = null,
            )
        }
        pendingAutoTranslation = value.isNotBlank()
        startPendingAutoTranslation()
    }

    fun startNewTranslation() {
        pendingAutoTranslation = false
        if (_state.value.translating) {
            translationGeneration++
            engine.cancel()
        }
        if (voiceInput.isRecording() || _state.value.voicePreparing || _state.value.recognizingSpeech) {
            voiceStartGeneration++
            voiceRecognitionGeneration++
            voiceStartInProgress = false
            voiceInput.cancelRecording()
        }
        textToSpeech.stop()
        _state.update {
            it.copy(
                sourceText = "",
                translation = "",
                processTextReadOnly = null,
                recognizedSourceLanguage = null,
                recording = false,
                recognizingSpeech = false,
                voicePreparing = false,
                speechModelInstalling = false,
                speechStatus = null,
                error = null,
            )
        }
    }

    fun showInputError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun reportAudioPermissionDenied() {
        showInputError("需要麦克风权限才能进行离线语音输入。")
    }

    fun clearProcessTextMode() {
        _state.update { it.copy(processTextReadOnly = null) }
    }

    private fun startPendingAutoTranslation() {
        val current = _state.value
        if (!pendingAutoTranslation || !preferencesReady || !current.modelReady || current.translating ||
            current.recording || current.recognizingSpeech || current.voicePreparing || current.speechModelInstalling
        ) return
        pendingAutoTranslation = false
        translate()
    }

    fun setTargetLanguage(value: String) {
        if (_state.value.inputBusy || value == _state.value.targetLanguage) return
        textToSpeech.stop()
        _state.update { it.copy(targetLanguage = value, error = null) }
        viewModelScope.launch(Dispatchers.IO) { preferences.setTargetLanguage(value) }
    }

    fun setAutoBilingualVoiceDirection(enabled: Boolean) {
        _state.update { it.copy(autoBilingualVoiceDirection = enabled, error = null) }
        viewModelScope.launch(Dispatchers.IO) { preferences.setAutoBilingualVoiceDirection(enabled) }
    }

    fun startVoiceInput() {
        val snapshot = _state.value
        if (!snapshot.modelReady || snapshot.translating || snapshot.recording || snapshot.recognizingSpeech ||
            snapshot.speechModelInstalling || snapshot.voicePreparing || voiceStartInProgress
        ) return
        if (getApplication<Application>().checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            reportAudioPermissionDenied()
            return
        }

        voiceStartInProgress = true
        val generation = ++voiceStartGeneration
        val recognitionGeneration = ++voiceRecognitionGeneration
        textToSpeech.stop()
        _state.update { it.copy(voicePreparing = true, speechStatus = "正在准备离线语音输入…", error = null) }
        viewModelScope.launch {
            try {
                if (!speechModels.isInstalled()) {
                    _state.update {
                        it.copy(
                            speechModelInstalling = true,
                            speechModelInstallProgress = 0f,
                            speechStatus = "首次使用需安装离线语音识别模型。",
                            error = null,
                        )
                    }
                    speechModels.install { progress ->
                        if (generation == voiceStartGeneration) {
                            _state.update { it.copy(speechModelInstallProgress = progress) }
                        }
                    }
                } else {
                    withContext(Dispatchers.IO) { speechModels.verifyInstalledModel() }
                }
                if (generation != voiceStartGeneration) return@launch

                val listener = object : OfflineVoiceInputController.Listener {
                    override fun onDurationLimitReached() {
                        _state.update {
                            it.copy(
                                recording = false,
                                recognizingSpeech = true,
                                modelReady = false,
                                modelStatus = "正在离线识别…",
                                speechStatus = "已达到 60 秒录音上限，正在识别…",
                            )
                        }
                    }

                    override fun onRecognitionFinished(result: RecognizedSpeech?, error: String?) {
                        restoreTranslationModelAfterVoice(result, error, recognitionGeneration)
                    }
                }
                withContext(Dispatchers.IO) {
                    voiceInput.startRecording(listener) { engine.releaseAndWait() }
                }
                _state.update {
                    it.copy(
                        speechModelInstalling = false,
                        speechModelInstallProgress = 1f,
                        voicePreparing = false,
                        recording = true,
                        recognizingSpeech = false,
                        speechStatus = "正在录音，点击“完成识别”结束。仅在本机识别。",
                        error = null,
                    )
                }
            } catch (error: Exception) {
                if (generation == voiceStartGeneration) {
                    _state.update {
                        it.copy(
                            speechModelInstalling = false,
                            voicePreparing = false,
                            recording = false,
                            recognizingSpeech = false,
                            speechStatus = null,
                            error = error.message ?: "无法启动离线语音输入。",
                        )
                    }
                }
            } finally {
                if (generation == voiceStartGeneration) {
                    voiceStartInProgress = false
                    _state.update { it.copy(voicePreparing = false) }
                }
            }
        }
    }

    fun finishVoiceInput() {
        if (!voiceInput.stopAndRecognize()) return
        _state.update {
            it.copy(
                recording = false,
                recognizingSpeech = true,
                modelReady = false,
                modelStatus = "正在离线识别…",
                speechStatus = "正在识别语音…",
                error = null,
            )
        }
    }

    fun cancelVoiceInput() {
        voiceStartGeneration++
        voiceRecognitionGeneration++
        voiceStartInProgress = false
        voiceInput.cancelRecording()
        _state.update {
            it.copy(
                voicePreparing = false,
                speechModelInstalling = false,
                recording = false,
                recognizingSpeech = false,
                speechStatus = "录音已取消。",
            )
        }
    }

    private fun restoreTranslationModelAfterVoice(
        result: RecognizedSpeech?,
        recognitionError: String?,
        recognitionGeneration: Int,
    ) {
        if (recognitionGeneration == voiceRecognitionGeneration) {
            _state.update {
                it.copy(
                    recording = false,
                    recognizingSpeech = true,
                    modelReady = false,
                    modelStatus = "正在恢复翻译模型…",
                    speechStatus = if (result == null) "语音识别已结束，正在恢复翻译模型…" else "识别完成，正在恢复翻译模型…",
                )
            }
        }
        viewModelScope.launch {
            val restoreError = runCatching {
                withContext(Dispatchers.IO) {
                    val path = translationModelPath ?: error("找不到本地翻译模型路径。")
                    engine.load(path)
                }
            }.exceptionOrNull()

            var targetToPersist: String? = null
            _state.update { current ->
                val applySpeechResult = recognitionGeneration == voiceRecognitionGeneration
                val activeResult = result.takeIf { applySpeechResult }
                val nextTarget = if (activeResult != null && current.autoBilingualVoiceDirection) {
                    targetForRecognizedLanguage(activeResult.language) ?: current.targetLanguage
                } else {
                    current.targetLanguage
                }
                if (nextTarget != current.targetLanguage) targetToPersist = nextTarget
                val finalError = restoreError?.message ?: if (applySpeechResult) recognitionError else null
                current.copy(
                    modelStatus = if (restoreError == null) "本地模型已就绪" else "翻译模型恢复失败",
                    modelReady = restoreError == null,
                    modelInitializationFailed = restoreError != null,
                    sourceText = activeResult?.text ?: current.sourceText,
                    targetLanguage = nextTarget,
                    recognizedSourceLanguage = activeResult?.language,
                    recognizingSpeech = false,
                    speechStatus = when {
                        restoreError != null && activeResult != null -> "识别已完成，但翻译模型恢复失败。"
                        restoreError != null -> "翻译模型恢复失败。"
                        activeResult != null -> voiceResultStatus(activeResult.language, nextTarget, current.autoBilingualVoiceDirection)
                        applySpeechResult -> "语音识别未完成。"
                        else -> current.speechStatus
                    },
                    error = finalError,
                )
            }
            targetToPersist?.let { target ->
                withContext(Dispatchers.IO) { preferences.setTargetLanguage(target) }
            }
            startPendingAutoTranslation()
        }
    }

    private fun targetForRecognizedLanguage(language: String): String? = when (language.lowercase()) {
        "zh" -> "英语"
        "en" -> "中文"
        else -> null
    }

    private fun voiceResultStatus(language: String, target: String, autoDirection: Boolean): String {
        val detected = when (language.lowercase()) {
            "zh" -> "中文"
            "en" -> "英语"
            "ja" -> "日语"
            "ko" -> "韩语"
            "yue" -> "粤语"
            else -> language.ifBlank { "未知语言" }
        }
        val direction = if (autoDirection && targetForRecognizedLanguage(language) != null) {
            "目标语言已自动切换为$target。"
        } else {
            "目标语言：$target。"
        }
        return "识别为$detected。请检查原文后翻译。$direction"
    }

    fun speakTranslation() {
        val current = _state.value
        if (!current.translationCurrent || current.inputBusy) return
        if (current.speakingTarget == SpeakingTarget.TRANSLATION) textToSpeech.stop()
        else {
            textToSpeech.stop()
            _state.update { it.copy(speakingTarget = SpeakingTarget.TRANSLATION) }
            textToSpeech.speak(current.translation, current.translatedTargetLanguage ?: current.targetLanguage)
        }
    }

    fun speakSource() {
        val current = _state.value
        if (current.sourceText.isBlank() || current.inputBusy) return
        if (current.speakingTarget == SpeakingTarget.SOURCE) textToSpeech.stop()
        else {
            textToSpeech.stop()
            val language = current.recognizedSourceLanguage?.takeIf(String::isNotBlank)
                ?: if (current.sourceText.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) "中文" else "英语"
            _state.update { it.copy(speakingTarget = SpeakingTarget.SOURCE) }
            textToSpeech.speak(current.sourceText, language)
        }
    }

    fun stopSpeaking() = textToSpeech.stop()

    fun loadHistory(record: TranslationRecord) {
        if (_state.value.inputBusy) return
        textToSpeech.stop()
        _state.update {
            it.copy(
                sourceText = record.sourceText,
                targetLanguage = record.targetLanguage,
                translation = record.translatedText,
                translatedSourceText = record.sourceText,
                translatedTargetLanguage = record.targetLanguage,
                processTextReadOnly = null,
                recognizedSourceLanguage = null,
                error = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) { preferences.setTargetLanguage(record.targetLanguage) }
    }

    suspend fun deleteHistory(record: TranslationRecord) = withContext(Dispatchers.IO) { historyDao.delete(record) }

    suspend fun restoreHistory(record: TranslationRecord) = withContext(Dispatchers.IO) { historyDao.insert(record) }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { historyDao.clear() }
    }

    fun cancelTranslation() {
        pendingAutoTranslation = false
        translationGeneration++
        engine.cancel()
    }

    fun translate() {
        val snapshot = _state.value
        if (!snapshot.modelReady || snapshot.sourceText.isBlank() || snapshot.translating ||
            snapshot.recording || snapshot.recognizingSpeech || snapshot.voicePreparing || snapshot.speechModelInstalling
        ) return
        val generation = ++translationGeneration

        viewModelScope.launch {
            textToSpeech.stop()
            _state.update { it.copy(translating = true, translation = "", error = null) }
            try {
                val translated = withContext(Dispatchers.Default) {
                    engine.translate(snapshot.sourceText, snapshot.targetLanguage)
                }
                if (generation == translationGeneration) {
                    _state.update {
                        it.copy(
                            translation = translated,
                            translatedSourceText = snapshot.sourceText,
                            translatedTargetLanguage = snapshot.targetLanguage,
                        )
                    }
                    withContext(Dispatchers.IO) {
                        historyDao.insert(
                            TranslationRecord(
                                sourceText = snapshot.sourceText,
                                targetLanguage = snapshot.targetLanguage,
                                translatedText = translated,
                            ),
                        )
                    }
                }
            } catch (error: Exception) {
                if (generation == translationGeneration) {
                    _state.update { it.copy(error = error.message ?: "本地翻译失败。") }
                }
            } finally {
                _state.update { it.copy(translating = false) }
                startPendingAutoTranslation()
            }
        }
    }

    override fun onCleared() {
        voiceInput.close()
        textToSpeech.close()
        engine.cancel()
        engine.closeAsync()
        super.onCleared()
    }
}
