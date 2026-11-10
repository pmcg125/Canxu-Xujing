# 视觉输入（聊天附图发给模型）设计

日期：2026-11-08。项目：残叙·残念（Xujing）。对照版本：PiuPiu Android 2.7.9。

状态：**已实现，未做真机验收**。2026-11-08 完成源码修改；`:app:assembleDebug` 编译与打包通过，未运行自动化测试，未做真实多模态模型与真机验收。原版结论来自只读核对 PiuPiu 已恢复前端源码。

本文承接 [下一阶段开发目标](development-goals-2026-11-06.md) 第 8 节暂缓项「图片上传/视觉输入……后续按实际需求独立排期」。用户已明确提出该需求，故在此独立排期设计。范围限定为**视觉输入**（把用户图片作为多模态内容发给模型），不含生图（模型画图，README 中另行延期）。

## 1. 目标与边界

- 在聊天输入区附加图片，发送时作为 OpenAI 兼容多模态内容（`image_url` / base64）随该条用户消息进入实际请求。
- 图片在聊天消息中可见、可移除；随会话保存、重启恢复；导入导出保留。
- 只把**用户消息**的图片送入上下文；角色/生成图片默认只展示不回传（与原版一致，见第 2 节）。
- 不做图床、不自建云、不引入账号/VIP/固定凭据。远程 URL 仅在其本身可被模型端点访问时直传。

## 2. 原版核对结论（PiuPiu 2.7.9）

证据路径相对 `D:\Canxu-project\PiuPiu-source\frontend-readable\assets`，均已只读核对：

| 行为 | 证据 | 结论 |
| --- | --- | --- |
| 上传入口与门槛 | `c/ChatView-legacy-C8UPOooP.js:1975`、`2102-2115` | 有「上传图片」按钮，`accept="image/*"`；提示「图片上传仅限专属通道使用」，即 VIP/专属通道功能 |
| 上传压缩 | `ChatView-legacy-C8UPOooP.js:2045-2066` | FileReader→Image→canvas，最长边压到 ≤2048px 后重编码 |
| 是否附图的判定 | `ChatView-legacy-C8UPOooP.js:4934-4941` | `shouldAttachImageToContext`：`role==="user"` 且 `image.trim()` 非空才附图 |
| 图片源解析 | `ChatView-legacy-C8UPOooP.js:4824+` | `remoteImageSource`：`data:` 直传；`blob:`/`capacitor://`/localhost 取回转 base64；远程 http(s) 直传 |
| 多模态请求体 | `ChatView-legacy-C8UPOooP.js:6052-6066` | 有图：`content:[{type:"text",text},{type:"image_url",image_url:{url}}]`；无图：`content` 为纯字符串 |
| 消息字段 | `c/GroupMemberSelector-legacy-BIJ8h7lR.js:25238` | 用户消息用单个 `image` 字符串字段（base64 / 文件引用 / 远程 URL） |
| 入库外置 | `c/app-security-legacy-BdXLf8ML.js:10363`（`piupiu/media`） | base64 外置为 `piupiu/media/…` 文件引用存入 message_json，加载后再解析 |
| 聊天 JSON 导出 | `ChatView-legacy-C8UPOooP.js:27809-27831` | 保留 `image` 字段（新消息为 base64，从库加载后为文件引用） |
| 视觉能力标记 | 未找到 supportsVision 类开关 | 原版无条件附图，不判断模型是否支持视觉 |
| 字段双语义 | `ChatView-legacy-C8UPOooP.js:25041`（生图写入同字段） | 生成图也写进同一 `image` 字段；附件设计需区分「上传输入」与「生成输出」 |

结论：**属「对齐原版」而非原创扩展**——PiuPiu 确实支持聊天视觉输入，且线格式与本文第 4 节设计一致。差异点：原版有 VIP 门槛、无视觉能力开关、用单 `image` 字段；Xujing 的产品取舍见第 8 节。

## 3. 数据模型（core，保持无 Android 依赖）

- [Models.kt](../core/src/main/kotlin/app/xujing/core/Models.kt) `ChatMessage`：新增 `image: String = ""` 承载原版单图字段（base64 / 本地媒体引用 / 远程 URL），与 PiuPiu `image` 一一映射；导入导出的未知嵌套仍走 `extra`/`raw` 保留。首版按**单图**对齐原版；多图作为后续超集，字段设计预留不破坏往返。
- `PromptMessage`：附加 `images: List<String> = emptyList()`（默认空，不动现有 `content:String`）。空列表时请求仍是纯字符串 content，保证非视觉端点与旧行为不变。
- 图片字节/base64 解析只在 app 层做；core 只携带字符串引用，不碰 Android、不做 IO。
- 附件「来源」标记（上传输入 vs 生成输出）存入 `extra`，避免把生图结果当用户输入回传；首版生图未实现，仅预留语义。

## 4. 请求管线（data/ApiClient）

- [ApiClient.kt:61](../app/src/main/java/app/xujing/data/ApiClient.kt) `complete()`：`message.images` 非空时，`content` 构造为 `[{type:"text",text:content},{type:"image_url",image_url:{url:<data或远程URL>}}]`；为空时维持现有纯字符串。与原版 6052-6066 完全对齐。
- 图片引用→可发送 URL 的解析（本地媒体文件读字节→base64 data URL；远程 URL 直传）在 app 层完成后再交给 ApiClient，对应原版 `remoteImageSource`。
- [PromptEngine.kt](../core/src/main/kotlin/app/xujing/core/engine/PromptEngine.kt) `build()`：仅把**当前用户消息**的 `image` 透传进对应 `PromptMessage.images`；历史中的用户图片是否随历史回传，按上下文预算与原版 `shouldAttachImageToContext` 语义处理（首版建议只带最新一条用户图，历史图默认不回传，记为可配置项）。
- 摘要模式（summary）不带图片。

## 5. 存储与媒体

- 复用背景图/头像的私有媒体目录方案（[Chat.kt:84](../app/src/main/java/app/xujing/ui/Chat.kt) 的 `filesDir`）：上传即压缩落盘为媒体文件，`ChatMessage.image` 存相对引用而非内联 base64，避免加密库记录膨胀（与原版 `piupiu/media` 外置一致）。
- 压缩对齐原版：最长边 ≤2048px、JPEG 重编码；入口大小/像素上限参考背景图（≤16MiB、≤4000 万像素）后再降采样。
- 加密口径与现有媒体一致：引用进加密库（[AppStore.kt](../app/src/main/java/app/xujing/data/AppStore.kt)），图片文件本身不额外加密。文档须明确「数据库加密 ≠ 所有文件已加密」，不夸大。
- 删除消息/会话时清理对应媒体文件，避免孤儿文件（与 `deleteVectors` 类似的清理路径）。

## 6. UI 设计（并入 composer 重做）

- 输入区（[Chat.kt:229](../app/src/main/java/app/xujing/ui/Chat.kt)）新增「附图」按钮 → 系统选图（`ActivityResultContracts` 图片 MIME，复用 [MainActivity.kt](../app/src/main/java/app/xujing/MainActivity.kt) 的导入回调模式）。
- 已选图在输入框上方显示缩略图 + 移除（×）；发送时写入该条用户消息 `image`。
- 消息渲染：用户气泡/阅读模式展示附件图，点开看大图；生成/忙碌时可禁用附图与发送。
- 连接设置新增「支持视觉输入」开关（见第 8 节产品取舍）。
- 与本次全局布局/composer 重做合并交付：附图按钮是 composer 的一部分，抽屉/双栏不影响该入口。

## 7. 导入导出与兼容

- 聊天 JSON：保留 `image` 字段往返。导入时 base64 落盘为媒体引用、文件引用按原样保留或按备份包内媒体解析；导出时按用户选择内联 base64 或随包附带媒体（大图片走 [M4 流式导出](development-goals-2026-11-06.md) 避免常驻内存）。
- [CompatibilityCodec.kt](../core/src/main/kotlin/app/xujing/core/compat/CompatibilityCodec.kt)：`image` 字段双向映射；未知嵌套字段保留。
- 备份不含 Keystore 密钥/API 密钥；媒体是否随导出由用户决定，鉴权头移除规则不变。
- 文件兼容与执行兼容分别记：能导入带 `image` 的聊天 ≠ 已验证附图实际进入模型请求，后者需模拟 API 核对实收 content。

## 8. 与原版的产品取舍（需用户拍板）

1. **VIP 门槛不照搬**：Xujing 无账号/VIP，视觉输入对用户自配连接直接开放。
2. **非视觉模型处理**：原版无条件附图；Xujing 连接任意 OpenAI 兼容端点，许多模型不支持视觉。建议每连接加「支持视觉输入」开关，关闭时附图给明确提示而非静默丢图或盲发。这是相对原版的**安全增强差异**，需记录。
3. **单图 vs 多图**：首版单图对齐原版 `image` 字段；多图作为后续超集再排期。
4. **历史图回传策略**：首版只带最新用户图，历史图默认不回传（省 token、降泄漏面），作为可配置项保留。

## 9. 已知限制与暂缓

- `estimatedTokens` 只算文本，图片 token 不计入上下文预算——首版记为已知限制，后续按模型计费口径补估算。
- 不做视觉能力自动探测（不可靠），用显式开关。
- 生图（模型画图）是独立延期功能；本文只预留 `image` 字段双语义，不实现生图。
- 远程 URL 直传要求该 URL 可被模型端点访问；不代为上传到任何图床。

## 10. 分层改动清单（附加式，待实施）

| 层 | 文件 | 改动 |
| --- | --- | --- |
| core | `Models.kt` | `ChatMessage.image`、`PromptMessage.images`（均附加默认值） |
| core | `engine/PromptEngine.kt` | 仅最新用户消息 `image` 透传；summary 不带图 |
| core | `compat/CompatibilityCodec.kt` | `image` 字段双向映射、未知字段保留 |
| data | `ApiClient.kt` | 多模态 content 数组；无图维持纯字符串 |
| data | `AppController.kt` / `AppStore.kt` | 选图落盘、引用解析为 base64、媒体清理 |
| ui | `Chat.kt` / `UiContract.kt` / 连接设置 | 附图按钮、缩略图/移除、消息渲染、视觉开关 |
| 宿主 | `MainActivity.kt` | 图片选择 ActivityResult 回调 |

## 11. 验收口径（沿用项目纪律）

- 状态区分：待实施 / 已实现未验收 / PASS / FAIL / BLOCKED / NOT TESTED；本文当前为**待实施**。
- 模拟 API 核对：附图用户消息的实收 `content` 为 `[{type:text},{type:image_url,…}]`，无图消息仍为纯字符串；非视觉开关关闭时的提示分支。
- 端到端：选图→压缩落盘→发送→气泡渲染→保存/重启恢复→聊天 JSON 导出再导入→`image` 字段与媒体一致。
- 按 [AGENTS.md](../AGENTS.md)：未经要求不加/跑测试、不装机启动；允许编译打包。运行验收由后续测试任务按 [environment.md](environment.md) 执行。

## 12. 本轮交付核对

- 已只读核对 PiuPiu 2.7.9 前端源码，给出视觉输入「支持」的结论与逐条证据位置。
- 已输出分层设计、兼容契约、产品取舍与验收口径；未修改任何产品源码、未构建、未运行测试。
- 第 8 节四项产品取舍待用户拍板后，再进入实现或细化。