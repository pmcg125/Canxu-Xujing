# PiuPiu 2.7.9 产品功能面对照：残叙·残念缺口盘点

日期：2026-11-04。**只读静态对照**：未构建、未运行测试、未连接设备、未发出网络请求。本文所有“实现/未实现”均指源码中是否存在入口与对应数据/引擎路径，不代表已通过运行验证。

- PiuPiu 侧证据路径相对 `D:\Canxu-project\PiuPiu-source\frontend-readable\`；行号为该目录下格式化副本的行号。
- Xujing 侧证据路径相对 `D:\Canxu-project\Xujing\`。
- PiuPiu 前端是压缩后格式化的产物，文件名与内容不对应（例如 `browser-*.js` 是 fflate、`ragService` 99% 是第三方库、`GroupMemberSelector` 内嵌整份 Vue/jQuery/lodash）。下表只引用已确认是业务代码或已核对的组件定义行；第三方库行不当作功能证据。
- 分类口径：`已实现` / `部分实现` / `未实现` / `刻意不做`。`刻意不做` 必须有 `AGENTS.md`、`docs/requirements.md`、`docs/compatibility-assessment.md` 或 `docs/compatibility.md` 的明文依据；没有明文的一律按 `未实现`。

统计：**43 项** —— 已实现 12、部分实现 10、未实现 10、刻意不做 9、不确定 2（表中行）。§4 另列 3 条“范围/口径不确定”的边界说明，不计入分类统计。

---

## 1. 功能对照总表

| # | 功能面 | PiuPiu 证据（file:line） | Xujing 现状（file:line 或“无”） | 状态 | 说明 |
|---|---|---|---|---|---|
| 1 | 角色聊天（单人主流程） | `assets/c/ChatView-legacy-C8UPOooP.js:9480`（ChatView）、`:27003`（buildAsync 调用）、`:27078`（流式空消息）、`:27082`（生成调用） | `app/src/main/java/app/xujing/ui/Chat.kt:53`；`app/src/main/java/app/xujing/AppController.kt:248` | 已实现 | 提示词组装、流式、停止、重生成、续写、编辑均有对应路径；不涉及原版云端代理（见 #28）。 |
| 2 | 群聊（本地多角色会话） | `assets/c/RoleHallView-legacy-OCYgw5tR.js:10718`（CreateGroupChatModal）、`:11203`（GroupEditModal）；`assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js:40851` | `app/src/main/java/app/xujing/ui/XujingApp.kt:192`（多选建会话）；`ui/Chat.kt:143`（成员/轮流发言选择）；`ui/SessionSettings.kt:29`（成员编辑）；`core/.../engine/PromptEngine.kt:53` | 部分实现 | 有多角色会话、逐轮指定发言人、群内多角色资料；但“群”不是独立实体：无群名/群设置/群级世界书与正则绑定，且 `docs/compatibility.md:29` 明示“多角色一轮生成一人”。 |
| 3 | 聊天室（服务端公共房间、禁言/黑名单/成员好感） | `assets/e/index-legacy-B0tYRju5.js:3546`（`/chatroom` 路由）；`assets/c/ChatroomContent-legacy-1HcLb3mr.js:6407`、`:942`（禁言倒计时）、`:971`（黑名单）；`assets/c/ChatroomProfileModal-legacy-qBmzrVdN.js:986` | 无 | 刻意不做 | 依据见 §3“社区/在线聊天室”。 |
| 4 | 消息渲染（Markdown/HTML/卡片脚本） | `assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js:35242`（ChatBubble）、`:33327`（HtmlSandbox）、`:9342`（DOMPurify sanitize） | `ui/Chat.kt:240`（分段渲染）、`:255`（CardMessage）；`ui/MessageContentParser.kt`；`runtime/CardMessage.kt` | 已实现 | 分段规则、流式期间不建 WebView、卡片样式保留见 `docs/compatibility.md:47`。隔离方式不同（WebView 而非 DOMPurify），不按等价处理，但功能面存在。 |
| 5 | 聊天图片上传 / 视觉输入 | `assets/c/ChatView-legacy-C8UPOooP.js:1961`（ChatImageUploader）、`:6055`（`type: "image_url"`） | 无；`core/.../Models.kt:63` 的 `PromptMessage(content: String)` 无多模态结构 | 未实现 | 缺 UI 入口、缺消息/请求数据模型（无 `image_url` 段），发送链路也不接受附件。 |
| 6 | 模型列表拉取与模型选择器 | `assets/c/ChatView-legacy-C8UPOooP.js:2470`（ModelSelector）；`:18541`（请求 `/models`）、`:18536`（去掉 `/chat/completions`） | `ui/Editors.kt:107` 仅手填“模型 ID”；无 `/models` 调用 | 未实现 | 有“填模型名”字段，但没有列表拉取、刷新和选择器；多连接本身已实现（#36）。 |
| 7 | 世界书（引擎 + 编辑） | `assets/c/ChatView-legacy-C8UPOooP.js:14815`（世界书运行时配置）；`assets/e/index-legacy-B0tYRju5.js:21450`（sticky/cooldown 字段）、`:20821`（useGroupScoring） | `core/.../engine/WorldbookEngine.kt:30`；`ui/SessionSettings.kt:68`；`ui/Editors.kt:60`（仅 JSON 编辑） | 部分实现 | 常量/主副键/正则键/递归/预算/概率/互斥组/位置深度/sticky/cooldown 已有（`docs/compatibility.md:30`）；缺结构化条目编辑器、characterFilter/triggers、装饰器阶段、内容哈希失效（`docs/piupiu-detail-gap-audit.md:15,20,21`）。 |
| 8 | 预设（prompt_order / 注入位置 / 采样参数） | `assets/c/ImageViewer-legacy-DD5gJaNv.js:12329`（GlobalPresetSettingsModal）、`:15172`（GlobalPresetListModal） | `core/.../engine/PromptEngine.kt:109`（prompts/prompt_order/marker/注入深度）；`ui/SessionSettings.kt:64` | 部分实现 | 读取与套用已实现，采样参数可选启用；编辑只有原始 JSON，脚本侧预设只能“选/加载”，创建修改删除未接入（`docs/compatibility.md:55`）。 |
| 9 | 正则（全局 + 角色内嵌，多阶段） | `assets/c/ImageViewer-legacy-DD5gJaNv.js:8279`（GlobalRegexSettingsModal）、`:9020`（GlobalRegexListModal）；`assets/c/ChatView-legacy-C8UPOooP.js:3906`（RoleRegexSettingsModal） | `core/.../engine/RegexProcessor.kt:6`（INPUT/OUTPUT/DISPLAY/WORLDINFO）、`:32`（runOnEdit）；`ui/SessionSettings.kt:70` | 部分实现 | 阶段、深度、markdownOnly/promptOnly 已有；无结构化正则编辑器与实时测试；JVM Regex 与 JS 语义差异仍在（`docs/compatibility.md:33`）。 |
| 10 | 人设（多人设资料 + 会话绑定） | `assets/c/ImageViewer-legacy-DD5gJaNv.js:1341`（PersonaListModal） | `ui/Settings.kt:30`（默认人设）；`ui/SessionSettings.kt:38`（会话人设/从资料库选）；`core/.../engine/PromptEngine.kt:47` | 已实现 | 支持默认、会话覆盖、资料库多人设；编辑为 JSON，但没有独立的人设表单（不影响入口存在）。 |
| 11 | 文风 | `assets/c/ImageViewer-legacy-DD5gJaNv.js:3340`（ReplyStyleEditorModal）；`assets/c/ChatView-legacy-C8UPOooP.js:5117` 附近读取 reply 配置 | `ui/Common.kt:121`（style 资料类型）；`ui/SessionSettings.kt:72`；`core/.../engine/PromptEngine.kt:69` | 已实现 | 多文风资料、按会话多选、注入提示词；无专用文风编辑器与预览。 |
| 12 | 全局资料管理入口（世界书/正则/预设集中管理） | `assets/c/SettingsView-legacy-CYxjl7kQ.js:3448`（“全局配置”分区）；`assets/c/ImageViewer-legacy-DD5gJaNv.js:11076`、`:15172` | `ui/XujingApp.kt:53`（资料库 tab）、`:159`（按类型分页）；`ui/Common.kt:121` | 已实现 | PiuPiu 分散在设置分区与多个弹窗；Xujing 合并为“资料库”，功能面等价。 |
| 13 | 角色标签 / 分类筛选 | `assets/c/RoleHallView-legacy-OCYgw5tR.js:903`（MarketCategoryTabs）；`assets/c/app-security-legacy-BdXLf8ML.js:11949`（`role_tags` 表） | 无；`core/.../Models.kt:14`（RoleCard 无 tags）；`ui/XujingApp.kt:118` 仅按名称/描述过滤 | 未实现 | 缺标签数据模型、编辑入口和筛选 UI；导入时标签可能留在 `raw`，但用户不可见、不可用。 |
| 14 | 角色卡脚本 / TavernHelper API | `assets/c/ChatView-legacy-C8UPOooP.js:2879`（TavernHelperScriptStatusModal）、`:9473`（脚本树键）；`assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js:30786`（sandbox 策略）、`:31649`（scriptTreeHash 授权）、`:31721`（requiresHostAccess） | `ui/Settings.kt:60`（全局开关）；`ui/SessionSettings.kt:50`（会话独立脚本）；`runtime/SessionScriptRuntime.kt`；`runtime/CardMessage.kt` | 部分实现 | 有脚本树、逐项开关、iframe、按钮、生成事件、基础 TavernHelper/世界书/预设接口（`docs/compatibility.md:48-61`）；缺 trusted/blocked 授权模型、完整 MVU/Zod 阶段、全部 DOM 生命周期、TypeScript 转译（`docs/feature-progress.md:12`）。 |
| 15 | 回忆页（长期记忆浏览/详情/删除） | `assets/e/index-legacy-B0tYRju5.js:3572`（`/memories`）；`assets/c/MemoryView-legacy-DM4TzbJU.js:819`（分页列表）、`:437`（详情） | 无；`ui/XujingApp.kt:53` 只有会话/角色/资料库/设置四页 | 未实现 | 记忆只作为检索上下文注入，用户看不到“记住了什么”，也不能浏览或删除单条记忆。缺入口 + 展示数据（检索片段未落成可浏览实体）。 |
| 16 | 长期记忆检索（本地/远程嵌入 + 设置） | `assets/c/ChatView-legacy-C8UPOooP.js:26621`（总开关）、`:26703`（retrieveScanned）；`assets/c/ragService-legacy-DSja2Orj.js:70713`、`:70837`（gte-small） | `app/src/main/java/app/xujing/data/EmbeddingManager.kt`；`ui/Settings.kt:82`（模型与检索参数）；`ui/SessionSettings.kt:84` | 已实现 | 本地 ONNX 与远程 `/embeddings`、阈值/topK/片段长度、按模型分索引、重建（`docs/compatibility.md:65-72`）；原版逐条查询取最大相似度未对齐（`docs/piupiu-detail-gap-audit.md:31`），属差异不属缺功能面。 |
| 17 | 摘要（手动 + 自动增量） | `assets/c/ChatView-legacy-C8UPOooP.js:3148`（AutoSummaryFloating）、`:6319`（摘要注入）；`assets/c/classicSummaryContext-legacy-CRgs7-fN.js:400` | `core/.../engine/SummaryPlanner.kt:27`；`ui/SessionSettings.kt:76`；`ui/Chat.kt:122` | 已实现 | 手动/自动、保留近期原文、按批增量、进度游标、历史修改后失效；自动无后台定时（`docs/compatibility.md:36`）。 |
| 18 | 回复建议 | `assets/c/ChatView-legacy-C8UPOooP.js:10974`（replySuggestionConfig.enabled）、`:10961`（utility 控制段）、`:11868`（从回复提取建议） | `ui/Chat.kt:160`；`AppController.kt:464`（额外请求 JSON 数组） | 部分实现 | 入口与生成有；缺“从普通模型回复的 `<reply_suggestions>` 标签提取并写回消息”的路径，建议也不持久化到消息（`docs/piupiu-detail-gap-audit.md:30`）。 |
| 19 | 回复设置（长度上下限 / 建议条数） | `assets/c/ImageViewer-legacy-DD5gJaNv.js:3683`（ReplySettingsModal，`minChars/maxChars/minUnlimited/maxUnlimited`、建议 `count`） | 无；仅 `ui/Editors.kt:117` 的 `max_tokens` 参数 | 未实现 | 缺界面和配置数据；当前回复长度只能通过连接的原始 `parameters` 间接影响，建议条数固定取 5。 |
| 20 | 状态栏（原生状态栏配置） | `assets/c/ImageViewer-legacy-DD5gJaNv.js:5714`（StatusBarSettingsModal）、`:5483`（StatusItemEditModal）；`assets/c/ChatView-legacy-C8UPOooP.js:5111`（statusBarConfig） | 无；`core/.../engine/PromptEngine.kt:73` 的 blocks 无 statusBar 键 | 未实现 | 缺配置入口和引擎注入；卡片可用 HTML/变量自绘状态栏，但那不等同于原版的状态栏配置能力。 |
| 21 | 消息编辑 / 回溯 / 候选（swipe） | `assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js:1248`（ChatMenuModal 的 `settings-messages`）、`:21032`（swipes/swipe_id）；`assets/c/ChatView-legacy-C8UPOooP.js:20395` 附近编辑保存 | `ui/Chat.kt:204`（编辑/回溯弹窗）、`:264`（候选切换）；`AppController.kt:388`、`:409`、`:442` | 已实现 | 编辑重跑正则、候选快照、回溯清理摘要/索引均有；`docs/compatibility.md:35` 记录“中间历史不能自动重演任意脚本副作用”。 |
| 22 | 聊天搜索 | `assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js:40458`（ChatSearchModal） | `ui/Chat.kt:186`；菜单入口 `ui/Chat.kt:118` | 已实现 | 当前会话内忽略大小写匹配、展示说话人和片段、点击跳转；无防抖/异步版本控制（`docs/piupiu-detail-gap-audit.md:32`），搜索原始文本而非渲染后视觉文本。 |
| 23 | 角色管理（大厅 → 我的角色、编辑、置顶、导入导出） | `assets/e/index-legacy-B0tYRju5.js:3585`（`/role-hall`）；`assets/c/RoleHallView-legacy-OCYgw5tR.js:11708`、`:13796`（我的角色）、`:2262`（RoleEditModal） | `ui/XujingApp.kt:113`（角色页）、`:191`；`ui/Editors.kt:20` | 部分实现 | 列表/搜索/新建/编辑/复制/置顶/删除/导出/导入都有；缺标签分类、市场、批量操作、开场白选择（#28）。 |
| 24 | 角色市场 / 市场评论 / 上传协议 | `assets/c/RoleHallView-legacy-OCYgw5tR.js:13775`（角色市场）、`:1872`（RoleMarketUploadModal）；`assets/c/useMarketUploadAgreement-legacy-CIq0LfZF.js:488` | 无 | 刻意不做 | 依据见 §3“市场”。 |
| 25 | 备用开场白选择 | `assets/c/RoleHallView-legacy-OCYgw5tR.js:6736`（GreetingSelectModal） | `ui/Editors.kt:31`（编辑备用开场）；`AppController.kt:189`（作为候选灌入首条消息） | 部分实现 | 数据与编辑有，开场时不做选择弹窗：第一条消息的 swipe 列表即备用开场，用户需在消息上翻候选。 |
| 26 | 角色卡 / 资料 / 聊天 / 设置的导入导出 | `assets/c/SettingsView-legacy-CYxjl7kQ.js:1463`（SettingsBackupModal）、`:2831`（导出设置文件名）；PNG/JSON 导入在角色大厅 | `ui/Settings.kt:63`；`ui/Chat.kt:125`；`AppController.kt:547`；`core/.../compat/CompatibilityCodec.kt` | 已实现 | PNG/JSON 角色卡、世界书/预设/正则/人设/文风、聊天 JSON、设置 JSON 均有；范围见 `docs/compatibility.md:9-19`。 |
| 27 | PiuPiu 数据备份 ZIP（manifest/NDJSON/媒体/CRC） | `assets/c/dataBackupService-legacy-DP8RNBWM.js:2266`（文件名）、`:2301`/`:2343`（NDJSON 条目）、`:2475`（manifest） | `core/.../compat/CompatibilityCodec.kt:407`；`ui/Settings.kt:65` | 已实现 | 导入导出 PiuPiu v1 ZIP 与自有 `xujing-state.json`；大小/条目上限见 `docs/compatibility.md:23`。导入为合并而非覆盖，与原版覆盖式导入不同。 |
| 28 | 多连接 / 自定义 OpenAI 兼容 API（多 provider） | `assets/c/ChatView-legacy-C8UPOooP.js:15864`（`apiurl` 开关）、`:15913`（fetch 自定义端点）、`:18536`（模型列表） | `ui/Settings.kt:35`（连接列表）；`ui/Editors.kt:91`（URL/模型/密钥/头/参数）；`ui/SessionSettings.kt:33` | 已实现 | 可配置任意 OpenAI 兼容端点、请求头、采样参数、上下文预算，按会话覆盖；对应的是原版 TavernHelper `custom_api` 能力，而不是“连接中心”（#29）。 |
| 29 | 连接中心（渠道 / 卡密 / 账号绑定 / VIP 状态） | `assets/e/index-legacy-B0tYRju5.js:3598`（`/connection`）；`assets/c/ConnectionView-legacy-DbxMl1Yy.js:1449`、`:1445`（`piupiu_app_vip_key`） | 无 | 刻意不做 | 依据见 §3“账号/VIP/专有凭据”。 |
| 30 | 邀请系统（奖励码/验证码） | `assets/c/InviteSystemModal-legacy-BrJeYdi2.js:659`、`:370`（奖励码）、`:546`（验证码） | 无 | 刻意不做 | 依据见 §3“邀请”。 |
| 31 | Live2D 立绘 | `assets/c/Live2DViewer-legacy-Cs35RHtK.js:746`；`assets/c/HomeView-legacy-CpTLpq7h.js:4933`（首页挂载并传 outfit） | 无 | 刻意不做 | 依据见 §3“Live2D”。 |
| 32 | TTS（在线语音 / 自动朗读） | `assets/c/BackgroundSettingsModal-legacy-B5m4oeJe.js:1293`（TTSSettingsModal）、`:498`（`/tts-proxy.php`）、`:726`（speak） | 无 | 刻意不做（延期） | 依据见 §3“TTS/生图”；不是永久排除，属明文 deferred。 |
| 33 | 生图（云端 + 本地 8081 服务） | `assets/c/ImageViewer-legacy-DD5gJaNv.js:6548`（ImageGenSettingsModal）；`assets/c/ChatView-legacy-C8UPOooP.js:24998`（generateImage）、`:7400`（127.0.0.1:8081） | 无 | 刻意不做（延期） | 依据见 §3“TTS/生图”。 |
| 34 | 全局设置页（个人/通用/外观/体验/全局配置/关于） | `assets/c/SettingsView-legacy-CYxjl7kQ.js:3057`、`:3422`（六个分区定义）、`:4705`/`:4758`/`:4819`/`:4870`/`:4913`（状态栏/语音/记忆/回复/配图入口） | `ui/Settings.kt:19`；`ui/XujingApp.kt:69` | 部分实现 | 主题/动态色/字号/默认人设/连接/记忆/脚本开关/导入导出/关于已有；缺“个人设置（含邀请）”“语音”“回复”“配图”“状态栏”分区与 BGM（对应 #19/#20/#32/#33/#37）。 |
| 35 | 背景与气泡外观 | `assets/c/BackgroundSettingsModal-legacy-B5m4oeJe.js:2823`（BackgroundSettingsModal）、`:2007`（BubbleSettingsModal） | `ui/SessionSettings.kt:90`（背景/透明度/气泡样式/显示模式/字号）；`ui/Chat.kt:61`（背景图）、`:216`（透明度） | 部分实现 | 会话级背景图、透明度、圆角/方角、阅读/气泡模式、主题底色已有；缺全局背景、正文/引用/括号颜色与实时预览（`docs/piupiu-detail-gap-audit.md:12`）。 |
| 36 | 首页（好感度/签到/日运/成就/公告/邮箱） | `assets/c/HomeView-legacy-CpTLpq7h.js:4713`；`:588`（AffectionCard）、`:2631`（DailyFortuneModal）、`:771`（AchievementModal）、`:1010`（SiteMailboxModal）、`:691`（AnnouncementBadge） | 无；`ui/XujingApp.kt:53` 无首页 | 未实现 | 这些功能依赖账号与服务端（`/site_mailbox.php` 等），但 AGENTS/需求未逐项点名，故不按“刻意不做”计；实质上也受账号排除约束。 |
| 37 | BGM（内置/自定义背景音乐） | `assets/c/SettingsView-legacy-CYxjl7kQ.js:851`（BgmSettingsModal）、`:888`（曲目选择）、`:1000`（添加自定义） | 无 | 未实现 | 缺入口、播放器与自定义音频导入；与角色扮演核心无关，属体验项。 |
| 38 | 反馈（提交问题/建议） | `assets/c/SettingsView-legacy-CYxjl7kQ.js:1982`（FeedbackModal）、`:3420`（要求先登录） | 无 | 未实现 | 原版反馈要求登录并走服务端；Xujing 无账号，也没有本地替代（如导出诊断）。 |
| 39 | 官方网页弹窗 / APK 更新 | `assets/c/OfficialWebModal-legacy-DGd73aKM.js:323`、`:428`（下载 APK）；`assets/e/index-legacy-B0tYRju5.js:13511`（UpdateModal） | 无；仅 `ui/Settings.kt:144` 打开模型下载页 | 未实现 | 只指向原应用官网与 APK 更新；净室重写没有对应服务，属产品面缺失而非兼容缺口。 |
| 40 | 内置模型权重（随包 gte-small） | `assets/c/ragService-legacy-DSja2Orj.js:70837`（`Xenova/gte-small`）、`:70397`（`local_files_only`） | `ui/Settings.kt:120` 改为手动导入模型 ZIP；`docs/model-package.md` | 刻意不做 | 依据见 §3“内置权重”。功能面影响：首次使用记忆检索必须由用户自备模型或改用远程 embeddings。 |
| 41 | 全局资源市场（世界书/正则/预设市场与评论） | `assets/c/GlobalResourceMarketModal-legacy-BvchFYLb.js:3504`、`:2456`（评论）；`assets/c/ImageViewer-legacy-DD5gJaNv.js:11772`（GlobalPresetMarketModal） | 无 | 刻意不做 | 依据见 §3“市场”。 |
| 42 | 覆盖式导入的崩溃恢复 | `assets/c/overwriteRecovery-legacy-2YP7Wk_W.js:339`（beginProtectedOverwrite） | `core/.../compat/ImportMerger.kt`（合并导入）；无 staging/回滚状态 | 不确定 | 原版是“覆盖导入 + 原备份回滚”；Xujing 设计为合并导入，是否需要同等恢复机制取决于产品选择。未找到明文取舍，单列。 |
| 43 | 原版数据库迁移弹窗（v1→v4） | `assets/e/index-legacy-B0tYRju5.js:13850`（DatabaseMigrationModal） | 无；`core/.../Models.kt:58` 自有 `schemaVersion = 1` | 不确定 | 净室应用不读原版内部 SQLite，迁移对象可能不存在；是否算缺口取决于是否要求接管旧应用本地库。 |

---

## 2. 未实现清单（按用户感知价值排序）

> 只列状态为“未实现”的 10 项。每项说明缺的是**入口**、**数据**还是**引擎**。

1. **聊天图片上传 / 视觉输入** —— 缺入口（聊天输入区无附件/拍照）且缺数据（消息与 `PromptMessage` 都是纯文本，没有 `image_url` 多模态段），因此也谈不上请求引擎。对“发图让角色看”的用法是硬缺失。
2. **回忆页（长期记忆浏览/详情/删除）** —— 缺入口（导航只有四个页签）与展示数据（检索片段没有落成可浏览实体）。用户无法知道长期记忆里存了什么、也无法纠正错误记忆，只能整库重建。
3. **角色标签 / 分类筛选** —— 缺数据（`RoleCard` 没有 tags 字段）与入口（只有名称/描述搜索）。角色数量上来后无法按标签分组，导入的 `role_tags` 只能躺在原始 JSON 里。
4. **状态栏（原生状态栏配置）** —— 缺入口（无状态栏设置弹窗）和引擎（提示词组装没有 statusBar 段）。复杂卡若依赖原版状态栏配置，只能改用卡片自绘 HTML。
5. **回复设置（长度上下限 / 建议条数）** —— 缺入口与配置数据；当前只能改连接的 `max_tokens`，回复建议条数固定，无法按角色卡要求限制“短回复”。
6. **模型列表拉取与选择器** —— 缺引擎（不请求 `/models`）与交互（只有手填模型 ID）。换服务商时要自己抄模型名，容易填错。
7. **首页（好感度/签到/日运/成就/公告/邮箱）** —— 缺入口、数据与服务端。属于账号运营层，去掉后不影响核心扮演，但原版“每天打开有东西可看”的体验消失。
8. **BGM（内置/自定义背景音乐）** —— 缺入口、播放器与音频导入；纯氛围项，优先级低。
9. **官方网页弹窗 / APK 更新** —— 缺入口与对应服务；净室应用应走自己的发布渠道，属产品面而非兼容缺口。
10. **反馈（问题/建议提交）** —— 缺入口与服务端；可用“导出诊断/日志”类本地替代，但目前也没有。

---

## 3. 刻意不做清单

> 每条附明文依据原句。注意：`TTS`/`生图` 是 **deferred（延期）**，不是永久排除；表中仍归入此类，是因为“当前不做”是明确决定。

### 3.1 账号、VIP、邀请、专有凭据
- **依据**：`AGENTS.md:3` —— “No account, VIP, invitation, community, market, Live2D, bundled weights, or proprietary service credentials.”
- **依据**：`docs/requirements.md:18` —— “账号、邀请、VIP、原版许可/签名/固定凭据、社区/市场/在线聊天室、Live2D。TTS 与生图延期。”
- **依据**：`docs/compatibility-assessment.md:28` —— “云端内部逻辑、账号/VIP/邀请/社区、Live2D 不计入；语音和生图按既定需求后置。模型权重不随应用分发。”
- 覆盖表中项：#29 连接中心（渠道/卡密/VIP）、#30 邀请系统，以及登录/注册/找回密码/账号绑定（`assets/e/index-legacy-B0tYRju5.js:15349`、`:14596`、`:15692`）、请求签名与固定凭据（`assets/c/ChatView-legacy-C8UPOooP.js:6766`、`assets/c/app-security-legacy-BdXLf8ML.js:9880`）。

### 3.2 社区 / 在线聊天室
- **依据**：`AGENTS.md:3` —— “No account, VIP, invitation, **community**, market, …”
- **依据**：`docs/requirements.md:18` —— “社区/市场/**在线聊天室**”。
- 覆盖表中项：#3 聊天室（含禁言、黑名单、成员资料卡、好感度徽章）。

### 3.3 市场（角色市场 + 全局资源市场）
- **依据**：`AGENTS.md:3` —— “No account, VIP, invitation, community, **market**, …”
- **依据**：`docs/requirements.md:18` —— “社区/**市场**/在线聊天室”。
- 覆盖表中项：#24 角色市场/评论/上传协议、#41 全局资源市场（世界书/正则/预设市场）。

### 3.4 Live2D
- **依据**：`AGENTS.md:3` —— “… **Live2D**, bundled weights, …”
- **依据**：`docs/requirements.md:18` —— “… 社区/市场/在线聊天室、**Live2D**。”
- 覆盖表中项：#31（含首页立绘、换装、PIXI 渲染）。

### 3.5 TTS（延期）
- **依据**：`AGENTS.md:3` —— “**TTS** and image generation are deferred.”
- **依据**：`docs/requirements.md:18` —— “**TTS 与生图延期**。”
- **依据**：`docs/compatibility-assessment.md:28` —— “**语音和生图按既定需求后置**。”
- 覆盖表中项：#32。导入设置时的运行时提示也保留该项：`core/.../compat/CompatibilityCodec.kt:404` —— “原版设置完整保留；账号、VIP、语音、生图和 Live2D 配置不启用。”

### 3.6 生图（延期）
- **依据**：同 §3.5（`AGENTS.md:3`、`docs/requirements.md:18`、`docs/compatibility-assessment.md:28`）。
- 覆盖表中项：#33（云端 `image-generate-*.php` 与本地 8081 两条路径）。

### 3.7 内置模型权重
- **依据**：`AGENTS.md:3` —— “… **bundled weights**, …”
- **依据**：`docs/compatibility-assessment.md:28` —— “**模型权重不随应用分发**。”
- **依据**：`docs/requirements.md:11` —— “本地与远程 Embeddings 均支持，**模型权重不随安装包分发**；提供来源下载页及手动模型包导入。”
- 覆盖表中项：#40。Xujing 保留记忆检索能力（#16），但首次使用需用户导入模型包或配置远程 embeddings。

### 3.8 原版外观/服务配置的逐项复刻
- **依据**：`docs/compatibility.md:17` —— “设置 JSON … **不逐项复刻原版外观/服务配置**”。
- 适用范围：设置导入只保留原文，不激活原版服务器、渠道、外观细节；不能用它来把 #19/#20 等未实现项“洗成”刻意不做——这些仍按未实现计。

---

## 4. 判断不确定的项

1. **覆盖式导入的崩溃恢复（#42）** —— 原版有 `beginProtectedOverwrite` 与 staging/回滚键（`assets/c/overwriteRecovery-legacy-2YP7Wk_W.js:339`）。Xujing 明确采用合并导入（`docs/compatibility.md:21`），冲突 ID 重分配。但没有任何文档说明“是否需要为合并导入提供同等崩溃恢复”。因此不判定为缺口，也不判定为刻意不做。
2. **原版数据库迁移（#43）** —— 原版有 `DatabaseMigrationModal`（`assets/e/index-legacy-B0tYRju5.js:13850`），Xujing 用自己的 `schemaVersion`。是否要读取/迁移原应用内部 SQLite 取决于产品边界（当前文档只承诺交换格式与备份 ZIP），没有明文取舍。
3. **随机背景图服务（`randomNekoService`）** —— `assets/c/randomNekoService-legacy-CyYdQN96.js:240` 调 `nekos.best` 与自家 `proxy_image.php`。静态代码只能确认服务存在，无法确认首页/背景是否实际使用、用户是否感知；且依赖原站代理，不在明文排除清单里。
4. **多媒体角色卡 / 聊天媒体引用** —— `docs/compatibility.md:11` 把“多媒体卡”列为“尚待核对”，Xujing 的导入会保留媒体并支持头像；但聊天内图片展示/发送没有实现（#5 已按未实现计）。卡片内嵌媒体的“应支持到什么程度”没有明确口径，故不把“聊天媒体引用”单列为独立缺口。
5. **“群聊”是否应升级为独立实体** —— 当前 Xujing 用会话 + 多 `roleIds` 表达群聊（`ui/XujingApp.kt:192`、`ui/SessionSettings.kt:29`），原版有 `CreateGroupChatModal`/`GroupEditModal` 与群级数据。文档没有说明是否要求群成为可复用实体（跨会话共享群设置），因此 #2 只记“部分实现”，不进一步断言缺哪张表。

---

## 5. 复核提示

- 本文的 Xujing 结论来自源码入口与引擎路径的静态阅读；`docs/feature-progress.md:41` 与 `docs/compatibility.md:5` 都明确“源码接入 ≠ 已验收”，本文不改变这一口径。
- 引用 PiuPiu 行号前已避开第三方库区段：例如 `ragService` 只引用 70356 之后的自有 `RagService`；`GroupMemberSelector` 只引用组件定义、沙箱策略、授权与消息字段，不引用 27268 附近内嵌的 jQuery/lodash/Vue。
- 想快速复核任一 PiuPiu 锚点，可用 `Select-String -Path <file> -Pattern '<常量>' -SimpleMatch`，再回读上下文；不要按文件名推断模块职责。
