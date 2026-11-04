# Xujing 原生宿主能力盘点（对照 PiuPiu 2.7.9 Android 原生侧）

日期：2026-11-04。范围：**只读静态对照** PiuPiu 2.7.9 反编译出的 9 个自有类与 Xujing `app` 模块原生代码，结论全部来自源码阅读，未构建、未运行、未上机验证。JADX 反编译存在变量名丢失与块重排，凡语义依赖反编译控制流的地方单独标注「未验证」。

路径缩写：

- `PP/` = `D:\Canxu-project\PiuPiu-source\jadx-export\sources\io\piupiu\chat\`
- `PPmanifest` = `D:\Canxu-project\PiuPiu-source\apktool\AndroidManifest.xml`
- `PPcfg` = `D:\Canxu-project\PiuPiu-source\apktool\assets\capacitor.config.json`
- `XJ/` = `D:\Canxu-project\Xujing\app\src\main\java\app\xujing\`
- `XJmanifest` = `D:\Canxu-project\Xujing\app\src\main\AndroidManifest.xml`

对照口径：PiuPiu 的 `AndroidDownloader` 是**面向整个前端应用**的原生能力面（Capacitor WebView 加载的正是加密前端包）；Xujing 的原生能力面分成两半——**Compose 宿主功能**（导入/导出/API/持久化，由 Kotlin 直接调用）和**卡片/脚本 WebView 桥**（`XujingBridge`，由 JS 调用）。两者形态不同，因此逐项按「能力」而不是「方法名」对齐。

本文件不覆盖 PiuPiu 前端 JS 层的卡片接口差异（`TavernHelper`、事件顺序、变量作用域等），那部分已在 `docs/piupiu-detail-gap-audit.md` 记录。

---

## 1. 能力对照总表

状态取值：`已实现` / `部分实现` / `未实现` / `刻意不做` / `不适用`。

| # | 能力 | PiuPiu 证据（file:line） | Xujing 现状（file:line 或「无」） | 状态 | 说明 |
| --- | --- | --- | --- | --- | --- |
| 1 | WebView 注入宿主接口（下载 / 文件 / 通知 / 设备） | `PP/MainActivity.java:259` 注入 `AndroidDownloader`；方法体 721–1337 | `XJ/runtime/CardMessage.kt:107-139`（`XujingBridge`）；`XJ/runtime/SessionScriptRuntime.kt:121-155` | 已实现 | 接口形态不同：PiuPiu 一套接口服务整个前端；Xujing 每条消息一个 WebView、每个会话一个脚本 WebView，各自持有一个桥。Xujing 桥只暴露 `height/request/eventComplete/ready` 与会话脚本的 `buttons/initialized/complete`，能力边界更窄（业务能力走 `cardRequest`）。 |
| 2 | 桥调用的调用方鉴别（token） | 无。`AndroidDownloader` 全部方法无 token 校验（721–1337） | `XJ/runtime/CardMessage.kt:113`（token 不等于本视图 `endpointToken`，或不在 `trustedTokens` 中即拒绝）；`XJ/runtime/SessionScriptRuntime.kt:126` | 已实现 | Xujing 明确强于 PiuPiu：任何在 PiuPiu 前端 WebView 里执行的脚本都能直接调 `installApk`/`downloadFileToDownloads`。 |
| 3 | 原生桥版本协商 | `PP/MainActivity.java:783-786` `getNativeBridgeVersion` | 无 | 不适用 | 单 APK 单进程，宿主与前端同版本发布，不存在需要协商的独立前端包。 |
| 4 | 结构化诊断 / 错误上报 | `PP/MainActivity.java:821-846`（`reportProtectedFrontendError/Event/Ready`，事件码白名单化后写日志） | `XJ/runtime/SessionScriptRuntime.kt:147` 仅脚本加载失败落一条 `Log.w`；其余错误走 `runtime.error` 显示给用户（`XJ/AppController.kt:75`） | 部分实现 | 缺：卡片 HTML 渲染失败、脚本事件超时、桥返回异常的结构化事件码与统一日志通道（参见 detail-gap 审计里的事件层问题）。属可观测性缺口，不影响功能正确性。 |
| 5 | 向前端暴露设备标识 | `PP/MainActivity.java:815-819` `getProtectedFrontendDeviceId`（`Settings.Secure.ANDROID_ID`） | 无 | 刻意不做 | 该接口唯一用途是授权/证明链的 `device_id`（`BusinessRequestSigner.java:82`、`FrontendLicenseManager` 的 installation_id 体系）。Xujing 无服务端身份，暴露 `ANDROID_ID` 只增加指纹面。 |
| 6 | 数据导入（PNG 卡 / 聊天 / 设置 / 备份 / 模型包） | 由前端 JS + Capacitor 文件插件完成（原生 9 类内无对应方法） | `XJ/MainActivity.kt:20,32`（`OpenDocument`）；`XJ/AppController.kt:496-534` | 已实现 | SAF 用户选择文件，带大小与像素上限（`AppController.kt:505` 16 MiB 背景、`:509` 4000 万像素、`:524` 256 MiB 导入）。 |
| 7 | 数据导出（聊天 / 设置 / 备份 / 角色卡 PNG） | 部分能力由 `downloadFileToDownloads`（854–873）与 `prepareBinaryDownload`+分块（1154–1332）承担 | `XJ/MainActivity.kt:21,33`（`CreateDocument`）；`XJ/AppController.kt:547-561` | 已实现 | 目标位置交由用户选择，不写死公共 `Downloads`。角色卡 PNG 在 `AppController.kt:552` 生成、`avatarPng` 555–570 转码。 |
| 8 | WebView 内链接下载（默认落到公共下载目录） | `PP/MainActivity.java:554-559` `setDownloadListener`；`599-674` 用 `DownloadManager` 写 `DIRECTORY_DOWNLOADS`，带 Cookie/UA 透传 | 无 | 未实现 | 卡片里的 `<a download>`、外链附件在 Xujing 里点击后不会有任何反馈（主框架导航被拦截，见 #21）。这是**卡片可感知的功能缺失**。 |
| 9 | 卡片 / 脚本主动保存文件到下载目录 | `PP/MainActivity.java:854-873` `downloadFileToDownloads`（JS 传 URL + 文件名 + MIME） | 无 | 未实现 | Xujing 的桥没有「保存到文件」入口；卡片脚本只能把内容塞回会话或走外部 URL。 |
| 10 | 保存 PNG 到相册（MediaStore） | `PP/MainActivity.java:962-1031` `savePngToGallery`（Base64 → `MediaStore.Images`，`Pictures/PiuPiuChat`） | 无 | 未实现 | Xujing 只做「导出到用户选择位置」。当前无生图，价值低；若后续接生图/TTS 之外的多媒体导出，需要补 MediaStore 分支。 |
| 11 | 大文件分块流式写入 | `PP/MainActivity.java:919-939`（`appendUpdateChunk` 逐块 base64 追加）；`1154-1332`（`prepareBinaryDownload`/`appendBinaryDownloadChunk`/`finishBinaryDownload` 分块写备份） | `XJ/AppController.kt:555`（备份 zip 全部 media 读入内存）、`:561`（`exportBytes` 一次性写流） | 部分实现 | **真缺口**：备份导出把整包（含全部 media）驻留为一个 `ByteArray`，大资料可能 OOM，且无进度、无中断续写。PiuPiu 用分块正是为了绕开这一点。 |
| 12 | 分块下载的取消与半成品清理 | `PP/MainActivity.java:1286-1332` `cancelBinaryDownload`（关流、删 MediaStore 条目或临时文件） | 无 | 不适用 | Xujing 不产生半成品文件（写失败时只丢弃内存字节），暂无对应语义需要。补 #11 时需一并补。 |
| 13 | APK 自更新（URL 下载 → 分块 → 安装） | `PP/MainActivity.java:848-852,895-960`（`startDownload`/`prepareUpdate*`/`appendUpdateChunk`/`finishAndInstallUpdate`）；`1450-1536`（`checkAndDownload`/`downloadAndInstall`）；`1570-1576`（`FileProvider` + `ACTION_VIEW` 安装）；`PPmanifest:4` `REQUEST_INSTALL_PACKAGES`、`:23` FileProvider | 无 | 未实现 | 依赖「官方分发通道 + 未知来源安装授权」。Xujing 无更新服务端，当前按手动安装分发；是否要做属于产品决策，不属于兼容性缺口。 |
| 14 | 数据库用量与可用空间 | `PP/MainActivity.java:721-734` `getDatabaseStorageInfo`（`StatFs` 可用字节 + `piupiuSQLite.db` 主库/wal/shm 大小） | 无 | 未实现 | 影响：备份/导入前无法提示「空间不足」，设置页也没有存储占用展示。低风险、低工作量。 |
| 15 | 回复完成通知 + 通知渠道 | `PP/MainActivity.java:1334-1337` `showChatReplyNotification`；`1360-1389` 前台跳过 + 通知构建；`1391-1404` 渠道 | 无 | 未实现 | PiuPiu 在应用不在前台时提示「已回复」，并复用前台服务通知位（`1368`）。Xujing 切后台后回复完成无任何提醒。 |
| 16 | 通知权限申请 | `PP/MainActivity.java:216-221`、`:541`（API 33+ 请求 `POST_NOTIFICATIONS`） | `XJmanifest:3` 只有 `INTERNET` | 未实现 | 与 #15 绑定；补通知时必须同时补权限与拒绝后的降级路径。 |
| 17 | 前台保活服务（`dataSync`） | `PP/KeepAliveService.java:16-165`（`startForeground` 38–79、`start/stop/showReplyCompleted` 116–156）；`PP/MainActivity.java:190-214`（`onStart` 停、`onStop` 起）；`PPmanifest:7-11,26` | 无 | 未实现 | **真缺口（中风险）**：Xujing 的生成跑在 `viewModelScope`（`AppController.kt:251`），应用退到后台后进程可被回收，长回复会中断且用户无提示。PiuPiu 用前台服务把进程钉住。Xujing 无后台任务/无定时任务，所以只需在「生成中」这一种状态下保活，成本可控。 |
| 18 | 关闭 WebView 内容调试 | `PP/MainActivity.java:251` `WebView.setWebContentsDebuggingEnabled(false)` | 无 | 未实现 | **唯一明确的安全硬化缺口**：debug 版（`android:debuggable=true`，debug 构建默认）可被 `chrome://inspect` 附加到卡片 WebView，读到 `window.__XUJING_CONTEXT__` 里的全部会话正文与变量。release 包不受影响。 |
| 19 | 禁止 file:// 与 content:// 访问 | 未在 9 类内显式设置，依赖 Capacitor 默认 | `XJ/runtime/CardMessage.kt:103`；`XJ/runtime/SessionScriptRuntime.kt:120` | 已实现 | 两处都显式 `allowFileAccess=false` / `allowContentAccess=false`。 |
| 20 | 混合内容策略 | 未在 9 类内显式设置（`PPcfg` `cleartext:true` 只影响 Capacitor 本地服务） | `XJ/runtime/CardMessage.kt:106`；`XJ/runtime/SessionScriptRuntime.kt:120` | 已实现 | `MIXED_CONTENT_NEVER_ALLOW`，页面基址是 `https://cards.xujing.invalid/`（`CardMessage.kt:211`、`SessionScriptRuntime.kt:208`），http 子资源会被拦。 |
| 21 | 主框架导航拦截 | 9 类内无 `shouldOverrideUrlLoading` 重写，依赖 `BridgeWebViewClient`（`PP/ProtectedBridgeWebViewClient.java:15` 继承它，只重写了 `shouldInterceptRequest`） | `XJ/runtime/CardMessage.kt:141`（主框架一律取消）；`XJ/runtime/SessionScriptRuntime.kt:157-158`（主框架或非 `about:` 一律取消） | 已实现 | Xujing 更严格：PiuPiu 依赖 Capacitor 的「外链交给系统浏览器」默认行为，Xujing 直接吞掉主框架导航。代价是 #8 的下载/新窗口能力一并没了。 |
| 22 | 子资源白名单 / 虚拟 origin | `PP/ProtectedBridgeWebViewClient.java:27-45`（仅本地 origin 且 GET 才解密，`no-store`）；`PP/ProtectedOriginMatcher.java:8-20`（scheme/host/effective-port 全等） | `XJ/runtime/CardMessage.kt:142-171`；`XJ/runtime/SessionScriptRuntime.kt:159-172` | 已实现 | 等价机制：Xujing 用固定虚拟主机 `cards.xujing.invalid` + 精确路径白名单从 assets 取文件，未命中回落 `super`（即真实网络）。PiuPiu 用 HMAC/SHA-256 索引键查条目（`ProtectedFrontendManager.java:562-574`），因此两边都不存在目录穿越面。差别：PiuPiu 白名单外是「回到 Capacitor 本地服务」，Xujing 白名单外是「放行真实网络」。 |
| 23 | 加密前端包加载（AEAD / HKDF / 整包哈希） | `PP/ProtectedFrontendManager.java:74-172`（manifest 解析、format 2/3）、`198`（unlock）、`301-380`（分段解密）、`475`（`verifyPakHash`） | 无 | 刻意不做 | 该机制的收益是保护「自家前端包不被抄/被改」并绑定授权；Xujing 的前端就是本仓库源码与 assets，无保护对象。 |
| 24 | 授权 / 设备证明链 | `PP/FrontendLicenseManager.java:44-71`（KeyStore 别名、challenge/issue 端点、许可证缓存）、`139-147`（构造与 `start`）；`PP/MainActivity.java:264-360`（完整性闸门、账号模式） | 无 | 刻意不做 | 依赖 `www.piupiuchan.top` 专有端点与账户体系。 |
| 25 | 业务请求签名 | `PP/BusinessRequestSigner.java:24`（`business_proof.php`）、`36-89`（origin 校验、时间偏移、KeyStore 私钥签名、会话迁移） | 无 | 刻意不做 | 与 #24 同一套专有信任根，Xujing 不应复刻。 |
| 26 | 应用签名自检 / 上报 | `PP/MainActivity.java:773-781` `getAppSignatureInfo`；`1415-1431` 读 APK 签名 SHA-256 | 无 | 刻意不做 | 目的是让服务端判断「是否官方包」。Xujing 无服务端校验方，也没有防篡改诉求。 |
| 27 | 本地数据加密与备份开关 | `PPcfg` `androidIsEncryption:false`（SQLite 明文）；`PPmanifest:16` `allowBackup="true"` | `XJ/data/AppStore.kt:18-42`（AndroidKeyStore AES-GCM `SecretVault`）、`45-93`（逐条记录加密落库）；`XJmanifest:4` `allowBackup="false"` | 已实现（更强） | Xujing 每条记录单独加密，API Key 单独进 `connection-secrets`；备份关闭。PiuPiu 两项都更弱。 |
| 28 | 明文流量策略 | `PPcfg` `server.cleartext:true`；`PPmanifest:16` `usesCleartextTraffic="true"` | `XJmanifest:4` `usesCleartextTraffic="true"`；`XJ/data/ApiClient.kt:26` 允许用户填 `http://` | 已实现（等价） | 两边都为了本地服务/用户自定义端点放开明文。Xujing 的卡片 WebView 另有 `MIXED_CONTENT_NEVER_ALLOW`，明文只影响 OkHttp 侧。 |

**计数**：已实现 10 项（#1,2,6,7,19,20,21,22,27,28）；部分实现 2 项（#4,11）；未实现 9 项（#8,9,10,13,14,15,16,17,18）；刻意不做 5 项（#5,23,24,25,26）；不适用 2 项（#3,12）。

**未验证标注**：PiuPiu 侧 #12 的 `cancelBinaryDownload`（`MainActivity.java:1286-1332`）反编译出多段重复块和悬空 `throw th`（`:1331`），清理顺序（关流 → 删 MediaStore 条目 → 删临时文件）只能按可见调用推断；#13 的 `downloadAndInstall` 进度回调在 UI 线程调用 `window.updateDownloadProgress`（`:1545`），前端是否真有该钩子未在本次范围内核对。表内其余结论只依赖方法签名、常量与显式条件判断，语义与行号一致。

### 1.1 附：Xujing 桥与卡片接口清单（现状基线）

`AppController.cardRequest`（`XJ/AppController.kt:573-630`）是卡片/会话脚本共用的分发入口，进入前有两道门：`allowCardScripts` 全局开关（`:575`）+ `CardRuntimeHost.isTrusted` token 校验（`:66`）。会话脚本另受 `sessionScriptsEnabled`（`SessionScriptRuntime.kt:71`）约束。

| 方法 | 位置 | 状态 |
| --- | --- | --- |
| `broadcastRuntimeEvent` | `AppController.kt:579-584` | 已实现（带 `excludeToken`，不回送发起方） |
| `getContext` | `:585` | 已实现（chat / variables / name1 / name2） |
| `getVariables` | `:586` | 已实现（作用域仅 chat/message，缺口见 detail-gap #16） |
| `replaceVariables` / `setVariables` | `:587`（`commitCardLibrary` 85-117） | 已实现（compare-and-set + 落盘后才回成功） |
| `parseMvuMessage` | `:588-599` | 已实现 |
| `reloadMvuInitVar` | `:600`（`commitMvuReload` 119-138） | 已实现 |
| `getChatMessages` | `:601` | 已实现 |
| `loadWorldInfo` / `getWorldbook` | `:602-603` | 已实现 |
| `loadPreset` | `:604-608` | 已实现 |
| `saveWorldInfo` / `createWorldbook` / `deleteWorldbook` / `rebindCharWorldbooks` / `selectPreset` | `:609` | 已实现 |
| `setChatMessages` | `:610-617` | 已实现（生成中拒绝） |
| `send` / `sendMessage` | `:618` | 已实现（要求会话已打开且不在生成中） |
| `generate` | `:619-627` | 部分实现（一次返回全文，无流式增量与按任务取消，见 detail-gap #19） |
| 其它 | `:628` | 返回 `尚未实现的角色卡接口：<method>` |

桥内兜底分支（宿主 handler 未就绪时）只支持 `getVariables/getContext/replaceVariables/send`：`CardMessage.kt:119-128`。

---

## 2. 真缺口清单（按风险/价值排序）

1. **备份导出无分块、整包驻留内存**（#11）
   影响：`exportBackup` 把所有 media 读进 `Map<String, ByteArray>`（`AppController.kt:555`），再整体塞进 `exportBytes`，由 `writeExport` 一次写流（`:561`）。资料量大（大量背景图/角色卡）时可能 OOM，失败后没有任何进度与断点，用户只会看到「操作失败」。这是当前数据安全链路上最实的一条缺口——备份是用户唯一的迁移/自救手段。

2. **无前台保活，后台生成会被回收**（#17）
   影响：生成协程挂在 `viewModelScope`（`AppController.kt:251`），没有前台服务锚定进程。切到后台后系统回收进程，长回复直接中断；`finally` 里虽然会 `persist()`（`:365-376`），但进程被杀时来不及。用户观感是「切出去再回来，回复没了/半截」。

3. **回复完成无通知**（#15 + #16）
   影响：即使生成顺利结束，用户不在前台时也收不到任何提示。与 #17 是同一个用户场景的两面，建议一起补（前台服务顺带承载通知，正是 PiuPiu 的做法：`KeepAliveService.showReplyCompleted`）。
   附带：manifest 缺 `POST_NOTIFICATIONS` 与 `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_DATA_SYNC`。

4. **WebView 内容调试未关闭**（#18）
   影响：debug 包可被 `chrome://inspect` 附加，读到卡片 WebView 内 `window.__XUJING_CONTEXT__`（整段会话正文、变量、worldbook/preset 名）与 `window.__XUJING_BRIDGE` 调用面。仅影响测试包与本地调试环境，release 不受影响；但这是 PiuPiu 明确做了而 Xujing 漏了的一行硬化。

5. **卡片无法触发下载 / 保存文件**（#8 + #9）
   影响：复杂互动卡里常见的「导出记录」「下载素材」按钮会静默失效（主框架导航被 `CardMessage.kt:141` 拦截，且桥没有保存入口）。属于执行兼容性缺口，和 detail-gap 里的事件/变量问题同级，但修复需要新增一个受 token 保护的写文件接口 + `DownloadListener` 兜底，涉及权限与落盘路径设计。

6. **APK 自更新缺失**（#13）
   影响：需要先有分发通道才成立。当前手动安装即可，不作为兼容性缺口；若将来提供官方下载页并希望「应用内升级」，需补 `REQUEST_INSTALL_PACKAGES`、FileProvider、分块写入与未知来源授权（`PP/MainActivity.java:1459-1463` 的处理可作为参考流程，但不要照搬签名/授权部分）。

7. **数据库用量与可用空间不可见**（#14）
   影响：备份/导入前无法预警空间不足；设置页没有占用展示。低风险，工作量小，可作为 #1 的配套（先查空间再决定是否分块）。

8. **保存 PNG 到相册缺失**（#10）
   影响：目前只能「导出到用户选择位置」，分享路径多一步。当前无生图，价值低；等 TTS/生图或聊天长图导出立项时再补。

9. **诊断事件缺结构化通道**（#4）
   影响：卡片渲染失败、脚本事件超时在用户侧只表现为「回复里那块空白/变量没变」，日志里也没有可对齐的事件码。排查成本高，但不改变功能正确性。

---

## 3. 刻意不做清单

依据优先级：`AGENTS.md` > `docs/requirements.md` > 机制本身的专有性。

| 项 | 依据 |
| --- | --- |
| 授权 / 设备证明链（对应 #24） | `AGENTS.md`：「No account, VIP, invitation, community, market, Live2D, bundled weights, or proprietary service credentials.」`docs/requirements.md:18`：「账号、邀请、VIP、原版许可/签名/固定凭据、社区/市场/在线聊天室、Live2D。TTS 与生图延期。」`FrontendLicenseManager` 的 challenge/issue 端点在 `www.piupiuchan.top`（`PP/FrontendLicenseManager.java:52,57,69,71`），属专有服务。 |
| 业务请求签名（对应 #25） | 同上。`PP/BusinessRequestSigner.java:24` 硬编码 `business_proof.php`，`:56` 限定 `www.piupiuchan.top`，`:68-84` 用 KeyStore 设备密钥 + 会话迁移。这是原版服务端的信任根，复刻既无对端也无意义。 |
| 加密前端包加载（对应 #23） | 目的是保护原版前端 bundle 并把它绑到许可证（`ProtectedFrontendManager.unlock` 需要 `FrontendLicenseManager` 解出的内容密钥，`PP/MainActivity.java:285-298`）。Xujing 前端即本仓库源码/assets，无保护对象；引入只会增加构建复杂度。 |
| 应用签名自检 / 上报（对应 #26） | 唯一消费者是服务端「是否官方包」判定。`AGENTS.md` 排除专有服务凭据，Xujing 也没有判定方。 |
| 向前端暴露 `ANDROID_ID`（对应 #5） | 该接口在 PiuPiu 只服务于证明链（`PP/MainActivity.java:815-819`，返回值进入 `BusinessRequestSigner` 的 `deviceId`）。`AGENTS.md` 排除账号体系；且暴露稳定设备标识只增加指纹面。 |
| 内置权重 / 模型随包分发 | `AGENTS.md`：「bundled weights」；`docs/requirements.md:11`：「模型权重不随安装包分发」。相关原生能力（模型包导入）已用 SAF 实现（`AppController.kt:517-520`）。 |
| TTS / 生图 | `AGENTS.md`：「TTS and image generation are deferred.」`docs/requirements.md:18` 同。因此 #10（存相册）当前无真实调用方。 |

---

## 4. 安全边界结论

### 4.1 逐条判定

| 边界项 | 判定 | 依据与说明 |
| --- | --- | --- |
| 关闭 WebView 内容调试 | **缺失** | PiuPiu `PP/MainActivity.java:251` 在 `onCreate` 第一行就关；Xujing 全仓库无 `setWebContentsDebuggingEnabled` 调用（grep 无命中）。release 包因 `debuggable=false` 不受影响，debug/测试包可被 `chrome://inspect` 附加。 |
| file:// / content:// 访问 | **已覆盖** | `CardMessage.kt:103`、`SessionScriptRuntime.kt:120` 显式关闭两项。PiuPiu 的 9 类里没有显式设置（依赖 Capacitor 默认），这一点 Xujing 更明确。 |
| 混合内容 | **已覆盖** | `CardMessage.kt:106`、`SessionScriptRuntime.kt:120` 均为 `MIXED_CONTENT_NEVER_ALLOW`；页面基址固定 https 虚拟主机。注意：这不覆盖 OkHttp 侧的用户自定义 `http://` 端点（`ApiClient.kt:26` 有意允许，见下条）。 |
| 主框架导航 | **已覆盖（更严格）** | `CardMessage.kt:141` 取消全部主框架导航；`SessionScriptRuntime.kt:157-158` 取消主框架与所有非 `about:` scheme。PiuPiu 在 9 类内没有重写，靠 Capacitor 的「外链交给浏览器」。代价是卡片里的下载/新窗口一并失效（真缺口 #8）。 |
| 子帧导航 | **不同但等价** | 消息 WebView 对子帧放行（`isForMainFrame=false` 时返回 false），这是卡片内 iframe 与 HTML 文档模式所必需；PiuPiu 前端同样是多 iframe 架构。风险面相同：子帧可加载任意外部 https 资源。 |
| 子资源 origin 限制 | **不同但等价** | PiuPiu 用 `ProtectedOriginMatcher` 做 scheme/host/effective-port 全等判定（`PP/ProtectedOriginMatcher.java:8-20`），只对本地 origin 走解密分支，其余回落 Capacitor。Xujing 用固定虚拟 host + 精确路径白名单（`CardMessage.kt:142-171`、`SessionScriptRuntime.kt:159-172`），未命中回落真实网络。两边都没有目录穿越面（PiuPiu 按 HMAC/SHA-256 索引查条目，`ProtectedFrontendManager.java:562-574`；Xujing 是字面量 `when(path)`）。 |
| JS 接口的调用方鉴别 | **已覆盖（强于 PiuPiu）** | `CardMessage.kt:113` 同时校验「本 WebView 的 `endpointToken`」与「`CardRuntimeHost.trustedTokens[sessionId]`」；会话脚本在 `SessionScriptRuntime.kt:126,145` 校验。PiuPiu 的 `AndroidDownloader` 无任何 token，任何在 WebView 内执行的代码都能调 `installApk`。**遗留小口子**：`height`（`CardMessage.kt:108`）、`ready`（`:138`）、`eventComplete`（`:131`）未做 token 校验——影响限于同一 WebView 内的自身状态（`eventPending` 是每 WebView 独立 map），危害低。 |
| 卡片的网络外发面 | **不同但等价（固有风险）** | 桥注入的快照含整段会话（`CardMessage.kt:84-95`），卡片脚本可 `fetch` 任意 https 主机外发；Xujing 没有 CSP 或域名白名单。PiuPiu 同样把 `getContext` 交给卡片脚本，且其前端 WebView 还能调无鉴权的原生接口，因此这不是 Xujing 新增的弱点，是「卡片即任意代码」的固有属性。收紧（CSP/白名单）会破坏依赖 CDN 的现有卡片，不建议在当前阶段做。 |
| 本地数据静态保护 | **已覆盖（强于 PiuPiu）** | `SecretVault` 用 AndroidKeyStore AES-GCM 逐条加密（`AppStore.kt:18-42`），`allowBackup=false`（`XJmanifest:4`）。PiuPiu 侧 `androidIsEncryption:false`（`PPcfg`）且 `allowBackup=true`（`PPmanifest:16`）。 |
| 明文流量 | **不同但等价** | 两边都开 `usesCleartextTraffic`（`PPmanifest:16` / `XJmanifest:4`）。Xujing 的明文只作用于用户自填 `http://` 的 OpenAI 兼容端点，卡片 WebView 由 mixed-content 策略挡住 http 子资源。若要收紧，正确做法是 per-domain `networkSecurityConfig`，而不是全局关掉。 |
| 进程/后台暴露 | **缺失** | 无前台服务，生成期间进程优先级与普通后台应用相同；也没有通知渠道可被滥用。属可用性风险而非安全漏洞。 |

### 4.2 最重要的三条

1. **注入面与鉴权：Xujing 明显强于 PiuPiu。** PiuPiu 的原生桥是一整片无鉴权的高危面（`installApk`、写下载目录、读应用签名），任何注入到 Capacitor WebView 的脚本都能用；Xujing 把它收窄成 per-message endpoint token + per-session 信任集合，且 `generate`/`send`/`setChatMessages` 还有生成互斥与「会话必须已打开」的前置条件。
2. **唯一确定的安全硬化缺口是「没关 WebView 内容调试」。** `setWebContentsDebuggingEnabled(false)` 在 PiuPiu 是启动第一步（`PP/MainActivity.java:251`），Xujing 完全没有；影响 debug/测试包（可被 `chrome://inspect` 读到全部会话正文），release 不受影响。这是投入产出比最高的一条。
3. **导航与源边界：Xujing 更严，但把「下载」这条能力一起关掉了。** 主框架导航一律取消 + 虚拟 origin 白名单是干净的模型，与 PiuPiu 的 origin 全等判定等价；代价是卡片内的下载/新窗口没有替代通路（真缺口 #8/#9），需要在补下载能力时一并设计受 token 保护的写文件入口，而不是放开导航。

### 4.3 明确不建议做的事

- 不要复刻 `FrontendLicenseManager` / `BusinessRequestSigner` / `ProtectedFrontendManager` 的授权与签名链，也不要为了「看起来一致」在 Xujing 侧实现 `getAppSignatureInfo` 或业务证明接口。
- 不要为了对齐 PiuPiu 而打开 WebView 内容调试、放开主框架导航、或把 `allowFileAccess` 打开。
- 不建议在卡片 WebView 上加全量域名白名单/CSP 作为「安全修复」：现有卡片生态依赖 CDN 与内联脚本，盲收紧造成的兼容性回归大于收益。
