# PiuPiu 22 条补点复核（core / 引擎层）

日期：2026-11-04。复核对象：`docs/piupiu-detail-gap-audit.md`（写于 2026-11-03）中的 22 条。
方法：只读静态检查当前工作区源码；清单里的旧行号全部作废并重新定位；未构建、未运行测试或模拟器。

判定口径：

- `已实现`：当前代码确实执行了清单要求的核心行为，并给出新定位的证据行。
- `部分实现`：核心链路已改，但清单描述的一部分语义仍缺。
- `未实现`：当前代码仍保持清单所描述的旧行为。
- `无法判定`：拿不到可验证证据；本次没有出现这种情况。
- 原版与本项目实现方式不同但效果等价时，按 `已实现` 计，并在备注写明差异。

---

## 1. 22 条复核总表

| 编号 | 主题 | 当前状态 | Xujing 证据(file:line) | PiuPiu 证据(file:line) | 剩余差距 | 优先级 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 01 | HTML 围栏与混合消息分段 | 已实现 | `app/.../ui/MessageContentParser.kt:22`（parse）、`:44-51`（未闭合围栏按代码到文末）、`:58-60`（仅 `html` 语言进 HTML 段）、`:68-81`（前后文字逐段切分）；`app/.../ui/Chat.kt:240`、`:255-261`（每个 Html 段独立 CardMessage）；`app/.../runtime/CardMessage.kt:204-210`（documentMode 保留完整文档 head/body） | `GroupMemberSelector-legacy-BIJ8h7lR.js:35904`（`Ee` 识别 language-* 与 HTML 内容）、`:34925-34933`（sandbox iframe 承载每段） | 无功能缺口。判定差异：Xujing 只把 `html` 语言围栏当 HTML，非围栏的内联标签也单独成段；原版还依据 DOM 结构判断。 | P1 | 清单旧行号（Chat.kt:228 / CardMessage.kt:34）已失效。QA HTML-01～08 已在模拟器通过，但本次未复跑。 |
| 02 | 引用、括号、正文颜色及外观预览 | 部分实现 | `app/.../ui/Chat.kt:241-243`（quote/bracket 颜色仍按明暗主题固定）、`:276`（protectedRanges）、`:303`（代码/链接保护）、`:313-319`（引号与括号高亮，跳过保护区）；`app/.../ui/SessionSettings.kt:90-100`（只有透明度、气泡样式、字号、背景） | `BackgroundSettingsModal-legacy-B5m4oeJe.js:2021-2023`（highlight/bracket/normalColor 默认值）、`:2117-2119`（三项可改）、`:2248-2282`（预览）；`GroupMemberSelector-legacy-BIJ8h7lR.js:36191`（写入 CSS 变量） | 缺三种颜色的会话级配置、normalColor 的应用、实时预览与“恢复默认”；HTML 段仍不经过 basicMarkdown 的引号/括号高亮。 | P2 | 高亮算法已存在且已做代码/链接保护，属于“机制在、配置没接”。 |
| 03 | 正则 trimStrings 完整替换语义 | 已实现 | `core/.../engine/RegexProcessor.kt:61-62`（trimStrings 全部展开并折叠应用）、`:64`（`{{match}}` 结果 clean）、`:65-73`（`$1`、`$&`、`$<name>` 逐个 clean） | `e/index-legacy-B0tYRju5.js:1216-1263`（每个捕获值走 trim）、`:1266`（替换结果再做一次宏展开） | 原版在捕获替换完成后对整段结果再跑一次宏展开；Xujing 只在替换模板上展开一次。捕获内容里带 `{{...}}` 的极端规则会有差异。 | P1 | 旧的“首个 trim 消耗占位符”问题已消失，trim 是纯函数折叠，不再消费。 |
| 04 | 编辑消息遵守 runOnEdit | 已实现 | `core/.../engine/RegexProcessor.kt:32`（isEdit 且无 runOnEdit 直接跳过）、`:36-42`（深度/位置仍生效）；`core/.../engine/PromptEngine.kt:192-203`（按消息角色、深度重跑对应阶段）；`app/.../AppController.kt:388-390`（编辑走 processEdit） | `e/index-legacy-B0tYRju5.js:1073-1080`（`isEdit && !runOnEdit` 跳过）；`c/ChatView-legacy-C8UPOooP.js:20409-20413`（edited → updated） | 编辑路径不发 `message_edited` / `message_updated` 事件（并入 14 条）。 | P1 | 与清单要求一致；变量更新副作用不会在编辑时重放。 |
| 05 | 世界书 characterFilter 与 triggers | 未实现 | `core/.../engine/WorldbookEngine.kt:30-69`（collect 只做 disable/enabled 过滤）、`:70-106`（只处理书级绑定，无角色名/标签/triggers 判定） | `e/index-legacy-B0tYRju5.js:20739-20762`（`rm` 处理 names/tags/isExclude，`im` 处理 triggers）、`:21644-21647`（归一化 characterFilter）、`:21636-21642`（triggers） | 缺按角色文件名/名称/标签与 isExclude 的筛选，也缺按生成模式（normal/continue/regenerate 等）过滤 triggers。 | P1 | 角色 `tags` 可从 `RoleCard.raw` 取，不需要改数据模型。 |
| 06 | 数值 delayUntilRecursion | 已实现 | `core/.../engine/WorldbookEngine.kt:158-166`（true→1，数字保留，false/缺省→0）、`:194`（取最大延迟步）、`:241`（无新增命中也要等过最后延迟步才结束） | `e/index-legacy-B0tYRju5.js:22225-22243`（收集等待步）、`:22417-22438`（true→1、数字保留，`a < C` 不激活） | 无功能缺口。 | P1 | 旧问题“顶层数字被当布尔”已修。 |
| 07 | 世界书互斥组评分 useGroupScoring | 部分实现 | `core/.../engine/WorldbookEngine.kt:199-208`（已有 sticky → groupOverride → groupWeight 加权） | `e/index-legacy-B0tYRju5.js:20764-20868`（sticky/override 后按主副键命中数取最高分，再按权重抽选）、`:20821`（单条/全局可覆盖开关） | 缺“按组内最高关键词命中数先筛一遍”的阶段；条目级 `useGroupScoring` 与全局开关都未读取。 | P1 | 现有 sticky/override/weight 顺序与原版一致，只差评分筛选。 |
| 08 | scanDepth=0 与书级默认继承 | 部分实现 | `core/.../engine/WorldbookEngine.kt:131`（条目级允许 0）、`:108-109`（全局深度仍 `coerceIn(1,1000)`）、`:240`（minActivations 扩展扫描） | `e/index-legacy-B0tYRju5.js:22453-22482`（条目显式值 → `book.scan_depth` → `defaultScanDepth`，允许 0；仅继承值随最少激活扩展） | 条目级 0 已支持；缺书级 `scan_depth` 继承，全局默认仍不能为 0，`maxDepth` 也没有书级来源。 | P2 | 如果只从会话配置设深度，0 会被抬成 1。 |
| 09 | 世界书预算 0 的含义 | 已实现 | `core/.../engine/WorldbookEngine.kt:113-116`（percent>0 才算百分比；cap>0 才封顶；两者都 0 → `budget=null` 表示无上限）、`:218`（null 不拦截） | `e/index-legacy-B0tYRju5.js:22188-22201`（`$=Infinity` 或 floor；`J=G>0?min($,G):$`） | 无功能缺口。 | P1 | 等价实现：`null` 对应原版 `Number.POSITIVE_INFINITY`。QA 报告已静态核对。 |
| 10 | 世界书内容装饰器及阶段分流 | 未实现 | `core/.../engine/WorldbookEngine.kt:214-224`（entry content 直接宏展开后进正文）、`core/.../engine/PromptEngine.kt:46`/`:104-106`（世界书文本直接注入）；`app/src/main/assets/card-template.js:6-23` 只解析消息模板的 `@@`，与世界书条目无关 | `e/index-legacy-B0tYRju5.js:20616-20655`（`Qp` 解析 @@activate/@@dont_activate/@@only_preload/@@initial_variables/@@render_*/@@generate_*/@@@） | 控制行仍会进入模型正文；没有强制激活/禁用/预载分流，也没有 render/generate 阶段。 | P2 | card-template 的 `@@` 解析容易被误认为已实现，实际作用域不同。 |
| 11 | 世界书时效状态的内容哈希 | 未实现 | `core/.../engine/WorldbookEngine.kt:167-173`（sticky/cooldown 只看 turn）、`:233-236`（写 state 只有 lastActivatedTurn/stickyUntil/cooldownUntil） | `e/index-legacy-B0tYRju5.js:22395-22409`（比对 `contentHash`）、`:23438`（写入时计算 contentHash） | 状态没有 contentHash；改条目内容后旧 sticky/cooldown 继续生效。 | P2 | 需要同时改状态结构和读取侧校验。 |
| 12 | 嵌套变量合并 | 已实现 | `app/src/main/assets/card-runtime.js:56-62`（对象递归合并、数组整体替换）、`:63`（insertOrAssignVariables 组合 get→merge→replace） | `c/GroupMemberSelector-legacy-BIJ8h7lR.js:30940`（`__mergeReplacingArrays`，并含 insertVariables/deleteVariable/getAllVariables） | 只提供 `insertOrAssignVariables`；缺 `insertVariables`（反向优先级）、`deleteVariable(path)`、`getAllVariables`。 | P1 | 清单的核心覆盖问题已修；配套接口属于同一族的后续项。 |
| 13 | 生成事件的参数和多参数支持 | 部分实现 | `app/.../AppController.kt:285-294`（prompt_ready 给 `{chat}`，after_data 给 `{messages}`，可回写）、`:312-315`（流式 token 两档）、`:372-374`（received/ended）；`app/src/main/assets/session-runtime.js:57-67` + `card-runtime.js:153-162`（按数组展开多参数） | `c/ChatView-legacy-C8UPOooP.js:25684`（generation_started `[M,{},false]`）、`:27029-27046`（prompt_ready/after_data 都带 `dryRun:!1`，after_data 键为 `prompt`）、`:12109-12124`（received/ended） | 缺 `dryRun:false`；`generation_started` 只有 `mode` 一个参数，缺 `{}` 与 `false`；`generate_after_data` 用 `messages` 而不是原版别名 `prompt`。 | P1 | 多参数转发与改写回传已通；QA EVENT-03 已验证 after_data 能改真实请求。 |
| 14 | 消息生命周期事件与跨环境广播 | 部分实现 | `app/.../runtime/SessionScriptRuntime.kt:63-66`（脚本环境先跑，再进卡片 WebView）、`app/.../runtime/CardMessage.kt:55-65`（按消息索引/段序顺序广播，失败保留前值）、`app/.../AppController.kt:372-374`（发送 received/ended） | `c/ChatView-legacy-C8UPOooP.js:11527-11530`（`Va`/`za` 顺序广播并带回修改）、`:20409-20413`（edited → updated）、`:12109-12124`（received → rendered → updated → ended） | 编辑路径不发 `message_edited`/`message_updated`；生成完成路径没有 `character_message_rendered` 与 `message_updated`；`message_sent` 未派发。 | P1 | 注册/顺序/销毁等基础设施已具备，缺的是具体事件点位。 |
| 15 | 角色独立脚本按钮闭环 | 已实现 | `core/.../compat/SessionScripts.kt:6`（SessionScript 带 buttons）、`:24`（读取 button/buttons）；`app/src/main/assets/session-script-runtime.js:21`（事件名 hash）、`:37-58`（getScriptId/getButtonEvent/getScriptButtons/replaceScriptButtons）；`app/.../runtime/SessionScriptRuntime.kt:48-61`（收集/去重/稳定排序）、`:140-143`（按钮上报）；`app/.../ui/Chat.kt:82`、`:134-141`（输入区按钮，生成/忙碌禁用） | `c/GroupMemberSelector-legacy-BIJ8h7lR.js:30975-30979`（脚本身份与按钮接口/上报）；`c/ChatView-legacy-C8UPOooP.js:24231`（汇总启用脚本按钮） | 无核心缺口。 | P1 | 每脚本独立 iframe（`session-runtime.js:22-38`）解决了旧版多 Blob 共用环境。QA SCRIPT-01/02/05/06 已通过。 |
| 16 | 更多变量作用域与合并优先级 | 未实现 | `core/.../compat/CardVariables.kt:19-23`（只认 chat/message，其余抛错）；`app/src/main/assets/card-runtime.js:36-42`（同样只实现 chat/message） | `c/ChatView-legacy-C8UPOooP.js:16894` 起（message/chat/global/preset/…）；`c/GroupMemberSelector-legacy-BIJ8h7lR.js:30940`（script/extension/all 及统一合并顺序） | global / preset / character / extension / all / script 作用域与合并优先级都缺；宿主侧也没有按归属持久化与导入映射。 | P1 | 只补前端内存不算接完；需要 core 新增存储键。 |
| 17 | 已有 MVU/Zod 库参与真实输出链 | 未实现 | `core/.../engine/PromptEngine.kt:181-190`（processOutput → RegexProcessor → MacroProcessor → `VariableUpdates.apply`）、`core/.../engine/MvuSession.kt:158-191`（重放同样是纯 Kotlin）；`app/src/main/assets/card-runtime.js` 的 MVU 钩子只服务卡片自身渲染 | `c/ChatView-legacy-C8UPOooP.js:11060-11077`（init/update-start/command-parsed/for-zod/update-end/before-write 事件映射）、`:11163`（等待脚本）、`:11177`（回写 variables/commands） | 生成输出链仍不调用 `card-vendors/mvu.js` 或 Zod 校验，阶段事件、参数与写回协议都没有。 | P1 | 这是清单里最大的架构项，不是“库已存在”就能算接完。 |
| 18 | injectPrompts/uninjectPrompts 与注册宏 | 未实现 | `app/src/main/assets/card-runtime.js:127-136`（TavernHelper API 无 injectPrompts/uninjectPrompts/registerMacro）、`:135`（substituteParams 只有 user/char/getvar） | `c/ChatView-legacy-C8UPOooP.js:18459-18497`（按 id 保存/删除，支持 once）；`c/GroupMemberSelector-legacy-BIJ8h7lR.js:30975`（脚本侧 TavernHelper 面） | 缺持久注册的提示词注入表、once 消费清理，以及可注册宏；现有钩子只能一次性改 messages。 | P2 | 与 13 条的“生成前改数组”不是同一机制。 |
| 19 | generateRaw 区分、流事件与按任务取消 | 未实现 | `app/src/main/assets/card-runtime.js:132-133`（generate 与 generateRaw 走同一个 `generate` 请求）、`app/.../AppController.kt:619-627`（只读 user_input/prompt/ordered_prompts，返回字符串）、`:228`（stop 全局取消，无 per-generationId） | `c/GroupMemberSelector-legacy-BIJ8h7lR.js:30992`（generate/generateRaw 区分）、`:28394-28401`（iframe 事件定义）；`c/ChatView-legacy-C8UPOooP.js:18503`（stopGenerationById） | 没有 raw 模式差异、没有把流增量回送脚本所属任务、没有按 generationId 取消/多任务隔离。 | P2 | 先把任务 ID 与取消传播做出来，再谈其他生成选项。 |
| 20 | 直接提取正常回复内的建议 | 未实现 | `app/.../AppController.kt:464-489`（仍额外请求 JSON 数组，建议只进临时 UI 状态）；全仓无 `reply_suggestions`/`replySuggestions` | `c/ChatView-legacy-C8UPOooP.js:10992-11015`（`Aa` 解析并清正文控制块）、`:11868-11870`（完成后写入消息） | 没有从普通回复解析 `<reply_suggestions><option>`，也没有去空白/1–5 条限制、正文移除和随消息保存。 | P1 | 现有“回复建议”按钮可保留为手动入口。 |
| 21 | 记忆逐条查询后取最大相似度 | 未实现 | `app/.../data/EmbeddingManager.kt:109-112`（最近 N 条拼成一个 query 再 `takeLast(queryLimit)`）、`:114-118`（单向量打分） | `c/ragService-legacy-DSja2Orj.js:70562-70575`（多 query 分别生成向量）、`:70784-70790`（每条消息取各 query 的 max 相似度） | 拼接+截断会丢掉较早查询内容；缺逐条 embedding 与 max 聚合。 | P1 | 模型隔离、阈值、最终按历史顺序排序这些现有逻辑可保留。 |
| 22 | 搜索防抖、异步请求版本与动态高度跳转 | 未实现 | `app/.../ui/Chat.kt:189`（每次输入 `remember(search, messages)` 全量过滤）、`:192-195`（点击只 `scrollToItem` 一次） | `c/GroupMemberSelector-legacy-BIJ8h7lR.js:40494-40498`（180ms 防抖）、`:39431`（等待布局稳定再校正）；`c/ChatView-legacy-C8UPOooP.js:27908`（递增版本拒绝迟到结果） | 防抖、版本号、WebView/图片撑高后的二次定位都没有。 | P2 | 当前数据在内存里，不需要照搬数据库分页层。 |

**统计：已实现 7 条（01/03/04/06/09/12/15），部分实现 5 条（02/07/08/13/14），未实现 10 条（05/10/11/16/17/18/19/20/21/22）。没有 `无法判定` 项。**

---

## 2. 仍未实现的条目（按优先级）

> 只列 `未实现` 的 10 条。`部分实现` 的 02/07/08/13/14 缺哪一块已写在总表“剩余差距”列。

### P1

1. **16 更多变量作用域** —— 先在 core 增加按作用域归属的持久化结构（global/preset/character/extension/script），再把 `CardVariables.read/apply` 从 chat/message 扩到统一合并顺序；宿主导入时要把原版分桶字段映射进来，不能只放在 WebView 内存。
2. **21 记忆多查询聚合** —— 改 `EmbeddingManager.retrieve`：对 `memoryQueryMessages` 的每条内容分别生成向量，对每个 chunk 取各 query 相似度的最大值，再套现有阈值、topK 与历史顺序。
3. **20 正常回复内的建议解析** —— 在输出处理链（`PromptEngine.processOutput` 附近）加 `<reply_suggestions>` 解析与正文移除，把结果写入 `ChatMessage` 的新字段并接到现有建议 UI，保留手动建议按钮。
4. **05 世界书 characterFilter/triggers** —— 在 `WorldbookEngine.activate` 的收集阶段加纯过滤函数：用 `RoleCard.raw` 的名称/标签匹配 names/tags、按 isExclude 取反，并把生成模式传进入口过滤 triggers。
5. **17 MVU/Zod 阶段写回** —— 需要把 `processOutput` 的纯 Kotlin 更新改成可回调脚本环境的阶段链（init/update-start/command-parsed/for-zod/update-end/before-write），并定义 variables/commands 的返回契约；core 不能依赖 Android，宿主负责调度。

### P2

6. **10 世界书装饰器** —— 在 `WorldbookEngine` 收集/注入之间加 `@@` 解析器，先落地强制激活/禁用/预载与 `@@@` 转义，再决定 render/generate 阶段由谁执行。
7. **11 时效状态内容哈希** —— 条目激活时把内容哈希写进 `worldState`，读取 sticky/cooldown 前比对；内容变化即视为旧状态失效。
8. **19 generateRaw 与按任务取消** —— 给脚本生成分配 generationId，区分 raw/普通模式，把流增量按任务回送，并让 `stop()` 支持按任务取消；生成结果是否落库需要与现有保存规则对齐。
9. **18 injectPrompts 与注册宏** —— 在脚本运行时加按 id 的提示词注入表和 `once` 清理点，接到 `PromptEngine` 的提示段编排；宏注册表要参与 `MacroProcessor` 的未知宏兜底。
10. **22 搜索防抖与定位校正** —— Compose 侧加 180ms 防抖与递增版本，点击结果后等待 `LazyList`/WebView 高度稳定再二次校正；与现有内存过滤共存即可。

---

## 3. 清单之外、同属 core/引擎层的缺口

1. **世界书默认参数与原版不一致。** `WorldbookEngine.kt:111-114` 默认预算 25%/2048、扫描深度 4、递归上限 8；PiuPiu 的 `zp` 默认是 `budgetPercent:0 / budgetCap:0 / maxRecursionSteps:0 / defaultScanDepth:2`（`e/index-legacy-B0tYRju5.js:20416-20430`，并在 `:21981` 合并）。未显式配置时，Xujing 会额外应用预算与递归，而原版默认近乎不限制。
2. **世界书扫描源缺角色深度提示与创作者注释。** `WorldbookEngine.kt:136-143` 只拼角色描述/性格/场景、人设与递归文本；PiuPiu 还支持 `matchCharacterDepthPrompt`、`matchCreatorNotes` 以及 `scanSources.extensionPrompts`（`e/index-legacy-B0tYRju5.js:21666-21682`、`:22544-22578`）。依赖这些字段的条目会漏触发。
3. **世界书激活使用的随机源是固定种子。** `PromptEngine.kt:38-39` 生成 `Random(seed)` 后传给引擎，`WorldbookEngine.kt:189-206` 的概率与组权重都取自它；PiuPiu 用 `Math.random()`（`:20885`、`:22753`）。同一条消息反复重生成时，Xujing 的世界书命中会保持恒定。若这是为 MVU 回放一致性做的取舍，需要在文档里写明，否则属于行为差异。
4. **正则替换缺少结果级二次宏展开，且支持的替换记号集合不同。** `RegexProcessor.kt:60-75` 只对替换模板展开一次宏；PiuPiu 在捕获替换后还会对整段结果再跑 `Pi(...)`（`e/index-legacy-B0tYRju5.js:1266`）。同时 Xujing 额外支持 `$&`/`` $` ``/`$'`，原版只处理 `$n` 与 `$<name>`（`:1233-1251`）。
5. **脚本身本收集在“现代键存在但无 scripts”时丢掉旧键。** `SessionScripts.kt:39` 只要 `extensions.tavern_helper` 存在就只读它的 `scripts`，不会再回退 `extensions.TavernHelper_scripts`；带空 `tavern_helper` 的旧卡会少加载脚本。原版脚本存储对两代格式都做兼容（`tavernHelperScriptStorage-legacy-BVIVfja_.js:375` 一带）。
