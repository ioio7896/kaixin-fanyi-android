package com.localtranslate.hymt2

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import com.localtranslate.hymt2.data.TranslationRecord
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Exercises the real Compose screen; injected state replaces inference in the UI-only APK. */
class UiLayoutSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val results = Bundle()
        try {
            val automation = uiAutomation
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val delegate = MainActivity::class.java.getDeclaredField("translatorViewModel\$delegate").apply { isAccessible = true }.get(activity) as Lazy<*>
            val vm = delegate.value as TranslationViewModel
            val flowField = TranslationViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val flow = flowField.get(vm) as MutableStateFlow<TranslatorUiState>
            // Missing bundled assets fail quickly in this dedicated UI build.
            val timeout = System.currentTimeMillis() + 10_000
            while (flow.value.error == null && System.currentTimeMillis() < timeout) Thread.sleep(100)
            check(flow.value.error != null) { "UI-only model initialization did not finish" }
            val ready = TranslatorUiState(
                modelReady = true, modelStatus = "本地模型已就绪", installProgress = 1f,
                sourceText = "请问最近的地铁站在哪里？", targetLanguage = "英语",
            )
            fun apply(value: TranslatorUiState) {
                runOnMainSync { flow.value = value }
                waitForIdleSync()
                Thread.sleep(700)
                automation.waitForIdle(100, 5000)
                automation.clearCache()
            }
            fun activeRoot(): AccessibilityNodeInfo {
                repeat(25) {
                    automation.rootInActiveWindow?.let { root ->
                        if (root.packageName?.toString() == targetContext.packageName) return root
                    }
                    Thread.sleep(200)
                }
                error("App accessibility window did not become available")
            }
            fun capture(name: String) {
                val screenshot = checkNotNull(uiAutomation.takeScreenshot())
                val directory = File(targetContext.getExternalFilesDir(null), "ui-smoke").apply { mkdirs() }
                File(directory, name).outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            fun validateHeader() {
                val header = findText(activeRoot(), "HY2 翻译")
                checkNotNull(header) { "Header not exposed in accessibility tree" }
                val bounds = Rect().also { header.getBoundsInScreen(it) }
                var safeTop = 0
                runOnMainSync { safeTop = activity.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top }
                check(bounds.top >= safeTop) { "Header overlaps system status/cutout area: $bounds, safeTop=$safeTop" }
                check(bounds.width() > 0 && bounds.height() > 0) { "Header is clipped" }
            }
            fun clickText(text: String) {
                val node = checkNotNull(findText(activeRoot(), text))
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                check(!bounds.isEmpty) { "Action has no touch bounds: $text" }
                val now = SystemClock.uptimeMillis()
                for ((action, time) in listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 50)) {
                    val event = MotionEvent.obtain(now, time, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    check(automation.injectInputEvent(event, true))
                    event.recycle()
                }
            }
            apply(ready)
            validateHeader()
            capture("main.png")
            val editable = findEditable(activeRoot())
            checkNotNull(editable) { "Input field is missing" }
            editable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(1200)
            validateHeader()
            capture("keyboard.png")
            runOnMainSync { activity.window.insetsController?.hide(WindowInsets.Type.ime()) }
            Thread.sleep(500)
            val translated = ready.copy(
                translation = "Where is the nearest subway station?",
                translatedSourceText = ready.sourceText, translatedTargetLanguage = ready.targetLanguage,
                recentTranslations = listOf(TranslationRecord(id = 1, sourceText = ready.sourceText, targetLanguage = ready.targetLanguage, translatedText = "Where is the nearest subway station?")),
            )
            check(translated.translationCurrent)
            check(!translated.copy(sourceText = "新的问题").translationCurrent) { "Edited source must invalidate result" }
            check(!translated.copy(targetLanguage = "日语").translationCurrent) { "Changed target must invalidate result" }
            apply(translated)
            validateHeader()
            capture("result.png")
            clickText("清空")
            Thread.sleep(700)
            automation.waitForIdle(100, 5000)
            automation.clearCache()
            check(flow.value.sourceText.isBlank()) { "Clear action did not update input" }
            check(findText(activeRoot(), "原文或目标语言已修改，请重新翻译。") != null) { "Stale-result warning is missing" }
            val clipboard = targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            fun clipboardText(): String? = clipboard.primaryClip?.let { if (it.itemCount > 0) it.getItemAt(0).text?.toString() else null }
            val beforeCopy = clipboardText()
            check(beforeCopy != translated.translation) { "Clipboard precondition must differ from result" }
            clickText("复制")
            Thread.sleep(200)
            check(clipboardText() == beforeCopy) { "Stale copy action changed clipboard" }
            capture("stale.png")
            clickText("撤销")
            Thread.sleep(500)
            check(flow.value.sourceText == ready.sourceText && flow.value.translationCurrent) { "Undo did not restore original input/result" }
            results.putString("stream", "UI_SMOKE_OK: header insets, keyboard layout, result state, stale action guards\n")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            runCatching {
                val directory = File(targetContext.getExternalFilesDir(null), "ui-smoke").apply { mkdirs() }
                uiAutomation.takeScreenshot()?.let { screenshot ->
                    File(directory, "failure.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot.recycle()
                }
            }
            results.putString("stream", "UI_SMOKE_FAILED: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (index in 0 until node.childCount) findEditable(node.getChild(index))?.let { return it }
        return null
    }

    private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true) return node
        for (index in 0 until node.childCount) findText(node.getChild(index), text)?.let { return it }
        return null
    }
}
