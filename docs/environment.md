# 开发与测试环境

更新：2026-11-06。适用于 `D:\Canxu-project\Xujing`。

## 1. 主机与限制

- Windows，约 6 GB 物理内存。构建与模拟器**不要同时跑**，否则 Gradle 可能 native OOM。
- 项目、JDK、Android SDK、Gradle 缓存、模拟器镜像都在 D 盘。
- 不清理用户数据；测试用的模拟器会话、角色、预设可以保留。

## 2. 工具链

| 工具 | 路径 / 版本 |
| --- | --- |
| JDK | `D:\REA-Android\jdk`，21 |
| Gradle | `D:\Roleplay\tooling\gradle-8.13` |
| Android SDK | `D:\Roleplay\tooling\android-sdk` |
| Gradle 缓存 | `D:\Roleplay\tooling\gradle-cache` |
| 本地依赖仓库 | `D:\Roleplay\tooling\maven-cache` |
| Android 用户目录 / 临时文件 | `D:\Roleplay\tooling\android-user` / `D:\Roleplay\tooling\tmp` |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose compiler | 2.1.20 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| adb | `D:\REA-Android\platform-tools\adb.exe` |
| UI 辅助脚本 | `C:\Users\Administrator\AppData\Local\Temp\opencode\xujing-qa\ui.ps1`（本地工具，未入库） |

## 3. 构建

```powershell
# 首次或依赖有变化
powershell -NoProfile -File D:\Canxu-project\Xujing\build.ps1 :app:assembleDebug

# 缓存就绪后
powershell -NoProfile -File D:\Canxu-project\Xujing\build.ps1 :app:assembleDebug -Offline

# 纯 Kotlin 回归
powershell -NoProfile -File D:\Canxu-project\Xujing\build.ps1 :core:regressionChecks -Offline

# 只构建一个 ABI（不生成 universal 和其他 ABI）
powershell -NoProfile -File D:\Canxu-project\Xujing\build.ps1 :app:assembleDebug -Offline -Abi arm64-v8a
```

`build.ps1` 会先同步本地 Maven 缓存，再调用 Gradle；不安装应用、不跑模拟器。

### 构建注意事项

- **不要用管道接 Gradle 输出**（如 `| Select-String`）。守护进程会继承管道，命令可能一直不返回。改为重定向到文件后再读：
  ```powershell
  $log = "$env:TEMP\xujing-build.log"
  powershell -NoProfile -File D:\Canxu-project\Xujing\build.ps1 :app:assembleDebug -Offline *> $log
  Select-String -Path $log -Pattern '^e: |BUILD' -Encoding UTF8 | Select-Object -Last 10
  ```
- 出现 `OutOfMemoryError` / `daemon disappeared`：
  1. `& 'D:\Roleplay\tooling\gradle-8.13\bin\gradle.bat' --stop`
  2. 关掉模拟器，或结束 Kotlin 守护进程（`javac` 参数的 java 进程，约 700 MB）
  3. 确认可用内存后重试一次
- 产物：`app\build\outputs\apk\debug\app-<abi>-debug.apk` 与 `app-universal-debug.apk`。
- 发布目录：`dist\`，含各 ABI 包、`xujing-0.1.0-source.zip`、`SHA256SUMS.txt`。

## 4. 测试设备

### 默认：逍遥 Android 12（MEmu）

| 项 | 值 |
| --- | --- |
| 实例 | `MEmu_1`（`memuc` 索引 1） |
| 设备序列 | `127.0.0.1:21513` |
| Android / ABI | 12 / x86_64 |
| 屏幕 | 720×1280，density 240 |
| WebView | Chrome 104.0.5112.69 |

```powershell
$memu = 'D:\Program Files\Microvirt\MEmu\memuc.exe'
& $memu start -i 1
& $memu stop -i 1

$adb = 'D:\REA-Android\platform-tools\adb.exe'
& $adb kill-server
& $adb connect 127.0.0.1:21513
& $adb -s 127.0.0.1:21513 shell getprop ro.build.version.release   # 12
& $adb -s 127.0.0.1:21513 shell getprop ro.product.cpu.abi         # x86_64
& $adb -s 127.0.0.1:21513 shell dumpsys webviewupdate | Select-String 'Current WebView'
```

安装用 `app-x86_64-debug.apk`。首次安装后：

1. 设置 → 角色卡执行 → 打开「允许角色卡脚本」
2. 会话设置 → 提示词资料 → 打开「运行角色独立脚本」

### 备用：Android Studio AVD

| 项 | 值 |
| --- | --- |
| AVD | `Xujing_Android13` |
| AVD 目录 | `D:\Roleplay\tooling\android-avd` |
| 设备序列 | `emulator-5554` |

```powershell
$env:ANDROID_AVD_HOME = 'D:\Roleplay\tooling\android-avd'
$env:ANDROID_USER_HOME = 'D:\Roleplay\tooling\android-user'
Start-Process 'D:\Roleplay\tooling\android-sdk\emulator\emulator.exe' `
  -ArgumentList '-avd','Xujing_Android13','-no-window','-no-snapshot-save','-gpu','swiftshader_indirect','-no-audio'
```

## 5. ADB 规范

- 统一使用平台工具的 adb；逍遥自带 adb 的 server 是 31.0.3，平台工具是 37.0.1，混用会报 server 版本不匹配。
- 连接前先 `adb kill-server`，再 `adb connect`。
- 多设备时每条命令都带 `-s <serial>`。
- 旧模拟器关闭：`adb -s emulator-5554 emu kill`；逍遥：`memuc stop -i 1`。

## 6. UI 自动化

`ui.ps1` 默认设备已改为 `127.0.0.1:21513`，可用 `-Serial` 覆盖。

```powershell
$u = 'C:\Users\Administrator\AppData\Local\Temp\opencode\xujing-qa\ui.ps1'
powershell -NoProfile -ExecutionPolicy Bypass -File $u -Dump
powershell -NoProfile -ExecutionPolicy Bypass -File $u -Tap "360,640" -Wait 2
powershell -NoProfile -ExecutionPolicy Bypass -File $u -TapIndex 3 -Wait 3
powershell -NoProfile -ExecutionPolicy Bypass -File $u -Tap "360,640" -Text "hello"
powershell -NoProfile -ExecutionPolicy Bypass -File $u -Shot panel.png
```

- dump 中文以 `\uXXXX` 输出；先 dump 再点，不要复用上一次的坐标。
- 文件选择器：**点缩略图只会预览**，要点文件名文本才会把 URI 返回给残叙。
- 逍遥与 AVD 的分辨率、状态栏高度不同，跨设备不要复用坐标。

## 7. 本地 Mock API

```powershell
python D:\Canxu-project\Xujing\tools\mock_openai.py --port 8765 --capture tools\mock-api-captures\capture.jsonl
& $adb -s 127.0.0.1:21513 reverse tcp:8765 tcp:8765
```

- 应用里连接填 `http://127.0.0.1:8765/v1`，模型 `xujing-mock`。
- 捕获文件在 `tools\mock-api-captures\`，含提示词文本，已被 gitignore。
- 停止：结束监听 8765 的进程；移除转发：`adb -s <serial> reverse --remove tcp:8765`。

## 8. 产物选择

| 场景 | APK |
| --- | --- |
| 逍遥 / x86_64 模拟器 | `app-x86_64-debug.apk` |
| 多数 arm64 手机 | `app-arm64-v8a-debug.apk` |
| 旧 32 位 x86 模拟器 | `app-x86-debug.apk` |
| 不确定 | `app-universal-debug.apk` |

- 最低 Android 8.0（API 26）。
- 卡片与预设运行时使用现代 JS 语法，WebView 建议 Chrome/WebView ≥ 80；低于此版本会白屏或语法错误。

## 9. 已知坑

| 现象 | 原因 / 处理 |
| --- | --- |
| 构建命令不返回 | Gradle 守护进程占住管道；改为重定向到文件 |
| `insufficient memory` / `hs_err_pid*.log` | 主机内存不足；停 Gradle/Kotlin 守护进程、关模拟器后重试 |
| `device 'emulator-5554' not found` | `ui.ps1` 用了旧默认序列；显式 `-Serial` 或已改为 21513 |
| dump 内容像是旧界面 | 上一次 dump 的 `ui.xml` 残留；先删 `C:\Users\Administrator\AppData\Local\Temp\opencode\xujing-qa\ui.xml` 或确认 `adb pull` 成功 |
| 文件选择器打开成文本编辑器 | 点到了缩略图；点文件名 |
| 脚本按钮/面板不出现 | 全局「允许角色卡脚本」或会话「运行角色独立脚本」未开 |
| 预设正则/预设脚本不生效 | 预设未选中、脚本被禁用、条目不在 `prompt_order`，或正则 `placement` 不匹配 |
