package com.localtranslate.hymt2

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val translatorViewModel: TranslationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HyTranslatorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OfflineTranslatorScreen(
                        vm = translatorViewModel,
                        onReplaceProcessedText = ::returnProcessedText,
                    )
                }
            }
        }
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
                if (text.isBlank()) {
                    translatorViewModel.showInputError("没有收到可翻译的选中文本。")
                    return
                }
                val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
                translatorViewModel.receiveText(text, processTextReadOnly = readOnly)
            }

            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                if (text.isBlank()) {
                    translatorViewModel.showInputError("分享内容中没有文本。")
                    return
                }
                translatorViewModel.receiveText(text)
            }

            ACTION_NEW_TRANSLATION -> translatorViewModel.startNewTranslation()

            ACTION_PASTE_TRANSLATE -> {
                val text = readClipboardText()
                if (text.isNullOrBlank()) {
                    translatorViewModel.startNewTranslation()
                    translatorViewModel.showInputError("剪贴板中没有文本。")
                } else {
                    translatorViewModel.receiveText(text)
                }
            }

            else -> translatorViewModel.clearProcessTextMode()
        }
    }

    private fun readClipboardText(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    private fun returnProcessedText(text: String) {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text),
        )
        finish()
    }

    companion object {
        const val ACTION_NEW_TRANSLATION = "com.localtranslate.hymt2.action.NEW_TRANSLATION"
        const val ACTION_PASTE_TRANSLATE = "com.localtranslate.hymt2.action.PASTE_TRANSLATE"
    }
}

internal fun copyText(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Hy-MT2 translation", value))
}

internal fun shareText(context: Context, value: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, value)
    }
    val chooserIntent = Intent.createChooser(sendIntent, "分享译文").apply {
        putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            arrayOf(ComponentName(context, MainActivity::class.java)),
        )
    }
    context.startActivity(chooserIntent)
}

internal fun requestQuickSettingsTile(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val statusBarManager = context.getSystemService(StatusBarManager::class.java) ?: return
    statusBarManager.requestAddTileService(
        ComponentName(context, TranslatorTileService::class.java),
        context.getString(R.string.quick_tile_label),
        Icon.createWithResource(context, R.drawable.ic_translate),
        context.mainExecutor,
    ) { result ->
        val message = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已添加快捷设置磁贴。"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "快捷设置磁贴已存在。"
            else -> "未添加快捷设置磁贴。"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
