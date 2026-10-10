package com.localtranslate.hymt2

import java.util.concurrent.Executors

internal class NativeLlamaEngine {
    @Volatile
    private var handle: Long = 0L

    @Synchronized
    fun load(modelPath: String) {
        if (handle != 0L) return
        check(NativeBindings.nativeAvailable()) {
            "本安装包未包含 arm64-v8a 推理库。请先按 android/README.md 准备 llama.cpp 并重新构建。"
        }
        val loaded = NativeBindings.nativeLoadModel(modelPath, Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
        check(loaded != 0L) { NativeBindings.nativeLastError().ifBlank { "llama.cpp 无法载入模型。" } }
        handle = loaded
    }

    @Synchronized
    fun releaseAndWait() {
        val oldHandle = handle
        if (oldHandle == 0L) return
        handle = 0L
        NativeBindings.nativeReleaseModel(oldHandle)
    }

    @Synchronized
    fun isLoaded(): Boolean = handle != 0L

    @Synchronized
    fun translate(text: String, targetLanguage: String): String {
        val activeHandle = handle
        check(activeHandle != 0L) { "本地模型尚未载入。" }
        NativeBindings.nativeClearCancel(activeHandle)
        return NativeBindings.nativeTranslate(activeHandle, text, targetLanguage)
    }

    fun cancel() {
        val activeHandle = handle
        if (activeHandle != 0L) NativeBindings.nativeCancelModel(activeHandle)
    }

    @Synchronized
    fun closeAsync() {
        val oldHandle = handle
        if (oldHandle == 0L) return
        handle = 0L
        RELEASE_EXECUTOR.execute { NativeBindings.nativeReleaseModel(oldHandle) }
    }

    private object NativeBindings {
        private val available: Boolean = try {
            System.loadLibrary("hymt2_android")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }

        fun nativeAvailable(): Boolean = available

        external fun nativeLoadModel(modelPath: String, threads: Int): Long
        external fun nativeClearCancel(handle: Long)
        external fun nativeCancelModel(handle: Long)
        external fun nativeTranslate(handle: Long, text: String, targetLanguage: String): String
        external fun nativeReleaseModel(handle: Long)
        external fun nativeLastError(): String
    }

    private companion object {
        val RELEASE_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hymt2-model-release").apply { isDaemon = true }
        }
    }
}
