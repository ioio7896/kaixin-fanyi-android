package com.localtranslate.hymt2

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

internal class OfflineTextToSpeech(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(ready: Boolean)
        fun onSpeakingChanged(speaking: Boolean)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ready = false
    @Volatile private var closed = false
    private var activePrefix: String? = null
    private var tts: TextToSpeech? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                main.post {
                    if (closed) return@post
                    val engine = tts
                    ready = status == TextToSpeech.SUCCESS && engine != null
                    if (ready) {
                        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                main.post {
                                    val prefix = activePrefix
                                    if (!closed && prefix != null && utteranceId?.startsWith(prefix) == true) {
                                        listener.onSpeakingChanged(true)
                                    }
                                }
                            }

                            override fun onDone(utteranceId: String?) {
                                main.post {
                                    val prefix = activePrefix
                                    if (!closed && prefix != null && utteranceId?.startsWith(prefix) == true &&
                                        utteranceId == "${prefix}last"
                                    ) {
                                        activePrefix = null
                                        listener.onSpeakingChanged(false)
                                    }
                                }
                            }

                            override fun onError(utteranceId: String?) {
                                reportUtteranceError(utteranceId)
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                reportUtteranceError(utteranceId, "系统朗读失败（错误 $errorCode）。")
                            }
                        })
                    }
                    listener.onReady(ready)
                }
            }
        } catch (_: RuntimeException) {
            ready = false
            listener.onReady(false)
        }
    }

    fun isReady(): Boolean = ready && !closed

    fun speak(text: String, language: String) {
        if (!isReady()) {
            listener.onError("系统朗读服务尚未就绪。")
            return
        }
        val content = text.trim()
        if (content.isEmpty()) {
            listener.onError("没有可以朗读的文字。")
            return
        }
        val engine = tts ?: run {
            listener.onError("系统朗读服务不可用。")
            return
        }
        val locale = localeFor(language)
        val voice = offlineVoice(engine.voices.orEmpty(), locale)
        if (voice == null) {
            val label = if (locale.language == "zh") "中文" else locale.displayLanguage
            listener.onError("本机没有可用的离线${label}语音，请先在系统语音设置中安装语音数据。")
            return
        }
        if (engine.setVoice(voice) == TextToSpeech.ERROR) {
            listener.onError("无法使用本机的离线朗读语音。")
            return
        }

        engine.stop()
        val prefix = "hy-tts-${UUID.randomUUID()}-"
        activePrefix = prefix
        val chunks = splitText(content, (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(200))
        chunks.forEachIndexed { index, chunk ->
            val utteranceId = if (index == chunks.lastIndex) "${prefix}last" else "$prefix$index"
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engine.speak(chunk, queueMode, Bundle(), utteranceId) == TextToSpeech.ERROR) {
                activePrefix = null
                listener.onSpeakingChanged(false)
                listener.onError("无法启动系统朗读。")
                return
            }
        }
    }

    fun stop() {
        activePrefix = null
        tts?.stop()
        listener.onSpeakingChanged(false)
    }

    fun close() {
        if (closed) return
        closed = true
        activePrefix = null
        ready = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun reportUtteranceError(utteranceId: String?, message: String = "系统离线朗读失败。") {
        main.post {
            val prefix = activePrefix
            if (!closed && prefix != null && utteranceId?.startsWith(prefix) == true) {
                activePrefix = null
                listener.onSpeakingChanged(false)
                listener.onError(message)
            }
        }
    }

    private fun offlineVoice(voices: Set<Voice>, requested: Locale): Voice? {
        val offline = voices.filter { !it.isNetworkConnectionRequired && it.locale.language == requested.language }
        return offline.firstOrNull { it.locale == requested }
            ?: offline.firstOrNull { requested.country.isBlank() || it.locale.country == requested.country }
            ?: offline.firstOrNull()
    }

    private fun localeFor(language: String): Locale = when (language) {
        "中文", "zh", "zh-CN" -> Locale.forLanguageTag("zh-CN")
        "英语", "en", "en-US" -> Locale.forLanguageTag("en-US")
        "日语", "ja" -> Locale.JAPAN
        "韩语", "ko" -> Locale.KOREA
        "法语", "fr" -> Locale.FRANCE
        "德语", "de" -> Locale.GERMANY
        "西班牙语", "es" -> Locale.forLanguageTag("es-ES")
        "俄语", "ru" -> Locale.forLanguageTag("ru-RU")
        else -> Locale.forLanguageTag("zh-CN")
    }

    private fun splitText(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val chunks = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + limit)
            if (end < text.length) {
                val floor = start + limit / 2
                for (index in end - 1 downTo floor) {
                    if (text[index] in charArrayOf('\n', '。', '！', '？', '.', '!', '?', ';', '；', '，', ',', ' ')) {
                        end = index + 1
                        break
                    }
                }
                if (end > start && end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            }
            if (end <= start) end = minOf(text.length, start + limit)
            chunks += text.substring(start, end).trim()
            start = end
            while (start < text.length && text[start].isWhitespace()) start++
        }
        return chunks.filter(String::isNotEmpty)
    }
}
