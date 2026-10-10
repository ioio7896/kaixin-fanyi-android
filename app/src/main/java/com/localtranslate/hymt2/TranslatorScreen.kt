package com.localtranslate.hymt2

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OfflineTranslatorScreen(vm: TranslationViewModel, onReplaceProcessedText: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showClearHistory by rememberSaveable { mutableStateOf(false) }
    var showExpandedTranslation by rememberSaveable { mutableStateOf(false) }
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var historyUndoVersion by remember { mutableIntStateOf(0) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    val translationList = rememberLazyListState()
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) vm.startVoiceInput() else vm.reportAudioPermissionDenied()
    }
    fun feedback(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun dismissKeyboard() { focus.clearFocus(); keyboard?.hide() }
    fun startVoice() {
        dismissKeyboard()
        if (state.recording) vm.finishVoiceInput()
        else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startVoiceInput()
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(state.recording) {
        recordingSeconds = 0
        if (state.recording) {
            val started = SystemClock.elapsedRealtime()
            while (true) {
                recordingSeconds = ((SystemClock.elapsedRealtime() - started) / 1000).toInt().coerceAtMost(60)
                delay(1000)
            }
        }
    }
    // A new incoming share/selection belongs on the translation page.
    LaunchedEffect(state.sourceText, state.translating) {
        if (state.translating) page = 0
    }
    LaunchedEffect(state.translation) {
        if (state.translation.isNotBlank() && page == 0) {
            delay(120)
            val resultIndex = 3 + (if (!state.modelReady && !state.voiceBusy) 1 else 0) + (if (state.error != null) 1 else 0)
            translationList.animateScrollToItem(resultIndex)
        }
    }
    BackHandler(enabled = page != 0) { page = 0 }

    // Own all system insets here: status/caption bars, cutouts, navigation and IME.
    // Child scaffold/navigation insets are zero to avoid double top/bottom padding.
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TranslatorHeader(
                    state = state,
                    title = if (page == 0) "HY2 翻译" else "翻译历史",
                    recordingSeconds = recordingSeconds,
                    onSettings = { showSettings = true },
                )
            },
            bottomBar = {
                if (!keyboardVisible) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.background,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        tonalElevation = 0.dp,
                    ) {
                        NavigationBarItem(
                            selected = page == 0, onClick = { page = 0 },
                            icon = { AppIcon(R.drawable.ic_translate, null) }, label = { Text("翻译") },
                        )
                        NavigationBarItem(
                            selected = page == 1, onClick = { dismissKeyboard(); page = 1 },
                            icon = { AppIcon(R.drawable.ic_history, null) }, label = { Text("历史") },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (page == 0) {
                    LazyColumn(
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                        state = translationList,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (!state.modelReady && !state.voiceBusy) {
                            item {
                                ContentCard {
                                    Text(state.modelStatus, style = MaterialTheme.typography.titleMedium)
                                    if (state.modelInitializationFailed) {
                                        TextButton(onClick = vm::retryModelInitialization) { Text("重试加载") }
                                    } else {
                                        if (state.installProgress < 1f) {
                                            LinearProgressIndicator(progress = { state.installProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                            Text("首次准备本机模型 · ${(state.installProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                        } else LoadingLine("正在载入翻译模型…")
                                    }
                                }
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    state.recognizedSourceLanguage?.let(::languageLabel) ?: "自动识别",
                                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                )
                                Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                                TargetLanguagePicker(state.targetLanguage, vm::setTargetLanguage, !state.inputBusy)
                            }
                        }
                        item {
                            ContentCard {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("原文", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    TextButton(
                                        onClick = {
                                            val original = vm.state.value.sourceText
                                            vm.setSourceText("")
                                            scope.launch {
                                                if (snackbar.showSnackbar("已清空原文", actionLabel = "撤销") == SnackbarResult.ActionPerformed &&
                                                    vm.state.value.sourceText.isBlank() && !vm.state.value.inputBusy
                                                ) vm.setSourceText(original)
                                            }
                                        },
                                        enabled = state.sourceText.isNotEmpty() && !state.inputBusy,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) { Text("清空") }
                                }
                                OutlinedTextField(
                                    value = state.sourceText, onValueChange = vm::setSourceText,
                                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "原文输入框" }, minLines = 3, maxLines = 8,
                                    placeholder = { Text("输入文字，或粘贴需要翻译的内容") },
                                    enabled = !state.inputBusy, shape = RoundedCornerShape(12.dp),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = clipboard.primaryClip
                                            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
                                            if (text.isNullOrBlank()) feedback("剪贴板中没有文字") else vm.setSourceText(text)
                                        }, enabled = !state.inputBusy, modifier = Modifier.heightIn(min = 48.dp),
                                    ) { Text("粘贴") }
                                    TextButton(
                                        onClick = ::startVoice,
                                        enabled = state.recording || (state.modelReady && !state.inputBusy),
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        AppIcon(R.drawable.ic_voice, null)
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (state.recording) "结束录音" else "语音输入")
                                    }
                                    if (state.recording) {
                                        TextButton(onClick = vm::cancelVoiceInput, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消录音") }
                                    } else if (state.sourceText.isNotBlank()) {
                                        TextButton(onClick = vm::speakSource, enabled = !state.inputBusy, modifier = Modifier.heightIn(min = 48.dp)) {
                                            AppIcon(R.drawable.ic_volume, null)
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (state.speakingTarget == SpeakingTarget.SOURCE) "停止原文" else "朗读原文")
                                        }
                                    }
                                }
                                if (state.recording) {
                                    Text("录音中 ${formatDuration(recordingSeconds)} / 01:00", color = MaterialTheme.colorScheme.primary)
                                }
                                when {
                                    state.speechModelInstalling -> {
                                        LinearProgressIndicator(progress = { state.speechModelInstallProgress }, modifier = Modifier.fillMaxWidth())
                                        Text("首次准备语音模型 · ${(state.speechModelInstallProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                    }
                                    state.voicePreparing -> LoadingLine("正在准备离线语音输入…")
                                    state.recognizingSpeech -> LoadingLine(state.speechStatus ?: "正在识别…")
                                    !state.recording -> state.speechStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                        state.error?.let { error ->
                            item {
                                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                                        FlowRow {
                                            if (error.contains("语音数据") || error.contains("离线朗读")) {
                                                TextButton(onClick = { openSpeechSettings(context) }) { Text("打开语音设置") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Button(
                                onClick = {
                                    dismissKeyboard()
                                    if (state.translating) vm.cancelTranslation() else vm.translate()
                                },
                                enabled = state.translating || (state.modelReady && !state.voiceBusy && state.sourceText.isNotBlank()),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp),
                            ) {
                                if (state.translating) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.width(10.dp))
                                    Text("正在翻译 · 取消")
                                } else Text(if (state.translation.isNotBlank() && !state.translationCurrent) "重新翻译" else "翻译")
                            }
                        }
                        item {
                            TranslationResult(
                                state,
                                onSpeak = vm::speakTranslation,
                                onCopy = { copyText(context, state.translation); feedback("已复制译文") },
                                onShare = { shareText(context, state.translation) },
                                onExpand = { showExpandedTranslation = true },
                                onReplace = { onReplaceProcessedText(state.translation) },
                            )
                        }
                    }
                } else {
                    HistoryPage(
                        state, historyQuery, onQuery = { historyQuery = it },
                        onClear = { showClearHistory = true },
                        onLoad = { vm.loadHistory(it); dismissKeyboard(); page = 0 },
                        onDelete = { record ->
                            val undoVersion = historyUndoVersion
                            scope.launch {
                                vm.deleteHistory(record)
                                if (snackbar.showSnackbar("已删除记录", actionLabel = "撤销") == SnackbarResult.ActionPerformed &&
                                    undoVersion == historyUndoVersion
                                ) vm.restoreHistory(record)
                            }
                        },
                    )
                }
            }
        }
    }
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false }, title = { Text("设置") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("语音中英自动换向", modifier = Modifier.weight(1f))
                        Switch(state.autoBilingualVoiceDirection, vm::setAutoBilingualVoiceDirection, enabled = !state.voiceBusy)
                    }
                    Text("中文语音译成英语，英语语音译成中文。文字输入使用首页选择的目标语言。", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("朗读使用系统离线音色。")
                    TextButton(onClick = { openSpeechSettings(context) }) { Text("管理朗读音色") }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        TextButton(onClick = { requestQuickSettingsTile(context) }) { Text("添加快捷设置磁贴") }
                    }
                    Text("Hy-MT2-1.8B · 本机推理\n翻译、语音识别和历史记录均留在本机。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("完成") } },
        )
    }
    if (showClearHistory) {
        AlertDialog(
            onDismissRequest = { showClearHistory = false }, title = { Text("清空全部历史？") },
            text = { Text("将删除本机保存的所有翻译记录，无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    historyUndoVersion++
                    snackbar.currentSnackbarData?.dismiss()
                    vm.clearHistory()
                    showClearHistory = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { showClearHistory = false }) { Text("取消") } },
        )
    }
    if (showExpandedTranslation && state.translationCurrent) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showExpandedTranslation = false }) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(20.dp)) {
                    Text("译文 · ${state.translatedTargetLanguage}", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(modifier = Modifier.weight(1f, fill = false).padding(vertical = 16.dp)) {
                        item { SelectionContainer { Text(state.translation, fontSize = 26.sp, lineHeight = 38.sp) } }
                    }
                    TextButton(onClick = { showExpandedTranslation = false }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun TranslatorHeader(state: TranslatorUiState, title: String, recordingSeconds: Int, onSettings: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
            IconButton(onClick = onSettings) { AppIcon(R.drawable.ic_settings, "设置") }
        }
        val status = when {
            state.recording -> "录音中 ${formatDuration(recordingSeconds)} · 离线识别"
            state.translating -> "正在翻译 · 内容留在本机"
            state.speechModelInstalling -> "首次准备语音模型"
            state.voicePreparing -> "正在准备离线语音"
            state.recognizingSpeech -> state.modelStatus
            state.modelReady -> "离线就绪 · 内容留在本机"
            else -> state.modelStatus
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun ContentCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

@Composable
private fun AppIcon(resource: Int, description: String?) {
    Icon(painterResource(resource), contentDescription = description, modifier = Modifier.size(22.dp))
}

@Composable
private fun LoadingLine(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TargetLanguagePicker(selected: String, onSelected: (String) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("$selected ▾", style = MaterialTheme.typography.titleMedium)
        }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
            listOf("中文", "英语", "日语", "韩语", "法语", "德语", "西班牙语", "俄语").forEach { language ->
                DropdownMenuItem(text = { Text(language) }, onClick = { onSelected(language); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranslationResult(
    state: TranslatorUiState,
    onSpeak: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onExpand: () -> Unit, onReplace: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    val canUse = state.translationCurrent && !state.inputBusy
    ContentCard {
        Text(
            if (state.translation.isBlank()) "译文" else "译文 · ${state.translatedTargetLanguage ?: state.targetLanguage}",
            style = MaterialTheme.typography.titleMedium,
        )
        when {
            state.translating -> LoadingLine("正在生成译文…")
            state.translation.isBlank() -> Text("译文将在这里显示", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                if (!state.translationCurrent) {
                    Text("原文或目标语言已修改，请重新翻译。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                SelectionContainer {
                    Text(state.translation, fontSize = 20.sp, lineHeight = 30.sp, color = if (state.translationCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSpeak, enabled = canUse, modifier = Modifier.heightIn(min = 48.dp)) {
                        AppIcon(R.drawable.ic_volume, null); Spacer(Modifier.width(6.dp))
                        Text(if (state.speakingTarget == SpeakingTarget.TRANSLATION) "停止译文" else "朗读")
                    }
                    TextButton(onClick = onCopy, enabled = canUse, modifier = Modifier.heightIn(min = 48.dp)) {
                        AppIcon(R.drawable.ic_copy, null); Spacer(Modifier.width(6.dp)); Text("复制")
                    }
                    Box {
                        IconButton(onClick = { more = true }, enabled = canUse) { AppIcon(R.drawable.ic_more, "更多译文操作") }
                        DropdownMenu(more && canUse, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text("分享译文") }, onClick = { more = false; onShare() })
                            DropdownMenuItem(text = { Text("放大阅读") }, onClick = { more = false; onExpand() })
                            if (state.processTextReadOnly == false) {
                                DropdownMenuItem(text = { Text("替换选中的原文") }, onClick = { more = false; onReplace() })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryPage(
    state: TranslatorUiState, query: String, onQuery: (String) -> Unit, onClear: () -> Unit,
    onLoad: (com.localtranslate.hymt2.data.TranslationRecord) -> Unit,
    onDelete: (com.localtranslate.hymt2.data.TranslationRecord) -> Unit,
) {
    val records = state.recentTranslations.filter {
        query.isBlank() || it.sourceText.contains(query, ignoreCase = true) || it.translatedText.contains(query, ignoreCase = true)
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    LazyColumn(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最近 30 条 · 仅保存在本机", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear, enabled = state.recentTranslations.isNotEmpty()) { Text("清空") }
            }
            OutlinedTextField(query, onQuery, label = { Text("搜索原文或译文") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp))
        }
        if (records.isEmpty()) {
            item { Text(if (query.isBlank()) "还没有翻译记录" else "没有找到匹配的记录", modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(records, key = { it.id }) { record ->
            Card(
                onClick = { onLoad(record) }, enabled = !state.inputBusy,
                shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.sourceText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(record.translatedText, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${record.targetLanguage} · ${dateFormat.format(Date(record.createdAtMillis))}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onDelete(record) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除") }
                    }
                }
            }
        }
    }
}

private fun languageLabel(code: String): String = when (code.lowercase()) {
    "zh" -> "中文"
    "en" -> "英语"
    "ja" -> "日语"
    "ko" -> "韩语"
    "yue" -> "粤语"
    else -> "自动识别"
}

private fun formatDuration(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)

private fun openSpeechSettings(context: Context) {
    try {
        context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
    } catch (_: android.content.ActivityNotFoundException) {
        try {
            context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "请到系统设置中打开文字转语音设置。", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
