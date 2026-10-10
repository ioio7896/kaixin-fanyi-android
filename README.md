# Hy-MT2 安卓离线翻译

Android 原生子项目：Kotlin + Jetpack Compose UI，ViewModel/协程、Room 本机历史、DataStore 设置，C++/JNI 调用 llama.cpp，运行本机 Hy-MT2-1.8B Q4_K_M GGUF。翻译过程不联网，Manifest 不声明 `INTERNET` 或网络状态权限。

语音输入使用随 APK 内置的 sherpa-onnx SenseVoice INT8 模型，在设备上识别普通话、英语、日语、韩语和粤语。中英语音自动换向默认开启：识别为中文时译成英语，识别为英语时译成中文；可在页面中关闭，改用手动目标语言。语音转写会先填入原文框供用户检查，不会自动发送录音或保存音频。

译文朗读使用 Android 系统已安装的离线 TTS 音色。应用会跳过要求网络的音色；如果设备没有中文或英文离线语音，需要先到系统语音设置安装对应语音数据。朗读可用于原文和译文。应用新增麦克风运行时权限，但仍不声明网络权限。

首次使用语音输入会从 APK 解包约 228 MiB 的识别模型，需预留约 800 MiB 空间。语音识别前会释放 Hy-MT2 推理引擎，识别结束后恢复模型，以降低低内存设备上的峰值占用；恢复期间翻译按钮会暂时不可用。录音最长 60 秒，可提前结束或取消。

## 界面与交互

- 首页采用文字优先布局：语言方向、原文输入、翻译按钮、译文阅读。模型就绪后只保留顶部简短状态；历史和语音选项放在独立入口。
- 支持粘贴、清空撤销、录音计时、原文与译文分别朗读、译文选择复制、分享和放大阅读。原文或目标语言变化后，旧译文会提示重新翻译并停用相关操作。
- 历史页可搜索最近 30 条记录、删除并撤销；清空全部历史需要确认。
- 跟随系统浅色/深色模式。根布局统一处理状态栏、刘海、导航栏和键盘安全区域，避免重复留白；键盘打开时收起底部导航。
- `scripts/ui-smoke.init.gradle` 和 `scripts/ui-smoke/` 仅用于模拟器界面检查。该构建排除模型和原生库，输出到 `.ui-smoke-build/`，不可作为翻译应用分发。正常 APK 继续使用 `:app:assembleDebug` 构建。

## 模型打包与首次启动

- Gradle 从仓库根目录 `models/Hy-MT2-1.8B-GGUF/` 读取已有 GGUF 和 Apache-2.0 许可证。
- 构建时把它们装入 APK 的 `assets/hy-mt2-model.zip`，模型采用 ZIP `STORED`，不再做无意义压缩。
- Android 不会在安装过程中执行应用代码。首次启动时，启动页在应用私有目录中解压模型、校验文件大小和 SHA-256，然后加载本地推理引擎。模型路径为 `files/models/Hy-MT2-1.8B/Hy-MT2-1.8B-Q4_K_M.gguf`。
- APK 会超过 1 GiB；首次启动还需要足够空间写入一份解压后的模型。模型已安装后，翻译不需要网络。

## 构建准备

本机需有 Android Studio、JDK 17、Android SDK、NDK 和 CMake。C++ 推理依赖 llama.cpp `v0.2.0`，只用于构建原生库：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-llama.ps1
```

从 Android Studio 打开本仓库根目录并构建 `app`。Windows 路径若包含中文，CMake/Ninja 可能损坏路径；可在项目根目录用 ASCII 盘符映射后构建：

```powershell
subst H: "$((Get-Location).Path)"
Push-Location H:\
try {
    .\gradlew.bat :app:assembleDebug
} finally {
    Pop-Location
    subst H: /D
}
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

打包模型需要根目录已有以下文件：

```text
../models/Hy-MT2-1.8B-GGUF/Hy-MT2-1.8B-Q4_K_M.gguf
../models/Hy-MT2-1.8B-GGUF/LICENSE.txt
```

语音识别模型资源位于 `app/src/main/assets/models/sensevoice/sensevoice-int8.tar.bz2`，来自 sherpa-onnx 官方 SenseVoice INT8 模型包。模型包 SHA-256：`7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e`。APK 内同时包含 sherpa-onnx Android 1.13.4 AAR；分发应用时请保留模型包和依赖各自的上游许可及归属说明。

翻译模型权重不包含在本仓库中。请在开发电脑上取得模型和许可证，放入上述 `../models/Hy-MT2-1.8B-GGUF/` 目录；最终 Android 应用不申请联网权限。

本机模型文件 SHA-256 应为：`dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`。

## 已知边界

- 当前只打包 `arm64-v8a`，使用 CPU 推理；GPU/NPU 后端尚未接入。
- 首次安装后的解压发生在第一次启动，不可能由 Android 安装器直接运行应用代码。
- 1.8B Q4_K_M 文件约 1.05 GiB；APK 和首次解压需要大量存储空间。低内存设备可能无法稳定载入模型。
- 如果要发布 APK/AAB，需按模型 Apache-2.0 条款保留许可证，并同时核对 llama.cpp 及其依赖的许可和归属。
