package com.localtranslate.hymt2

import android.content.Context
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class BundledSpeechModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.filesDir, "models/sensevoice-small-int8")
    @Volatile private var contentVerified = false

    val modelFile: File get() = File(modelDirectory, "model.int8.onnx")
    val tokensFile: File get() = File(modelDirectory, "tokens.txt")
    private val markerFile: File get() = File(modelDirectory, "installed.sha256")

    fun isInstalled(): Boolean {
        if (!modelFile.isFile || modelFile.length() < MIN_MODEL_BYTES ||
            !tokensFile.isFile || tokensFile.length() < MIN_TOKENS_BYTES
        ) return false
        return try {
            markerFile.readText(StandardCharsets.US_ASCII).startsWith("$ARCHIVE_SHA256\n")
        } catch (_: IOException) {
            false
        }
    }

    suspend fun install(onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        if (isInstalled()) {
            verifyInstalledModel()
            onProgress(1f)
            return@withContext modelFile
        }

        val available = StatFs(appContext.filesDir.absolutePath).availableBytes
        check(available >= INSTALL_BYTES_REQUIRED) {
            "可用空间不足，安装离线语音识别至少需要约 800 MiB。"
        }

        val stagingDirectory = File(appContext.filesDir, "models/.sensevoice-installing")
        if (stagingDirectory.exists()) stagingDirectory.deleteRecursively()
        check(stagingDirectory.mkdirs()) { "无法创建语音模型暂存目录。" }
        val stagedModel = File(stagingDirectory, "model.int8.onnx")
        val stagedTokens = File(stagingDirectory, "tokens.txt")
        try {
            extractArchive(stagedModel, stagedTokens, onProgress)
            check(stagedModel.length() >= MIN_MODEL_BYTES && stagedTokens.length() >= MIN_TOKENS_BYTES) {
                "内置语音模型文件不完整。"
            }
            val modelHash = sha256(stagedModel)
            val tokensHash = sha256(stagedTokens)
            check(modelDirectory.mkdirs() || modelDirectory.isDirectory) { "无法创建语音模型目录。" }
            moveReplace(stagedModel, modelFile)
            moveReplace(stagedTokens, tokensFile)
            writeMarkerAtomically(modelHash, tokensHash)
            contentVerified = true
            onProgress(1f)
            modelFile
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    fun verifyInstalledModel() {
        if (contentVerified && isInstalled()) return
        check(isInstalled()) { "语音模型文件不完整或校验清单无效。" }
        val marker = markerFile.readText(StandardCharsets.US_ASCII)
        val expectedModel = "model=${modelFile.length()}:${sha256(modelFile)}"
        val expectedTokens = "tokens=${tokensFile.length()}:${sha256(tokensFile)}"
        check(marker.contains(expectedModel) && marker.contains(expectedTokens)) { "语音模型校验失败。" }
        contentVerified = true
    }

    private fun extractArchive(
        stagedModel: File,
        stagedTokens: File,
        onProgress: (Float) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var foundModel = false
        var foundTokens = false
        val progressStep = longArrayOf(0L)
        val buffer = ByteArray(1024 * 1024)
        appContext.assets.open(ARCHIVE_ASSET).use { asset ->
            val checked = DigestInputStream(asset, digest)
            val progress = ProgressInputStream(checked) { bytes ->
                if (bytes - progressStep[0] >= PROGRESS_STEP_BYTES) {
                    progressStep[0] = bytes
                    onProgress((bytes.toFloat() / ARCHIVE_BYTES).coerceIn(0f, 0.99f))
                }
            }
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(progress))).use { tar ->
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("语音模型安装已取消。")
                    val entry = tar.nextTarEntry ?: break
                    if (!entry.isFile) continue
                    val target = when {
                        entry.name.replace('\\', '/').endsWith("/model.int8.onnx") -> stagedModel.also { foundModel = true }
                        entry.name.replace('\\', '/').endsWith("/tokens.txt") -> stagedTokens.also { foundTokens = true }
                        else -> null
                    } ?: continue
                    BufferedOutputStream(FileOutputStream(target)).use { output ->
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("语音模型安装已取消。")
                            val count = tar.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
        check(ARCHIVE_SHA256.equals(digest.digest().toHex(), ignoreCase = true)) { "内置语音模型压缩包校验失败。" }
        check(foundModel && foundTokens) { "语音模型压缩包缺少必要文件。" }
        onProgress(0.99f)
    }

    private fun writeMarkerAtomically(modelHash: String, tokensHash: String) {
        val markerPart = File(modelDirectory, "installed.sha256.part")
        markerPart.writeText(
            "$ARCHIVE_SHA256\nmodel=${modelFile.length()}:$modelHash\ntokens=${tokensFile.length()}:$tokensHash\n",
            StandardCharsets.US_ASCII,
        )
        moveReplace(markerPart, markerFile)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun moveReplace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class ProgressInputStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var bytesRead = 0L
        override fun read(): Int = super.read().also { if (it >= 0) report(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also {
            if (it > 0) report(it)
        }
        private fun report(count: Int) {
            bytesRead += count
            onRead(bytesRead)
        }
    }

    private companion object {
        const val ARCHIVE_ASSET = "models/sensevoice/sensevoice-int8.tar.bz2"
        const val ARCHIVE_SHA256 = "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e"
        const val ARCHIVE_BYTES = 163_002_883L
        const val MIN_MODEL_BYTES = 200L * 1024 * 1024
        const val MIN_TOKENS_BYTES = 100L * 1024
        const val INSTALL_BYTES_REQUIRED = 812L * 1024 * 1024
        const val PROGRESS_STEP_BYTES = 2L * 1024 * 1024
    }
}
