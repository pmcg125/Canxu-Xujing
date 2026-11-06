# 残叙·残念

Android 优先的本地角色扮演应用。原生 Jetpack Compose Material 3 界面；使用用户自行配置的 OpenAI 兼容服务生成回复。

项目路径：`D:\Canxu-project\Xujing`。当前版本：`0.1.0` 开发版。

本机 `dist` 目录提供 ARM64 开发 APK、通用开发 APK、其他架构分包、新项目源码归档与 SHA-256 校验清单。ARM64 包约 27.7 MiB，最低 Android 8.0。

## 首版功能

- 本地角色、用户人设、单角色和多角色会话，气泡 / 阅读模式切换。
- 自定义 API URL、请求头、模型、生成参数、流式响应；摘要可使用独立连接。
- 角色设定、世界书、预设、文风、正则、常用宏、剧情变量的提示词编排。
- 编辑、续写、重新生成、候选回复、回溯、手动 / 自动增量剧情摘要和提示词预览。
- 会话中可选使用导入预设的采样参数；聊天和摘要分别保留连接参数。
- 本地 ONNX 与远程 Embeddings 长期记忆；不同模型配置使用独立向量索引。
- 长期记忆可按会话设置查询使用的近期消息数，召回片段按剧情顺序注入。
- 聊天记录关键词搜索和跳转、角色置顶、自定义背景图片、气泡透明度与样式；手动生成回复建议并填入输入框。
- 可按会话启用角色独立脚本，并逐项停用；生成前事件可修改实际请求的消息数组。新增接入范围见 [功能进度](docs/feature-progress.md)。
- PiuPiu 2.7.9 交换格式适配：PNG 角色卡、资料 JSON、聊天 JSON、设置 JSON、角色会话 ZIP。
- 角色卡 HTML/CSS 呈现，按需开启脚本；提供部分 TavernHelper / MVU 兼容接口。
- Android Keystore 保护本机加密密钥；SQLite 中的角色、会话、记忆及 API 密钥加密保存。

不含账号、邀请、VIP、社区、市场、在线聊天室或 Live2D。语音和生图留待后续。安装包不含模型权重。

**开发版尚未完成全部 PiuPiu 行为等价验证。** 文件适配、复杂脚本执行、本地模型推理和不同设备上的表现分别记录，不能用“导入成功”代表全部功能兼容。详见 [兼容范围](docs/compatibility.md)。

## 使用顺序

1. 在「设置 → API 连接」添加服务。URL 填服务的 API 基地址，例如 `https://example.com/v1`；也接受完整的 `/chat/completions` 地址。不自动添加 `/v1`。
2. 填写模型与密钥。自定义请求头为 JSON 对象，生成参数为 JSON 对象。留空已有连接的密钥输入框会保留原密钥。
3. 创建或导入角色，在角色页建立会话。会话设置中可选择人设、世界书、预设、正则、文风及独立摘要连接。
4. 启用长期记忆前，配置远程 Embeddings，或导入本地模型包。见 [本地模型包](docs/model-package.md)。普通聊天不要求本地模型。
5. 导入 PiuPiu 备份时合并到资料库；ID 冲突的角色、资料和会话分配新 ID。导入聊天 JSON 可替换当前打开会话的历史。

会话设置可开启“使用预设采样参数”和“自动整理剧情摘要”，默认关闭。自动摘要保留指定数量的近期原文，在发送新消息前按旧角色回复积累数量触发，额外调用摘要 API；长记录按上下文分批处理。摘要失败保留旧内容并继续聊天。

角色卡脚本默认关闭，可在设置中为可信角色卡开启。脚本接口可操作当前会话和调用已配置的生成服务。宿主不向脚本提供 API 密钥、文件系统或 Android 原生能力。

## 构建

```powershell
Set-Location D:\Canxu-project\Xujing
.\build.ps1 assembleDebug
```

本机依赖准备完成后可运行 `.\build.ps1 assembleDebug -Offline`。脚本会先把既有 Gradle 缓存整理为 D 盘本地 Maven 仓库，优先使用硬链接，避免重新下载和重复占用磁盘。

本机工具目录：

| 工具 | 路径 / 版本 |
| --- | --- |
| JDK | `D:\REA-Android\jdk`，21 |
| Gradle | `D:\Roleplay\tooling\gradle-8.13` |
| Android SDK | `D:\Roleplay\tooling\android-sdk` |
| Gradle 缓存 | `D:\Roleplay\tooling\gradle-cache` |
| 本地依赖仓库 | `D:\Roleplay\tooling\maven-cache` |
| Android 用户目录 / 临时文件 | `D:\Roleplay\tooling\android-user` / `D:\Roleplay\tooling\tmp` |
| adb | `D:\REA-Android\platform-tools\adb.exe` |
| 默认测试机 | 逍遥 Android 12（`MEmu_1`，`127.0.0.1:21513`，x86_64） |
| 备用测试机 | AVD `Xujing_Android13`（`emulator-5554`） |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose compiler | 2.1.20 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |

输出目录：`app\build\outputs\apk\debug`，包括按 CPU 架构拆分的 APK 和 `app-universal-debug.apk` 通用包。多数近年 Android 手机可使用 `app-arm64-v8a-debug.apk`。首次构建需要下载依赖。`build.ps1` 只构建指定任务，不安装应用或运行测试。

完整的环境路径、内存限制、模拟器启动/连接方式和常见坑见 [开发与测试环境](docs/environment.md)。当前默认测试机为逍遥 Android 12（`127.0.0.1:21513`）；构建与模拟器不要同时运行，Gradle 输出请重定向到文件而不是接管道。

依照用户下载偏好，Gradle 优先使用阿里云 Google / Central / Gradle 插件镜像，官方仓库保留在后面；模型下载优先使用 hf-mirror.com。显式下载工具使用 `tools/Download-Resumable.ps1`，保留 `.part` 文件并以 HTTP Range 续传，完成后核对已知校验值。Gradle 自带下载使用缓存，不能据此保证跨进程断点续传；较大文件中断时可用此脚本接管。

## 模块

| 目录 | 职责 |
| --- | --- |
| `core` | Kotlin/JVM 数据模型、交换格式、提示词、世界书、宏、正则和变量，不依赖 Android |
| `app/data` | 本机加密存储、兼容 API、ONNX 分词/推理、记忆索引 |
| `app/ui` | 原生 MD3 界面和编辑器 |
| `app/runtime` | HTML 角色消息与受限脚本桥 |
| `docs` | 需求、实现边界和模型包规范 |

将来扩展其他平台时可复用 `core` 的逻辑和交换格式，并替换存储、网络、模型推理与 UI 适配层；当前尚不是完整 Kotlin Multiplatform 工程。

## 数据说明

应用无自建云端。聊天生成会把提示词发送到所配置的服务；远程 Embeddings 会发送选中的聊天片段。本地嵌入模型只用于检索，不在本机生成角色回复。

头像和附件当前保存在应用私有目录，未额外加密；不能把数据库加密视为所有文件都已加密。

导出文件为可移植明文，备份不包含 Keystore 密钥、API 密钥和本地模型权重；常见鉴权请求头会移除，非标准字段仍应由用户自行检查。保留原始备份，开发版的复杂格式尚待真实样本核对。

## 当前交付记录

编译与模拟器验证结果见 `docs/build-status.md`。针对性的纯 Kotlin 回归检查说明见 [回归检查](docs/regression-checks.md)，90% 目标的样本范围和计量方式见 [兼容性验收口径](docs/compatibility-assessment.md)。本地模拟 API 只校验请求和固定回复流程，不能代表真实模型质量或全部 PiuPiu 资料兼容。
