package com.localtranslate.hymt2

import android.content.Context
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class BundledModelInstaller(private val context: Context) {
    suspend fun install(onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val modelDirectory = File(context.filesDir, "models/Hy-MT2-1.8B")
        val model = File(modelDirectory, MODEL_FILENAME)
        val marker = File(modelDirectory, ".installed.sha256")
        if (model.isFile && model.length() == MODEL_BYTES && marker.takeIf(File::isFile)?.readText()?.trim() == MODEL_SHA256) {
            onProgress(1f)
            return@withContext model
        }

        val required = MODEL_BYTES + MIN_FREE_SPACE_MARGIN
        val freeBytes = StatFs(context.filesDir.absolutePath).availableBytes
        check(freeBytes >= required) {
            "需要至少 ${(required / GIB).toInt()} GiB 可用空间才能解压模型；当前可用 ${(freeBytes / GIB).toInt()} GiB。"
        }

        val stagingDirectory = File(context.filesDir, "models/.hymt2-installing")
        stagingDirectory.deleteRecursively()
        check(stagingDirectory.mkdirs()) { "无法创建模型暂存目录。" }

        val stagedModel = File(stagingDirectory, MODEL_FILENAME)
        val stagedLicense = File(stagingDirectory, "LICENSE.txt")
        val digest = MessageDigest.getInstance("SHA-256")
        var extractedBytes = 0L
        var lastReportedBytes = 0L

        try {
            ZipInputStream(BufferedInputStream(context.assets.open(MODEL_ASSET), IO_BUFFER_SIZE)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }

                    val output = when (entry.name) {
                        "models/$MODEL_FILENAME" -> stagedModel
                        "models/LICENSE.txt" -> stagedLicense
                        else -> throw IllegalStateException("模型安装包包含意外文件：${entry.name}")
                    }

                    FileOutputStream(output).use { fileOutput ->
                        BufferedOutputStream(fileOutput, IO_BUFFER_SIZE).use { bufferedOutput ->
                            val buffer = ByteArray(IO_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                bufferedOutput.write(buffer, 0, count)
                                if (output === stagedModel) {
                                    digest.update(buffer, 0, count)
                                    extractedBytes += count
                                    if (extractedBytes - lastReportedBytes >= PROGRESS_STEP_BYTES) {
                                        onProgress((extractedBytes.toFloat() / MODEL_BYTES).coerceIn(0f, 0.99f))
                                        lastReportedBytes = extractedBytes
                                    }
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }

            check(stagedModel.isFile && stagedModel.length() == MODEL_BYTES) {
                "安装包中的模型大小不正确。"
            }
            check(stagedLicense.isFile) { "安装包缺少模型许可证文件。" }
            val actualHash = digest.digest().toHex()
            check(actualHash.equals(MODEL_SHA256, ignoreCase = true)) {
                "模型 SHA-256 校验失败；请重新安装应用。"
            }

            check(modelDirectory.mkdirs() || modelDirectory.isDirectory) { "无法创建模型目录。" }
            val installedLicense = File(modelDirectory, "LICENSE.txt")
            check(stagedLicense.copyTo(installedLicense, overwrite = true).isFile) { "无法保存模型许可证。" }
            if (model.exists()) check(model.delete()) { "无法替换旧模型文件。" }
            check(stagedModel.renameTo(model)) { "无法将模型移动到应用存储。" }
            marker.writeText(MODEL_SHA256)
            onProgress(1f)
            model
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val MODEL_ASSET = "hy-mt2-model.zip"
        const val MODEL_FILENAME = "Hy-MT2-1.8B-Q4_K_M.gguf"
        const val MODEL_BYTES = 1_133_080_448L
        const val MODEL_SHA256 = "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699"
        const val IO_BUFFER_SIZE = 4 * 1024 * 1024
        const val PROGRESS_STEP_BYTES = 16L * 1024 * 1024
        const val MIN_FREE_SPACE_MARGIN = 64L * 1024 * 1024
        const val GIB = 1024L * 1024 * 1024
    }
}
