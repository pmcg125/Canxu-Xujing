# 预设对外接口对照（PiuPiu 2.7.9 ↔ 残叙·残念）

日期：2026-11-05。范围：角色卡脚本 / TavernHelper / SillyTavern 兼容层能调用的**预设相关接口**，以及生成期预设如何落成 `messages`。

口径：
- 本文全部是**静态源码对照**。没有运行前端、没有跑构建/测试，任何「行为」都指代码路径，不是运行验证。
- PiuPiu 行号基于 `PiuPiu-source/frontend-readable/assets/` 下的格式化副本；Xujing 行号基于仓库当前文件。
- 表格状态：`一致` / `部分`（同名可用但契约或形状有差）/ `缺失` / `语义不同`（同名但含义不同）。
- 没有看到实现不下结论；无法确定的一律进最后一节。

---

## 0. 先说结论

1. PiuPiu 的预设接口**分两层**：`window.*`（ChatView 挂载时安装、卸载时删除）与 `TavernHelper.*`（模块级常量对象，常驻）。两者**不是同一份**：`TavernHelper` 上没有 `getPresetManager`，`window` 上没有 `getAllPresets/getActivePresetId/setActivePresetId`（这三个只在脚本 iframe 的 bootstrap 里补）。
2. PiuPiu 的 `loadPreset(name)` 是**同步的「选中」动作，返回布尔**，不加载数据、不返回数据；真正的数据读取是 `getPreset` / `getPresetSettings`。
3. PiuPiu 的 `TavernHelper.getPreset()` 与 `getPresetManager().getPresetSettings()` **返回形状不同**（前者经 `bo()` 转成 `{settings,prompts,prompts_unused,extensions}`，后者是原始 JSON 深拷贝）。
4. PiuPiu **没有把预设采样参数用于主聊天请求**：`buildAsync` 只返回 `{messages}`，请求固定 `temperature:1.1 / max_tokens:8192`。预设采样只出现在 `getPresetSettings()` 的返回值里。
5. Xujing 一侧**只实现了读 + 选中**，所有预设写接口缺失；读接口又多了一条「必须先 `loadPreset`」的前置条件，返回的是原始预设 JSON。

---

## 1. 接口总表

证据前缀约定：`CV` = `assets/c/ChatView-legacy-C8UPOooP.js`，`GMS` = `assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js`，`IDX` = `assets/e/index-legacy-B0tYRju5.js`，`CRT` = `app/src/main/assets/card-runtime.js`，`AC` = `app/src/main/java/app/xujing/AppController.kt`，`CL` = `core/src/main/kotlin/app/xujing/core/compat/CardLibrary.kt`。

| # | 接口 | PiuPiu 证据 | Xujing 证据 | 状态 | 备注 |
|---|---|---|---|---|---|
| 1 | `getPresetManager(name?)` | `CV:22641`（context 内）、`CV:24422`（window）、`CV:21800-21936`（返回对象本体） | `CRT:105-116`、`CRT:117`、`CRT:140` | 部分 | 两边都忽略 `apiId` 参数；返回对象的方法集不同（见 #11/#23/#24） |
| 2 | `getAllPresets()` | `CV:21814-21822` | `CRT:106`（= `getPresetNames`） | 语义不同 | PiuPiu 返回 `[当前预设名, ...全部名]`（当前名会重复）；Xujing 只返回库内名字 |
| 3 | `getPresetNames()` | `CV:22169-22171`（`["in_use"].concat(getAllPresets())`） | `CRT:86`、`CRT:106` | 语义不同 | PiuPiu 首项固定 `"in_use"` 且重复当前名；Xujing 不含 `"in_use"`、不重复 |
| 4 | `getLoadedPresetName()` | `CV:22644-22646`、`CV:23198-23200`、`CV:24411-24413` | `CRT:129` | 一致 | 都是同步返回当前名；PiuPiu 无选中时回退到内置名，Xujing 返回 `''` |
| 5 | `getPreset(name?)` | `CV:21937-21947`（`bo(Xl(...))`）、`CV:7058-7090`（转换器） | `CRT:95-99` | 语义不同 | PiuPiu 返回**转换形**（`prompts[].id/position`、`prompts_unused`、`settings`）；Xujing 返回**原始预设 JSON**（`prompts[].identifier/injection_position`）。两边都同步、失败抛错 |
| 6 | `getPresetSettings(name?)`（manager） | `CV:21864-21866`（`De(Xl(...))` 原始深拷贝） | `CRT:110`（`= getPreset`） | 部分 | 形状一致（都是原始 JSON），但 PiuPiu 只要当前预设已进内存即可用；Xujing 强制先 `loadPreset`（`CRT:95-99`） |
| 7 | `getSelectedPresetName()`（manager） | `CV:21848-21855` | `CRT:108` | 一致 | 都返回名字 |
| 8 | `getSelectedPreset()`（manager） | `CV:21856-21863` | `CRT:109` | 一致 | 两边都返回**名字字符串**，不是对象 |
| 9 | `getCompletionPresetByName(name)`（manager） | `CV:21867` → `CV:21793-21799` | `CRT:111` | 部分 | 都同步、都吞异常返回 `undefined`；PiuPiu 能解析当前已加载预设，Xujing 只查 `presetCache` |
| 10 | `findPreset(name)`（manager） | `CV:21868-21875` | `CRT:112` | 一致 | 都是「存在则回名字，否则 `undefined`」 |
| 11 | `getActivePresetId()`（manager / 脚本全局） | `CV:21877-21885`；iframe 全局见 `GMS:31033` 段 | `CRT:107`、`CRT:129` | 语义不同 | PiuPiu 返回**数字 id，找不到时回退成预设名字**；Xujing 返回**字符串 id 或 `null`** |
| 12 | `setActivePresetId(id)` | `CV:21886` → `CV:21698-21706` | `CRT:113` → `CRT:100-104` | 语义不同 | PiuPiu **同步返回布尔**；Xujing **异步**返回布尔。PiuPiu 传入「当前名」会**取消选中**（`CV:21700-21701`），Xujing 传空串才清空（`CL:113-114`） |
| 13 | `selectPreset(nameOrId)`（manager） | `CV:21876` → `CV:21698-21706` | `CRT:100-104` | 部分 | 同名不同步：PiuPiu 同步布尔、Xujing 异步布尔并派发 `preset_changed` |
| 14 | `loadPreset(name)` | `CV:22172-22174`（`du`，同步布尔）；`CV:24414`、`CV:23201`、`CV:22647` | `CRT:88-94`（异步、返回预设数据） | 语义不同 | PiuPiu 只是「选中」：`!(!e||"in_use"===e)&&$l(e)`，**不加载、不返回数据**；Xujing 走宿主请求并**返回 `data`** |
| 15 | `getPresetList()`（manager） | `CV:21823-21846` | `CRT:114` | 部分 | PiuPiu 返回 `{presets, preset_names, settings}`；Xujing 只有 `{presets, preset_names}`，且只列已缓存项 |
| 16 | `createPreset(name, data?, options?)` | `CV:22297-22318` → `CV:22188-22296` | 无 | 缺失 | Xujing 全仓无该符号（`grep createPreset` 零命中） |
| 17 | `createOrReplacePreset(name, data?, options?)` | `CV:22188-22296` | 无 | 缺失 | 同上 |
| 18 | `deletePreset(name)` | `CV:22319-22384` | 无 | 缺失 | 同上 |
| 19 | `renamePreset(old, new)` | `CV:22385-22441` | 无 | 缺失 | 同上 |
| 20 | `replacePreset(name, data, options)` | `CV:22037-22061` | 无 | 缺失 | 同上 |
| 21 | `updatePresetWith(name, fn, options)` | `CV:22062-22136` | 无 | 缺失 | 同上 |
| 22 | `setPreset(name, data, options)` | `CV:22137-22168` | 无 | 缺失 | 同上 |
| 23 | `savePreset(name, data?, options?)`（manager） | `CV:21887-21895` → `CV:22442-22561` | 无 | 缺失 | 同上 |
| 24 | `updatePreset(patch)`（manager） | `CV:21896-21909` | 无 | 缺失 | 只允许改本地预设，远端只读 |
| 25 | `preset_changed` 事件 | `CV:11347-11348`（`PRESET_CHANGED`，参数=**名字**） | `CRT:102`（参数=**activePresetId**） | 部分 | 两边都会发，但载荷不同；PiuPiu 只在**选中项变化**时发，改同一预设内容不发 |
| 26 | `oai_preset_changed_after` / `_before` | 常量 `GMS:28438-28439`；仅 `after` 在 `CV:11347` 派发，`before` 无派发点 | 无 | 缺失 | Xujing 不派发，也不在 `tavern_events` 里给常量 |
| 27 | `tavern_events.PRESET_*` 常量 | `GMS:28499-28502`（`PRESET_CHANGED/DELETED/RENAMED/RENAMED_BEFORE`） | `CRT:166` 无预设项 | 缺失 | 卡片写 `tavern_events.PRESET_CHANGED` 在 Xujing 得到 `undefined` |
| 28 | `chat_completion_prompt_ready`（提示词改写钩子） | `CV:27029-27042` | `AC:286-287` | 一致 | 都是 `{chat, dryRun:false}`，返回值里 `chat` 数组会覆盖 messages |
| 29 | `generate_after_data`（提示词改写钩子） | `CV:27044-27046`（只带 `prompt`）；脚本生成路径 `CV:15841-15847`（`prompt`+`messages`） | `AC:288-290`（`messages`，运行时补 `prompt` 别名） | 部分 | Xujing 是超集；纯 PiuPiu 主路径只保证 `prompt` |
| 30 | `generate_before_combine_prompts` | `CV:26830`（无参数） | 无 | 缺失 | Xujing 没有这个时点 |
| 31 | `SillyTavern.getContext().chatCompletionSettings` | `CV:22614`（`Ll()`） | `CRT:117-126` 未提供 | 缺失 | PiuPiu 会把预设展开后的 settings 挂进 context |

**统计：31 项 —— 一致 5、部分 7、缺失 13、语义不同 6。**
（一致：#4、#7、#8、#10、#28；部分：#1、#6、#9、#13、#15、#25、#29；缺失：#16–#24、#26、#27、#30、#31；语义不同：#2、#3、#5、#11、#12、#14。）

---

## 2. 调用契约

### 2.1 什么时候可用

| 侧 | 事实 | 证据 |
|---|---|---|
| PiuPiu `window.*` | 在 **ChatView `onMounted`** 里、`await Jt()`（数据装载）之后逐项赋值 | `CV:24365-24422` |
| PiuPiu `window.*` 生命周期 | 离开视图时**逐项比对后删除**（防止误删别人覆盖的同名函数） | `CV:9749-9779` |
| PiuPiu `TavernHelper` | 模块级常量对象 `Vu`，用 `Object.defineProperty(window,"TavernHelper",{get})` 暴露，不随路由删除 | `CV:23168`、`CV:24451-24458` |
| PiuPiu 脚本 iframe | bootstrap 里把 `window.SillyTavern.getPresetManager` 转成 `window.getPresetManager`，并补 `getAllPresets/getActivePresetId/setActivePresetId` 与全部 `TavernHelper.*` 转发 | `GMS:31033` 段（同一行内的引导脚本） |
| PiuPiu 兜底 | 拿不到父窗口预设管理器时，iframe 里返回一个**只含当前预设名**的假管理器 | `GMS` 31012 行内的 `getPresetManager` fallback |
| Xujing | `card-runtime.js` 在 iframe 加载时就把 `api` 全量挂到 `window`（`if (!(name in window))`），并设 `window.SillyTavern` | `CRT:127-140` |

结论：PiuPiu 的 `window.*` 是**页面级**能力，不在聊天页时不存在；`TavernHelper` 是**模块级**能力，但方法内部仍依赖 ChatView 的响应式状态。Xujing 是**iframe 级**，生命周期跟随卡片，不依赖路由。

### 2.2 同步 / 异步与失败形态

| 方法 | PiuPiu | Xujing |
|---|---|---|
| `getAllPresets` / `getPresetNames` / `getLoadedPresetName` / `getSelectedPresetName` / `getSelectedPreset` / `findPreset` / `getActivePresetId` | 同步 | 同步（`CRT:106-112`） |
| `getPreset` / `getPresetSettings` / `getCompletionPresetByName` | 同步；未加载/不存在 **抛 `Error`**（`CV:21790-21791`、`CV:21744-21746`），`getCompletionPresetByName` 自吞异常 | 同步；未加载 **抛 `Error('预设尚未加载，请先调用 loadPreset')`**（`CRT:97`），`getCompletionPresetByName` 自吞 |
| `loadPreset` | **同步**，返回布尔；`"in_use"`/空值直接 `false`（`CV:22172-22174`） | **异步**，成功返回 `data`，失败 reject（`CRT:88-94`） |
| `selectPreset` / `setActivePresetId` | **同步**布尔（`CV:21698-21706`） | **异步**布尔（`CRT:100-104`） |
| `createPreset` / `createOrReplacePreset` / `deletePreset` / `renamePreset` / `replacePreset` / `updatePresetWith` / `setPreset` / `savePreset` | 异步，成功时返回 `true`/`false`/`undefined` 不等（见各自行号） | 不存在 |
| `getPresetManager` | 同步，**每次调用新建一个对象** | 同步，返回**同一单例** |

失败形态差异是**最容易静默失效**的一类：PiuPiu 的 `loadPreset` 从不 reject，卡片写 `.catch(...)` 永远不触发；Xujing 的 `loadPreset` 会 reject，而 `getPreset` 会抛，卡片如果只 `try{...}catch{}` 会看不到任何东西。

### 2.3 作用域

- **PiuPiu：预设「选择」只有全局一份。** 选中项是 `Kt.activeGlobalPresetId`（全局 store），`selectPreset/setActivePresetId` 改的就是它（`CV:21698-21706`），不按角色/会话分别保存。生成前还会调用 `Pr(A, N)` 把第二份预设以 `_piupiu_source:"local"` 叠加到第一份上（调用点 `CV:25641-25648`；合并实现 `ImageViewer-legacy-DD5gJaNv.js:979-1085`，local 项带 `_piupiu_before_anchors/_piupiu_after_anchors`）。卡片**没有「为某个角色选预设」的接口**。
- **Xujing：会话级。** `session.presetId` 决定用哪个预设（`AC:91`、`CL:112-116`），脚本切换的是当前会话的预设。

---

## 3. 生成期编排

### 3.1 PiuPiu：`buildAsync` 实际怎么落 `messages`

调用点 `CV:27003-27023`，只传 `promptConfig`（= 合并后的预设，`CV:25641-25648`、`CV:26835`）等；返回值只有 `{messages}`（`CV:6412-6413`）。

**(a) `prompt_order` 的选取**

- `$ = Be(F)?.order || []`（`CV:5133-5136`）。
- `Be` = `IDX` 导出的 `Vi`（`IDX:5967-5979`）：**优先 `character_id === "100001"`**，否则第一条 `order` 非空的，否则第一条。
- 逐项：`e.enabled` 为真、能在 `F.prompts` 里按 `identifier` 找到、`injection_trigger` 与 `generationType` 匹配（`CV:5143-5148`）、`hr(prompt)` 通过（`ImageViewer:1133-1135`：本地提示词或 `enabled !== false`）。
- 额外按 identifier 过滤：`utility_statusBar`（状态栏未启用则丢）、`utility_userStatusBar`、`image_gen_prompt`、`local_image_gen_prompt`、群聊下的 `charDescription/dialogueExamples`、非群聊下的 `group_base` 等（`CV:5150-5186`）。
- **注意**：`e.enabled` 用的是真值判断，`prompt_order` 项缺 `enabled` 会被**丢掉**（`CV:5139`）；Xujing 用默认 `true`。

**(b) 注入位置分类**

`CV:5373-5382`：

```
injection_position === 1        → ee  （追加到消息数组末尾）
injection_position === 0 且 depth>0 → Q （按 depth 插入历史）
其余（position 0、depth 0）      → X  （按 prompt_order 顺序进顶部块）
local(_piupiu_source) 同理：position1 → re，否则 → ne
```

- 顶部块 `X` 会再按 `injection_order` 升序排一次（`CV:5433-5439`）；`ee` 也按 `injection_order` 排（`CV:5440`）；`Q` 先按 `depth` 升序再按 `injection_order` 升序（`CV:5441-5447`）。
- `Z` 是**世界书 depth 条目**（`C.depthEntries`，`CV:5384-5421`）与作者注（`CV:5960-5982`，depth 默认 4、order 0），它们走 `Q` 同一条深度通道。

**(c) 消息数组的拼装顺序**

1. `X` 逐条 push 进 `cn`（`CV:5912-5946`）；`worldInfoBefore/After`、`dialogueExamples` 在这里被替换成世界书/示例文本。
2. `chatHistory` 标记**只标记、不改位置**：`CV:5930-5931` 里 `return void vn("chatHistory")`，而 `vn` 明确对 `"chatHistory"` 不做事（`CV:5908-5910`）。
3. `M`（反拒答 jailbreak）在 X 段之后 push（`CV:5947-5951`）。
4. 历史 `bn`：取最近 `P` 条（默认 40，`CV:5110`），用 `_.slice(-P)` 再 `reverse()` 成**新→旧**（`CV:5983-5984`），逐条宏替换后 push（`CV:5987-6077`）。
5. 深度注入：收集 `Sn = Q + re + Z` 的所有 `injection_depth` 去重升序（`CV:6082-6105`），对每个 depth 把同 depth 的内容按 `injection_order` 降序、按 `system/user/assistant` 顺序合并成一条，`bn.splice(min(bn.length, depth+已插入数), 0, ...)`（`CV:6280-6289`）。因为 `bn` 此时是**新→旧**，`depth=0` 落在最新一条之前，`reverse()` 之后就是**消息数组最末**——与 SillyTavern 的 depth 语义（0=最后）方向一致。
6. `bn.reverse()` 变回**旧→新**（`CV:6318`）。
7. `storySummary` 先 push（`CV:6322-6328`），历史再 `cn.push(...Nn)`（`CV:6329`）。
8. **`ee`（position=1）在此刻才 push**（`CV:6332-6346`）——所以无论 `injection_depth` 是多少，position=1 的提示词都落在**整段消息的最后**。

**(d) 采样参数**

- `buildAsync` 不产出采样参数，只产出 messages。
- 主聊天请求写死：`temperature: 1.1, maxTokens: 8192`（`CV:27082-27087`），落到请求体 `temperature / max_tokens`（`CV:6793-6805`）。
- 预设里的 `temp_openai/temperature/top_p/...` 只在 `ho()` 里被**读取成返回对象**（`CV:6984-7056`），供 `getPresetSettings`/`getPreset` 的 `settings` 字段使用（`CV:7089`），从未进入请求。
- 唯一的例外是脚本自定义 API 路径：`xe.temperature/max_tokens/...` 来自用户的自定义接口配置，不是预设（`CV:15875-15887`、`CV:16169-16183`）。

### 3.2 Xujing：`PromptEngine.build`

- 预设来源：`state.library` 里 `enabled && kind=="preset" && id==session.presetId`（`PromptEngine.kt:27-28`）。
- `prompt_order` 选取：先找 `character_id == 当前角色 id`，再找 `"100001"`，再取第一条（`PromptEngine.kt:111-114`）；为空时**回退为「所有 enabled 提示词」**，再为空才用内置块顺序（`PromptEngine.kt:116-118`）。
- 逐项：`enabled` 默认 `true`；`injection_trigger` 为空或包含当前 mode / `all` 才保留（`PromptEngine.kt:126-130`）。
- `chatHistory` 标记**真的决定分界**：`historyReached=true` 后，后续相对提示词进 `assembledAfter`（`PromptEngine.kt:131`、`141`）。
- `injection_position == 1` → 进 `injections`（depth 默认 4、order 默认 100），**按 depth 插入历史**（`PromptEngine.kt:138-139`、`168-176`）。
- position 0 → 忽略 depth，按 `prompt_order` 放 before/after（`PromptEngine.kt:140-144`）。
- 注入下标：按 `depth` **降序**处理，`index = history.size - depth`（`PromptEngine.kt:171-173`），与 ST 的 0=最后 一致。
- 最终 `messages = assembledBefore + injectedHistory + assembledAfter`（`PromptEngine.kt:176`）。
- 预设里的 `maxHistoryMessages` 可作为历史条数默认值（`PromptEngine.kt:90`）。
- 采样：仅当 `session.extra.applyPresetSampling == true` 时，用预设覆盖连接的 `temperature/top_p/frequency_penalty/presence_penalty/max_tokens/seed/stop`（`GenerationSettings.kt:8-59`，开关 UI 在 `ui/SessionSettings.kt:67-68`）；否则完全用连接参数。

### 3.3 双方生成期差异（逐项）

| 点 | PiuPiu | Xujing |
|---|---|---|
| `prompt_order` 首选项 | 永远优先 `100001`（`IDX:5970-5972`） | 先当前角色 id，再 `100001`（`PromptEngine.kt:113`） |
| `enabled` 缺省 | 视为**关闭**（`CV:5139`） | 视为**开启**（`PromptEngine.kt:127`） |
| 顺序 | 顶部块再按 `injection_order` 重排（`CV:5439`） | 完全按 `prompt_order`（只有深度注入按 order 排） |
| `chatHistory` 标记 | **位置被忽略**，历史固定跟在顶部块+摘要之后（`CV:5930`、`6329`） | **按标记分界**（`PromptEngine.kt:131`、`141`、`176`） |
| `injection_position=1` | 追加到**消息末尾**（`CV:6332-6346`），depth 不生效 | 按 depth 插入历史（`PromptEngine.kt:138-139`、`171-173`） |
| `position=0 且 depth>0` | 按 depth 插入历史（`CV:5375-5376`） | 忽略 depth，走 prompt_order（`PromptEngine.kt:140`） |
| 空 `prompt_order` | 顶部块为空，只剩摘要/历史（`CV:5138` 循环不执行） | 回退到全部 enabled 提示词（`PromptEngine.kt:117`） |
| 角色级提示词叠加 | 有 `_piupiu_source:"local"` + 前后锚点（`ImageViewer:1044-1079`） | 无此机制 |
| 预设采样 | **不生效**（`CV:27082-27087`） | 会话开关开启时生效（`GenerationSettings.kt:14`） |
| 摘要/记忆/变量/文风 | 各自固定插入点（`CV:6319-6329`、`CV:6325`） | 未出现在 `prompt_order` 时补在 before 段（`PromptEngine.kt:148-151`） |
| `TavernHelper.generate({ordered_prompts})` | 支持字符串标识符 + 对象 + `injects`（深度）（`CV:15629-15804`） | 只接受带 `content` 的对象，字符串被丢弃，`injects` 被忽略，空则回退整段提示词（`AC:632-640`） |

---

## 4. 会导致原生卡「静默失效」的差异（按影响排序）

1. **预设写接口整体缺失（#16–#24）。**
   带「预设管理 / 自动改提示词 / 克隆预设」逻辑的卡会直接 `TypeError: createPreset is not a function`。因为大多数卡把这些调用包在按钮回调或 `try/catch` 里，用户看到的是「按钮没反应」。
   证据：PiuPiu `CV:22037-22561`、`CV:22297-22318`；Xujing 全仓无符号。

2. **`getPreset` / `getPresetSettings` 多了一道「必须先 `loadPreset`」的门槛，且 `getPreset` 形状不同。**
   - PiuPiu 打开聊天时会把当前预设装进 `Zt.value`（`CV:11215-11315`），所以 `getPresetManager().getPresetSettings()` / `TavernHelper.getPreset()` 通常**不用先 load** 就能拿到内容。
   - Xujing 的 `getPreset` 只查 `presetCache`，缓存只在 `loadPreset` 成功时写入（`CRT:88-99`）。同一段卡在 Xujing 会抛 `预设尚未加载`。
   - 就算 load 了，Xujing 返回原始 JSON（`identifier` / `injection_position`），PiuPiu 的 `TavernHelper.getPreset()` 返回 `{settings, prompts:[{id, position:{type,depth,order}}], prompts_unused, extensions}`（`CV:7058-7090`、`CV:21937-21947`）。读 `prompt.identifier` 的卡在 PiuPiu 是 `undefined`，读 `prompt.id` 的卡在 Xujing 是 `undefined`——两边都会「读到空值但不报错」。

3. **`loadPreset` / `selectPreset` / `setActivePresetId` 的同步-异步契约相反。**
   PiuPiu 是同步布尔（`CV:22172-22174`、`CV:21698-21706`），Xujing 是异步 Promise（`CRT:88-104`）。
   - `if (!loadPreset(name)) return;`：PiuPiu 会按选中结果正常分支；Xujing 拿到的是 Promise（恒真值），`!promise` 为 `false`，**不会 return**，而且此时数据还没加载完。
   - `loadPreset(name); getPreset(name);`（不 await）：PiuPiu 同步完成，能读到；Xujing 立即读缓存会抛「预设尚未加载」。
   - `const r = await loadPreset(name)`：PiuPiu 得到 `boolean`，Xujing 得到预设对象——同一行卡片的后续字段读取会分叉。
   这是**最难排查**的一类：不抛错、类型也对不上。

4. **`getPresetNames()` / `getAllPresets()` 的集合语义不同。**
   PiuPiu 首项是 `"in_use"`，且当前预设名会重复出现（`CV:22169-22171`、`CV:21814-21822`）。Xujing 只列库内名字（`CRT:86`）。
   卡若用 `getPresetNames().indexOf('in_use')` 判存在、或用 `names.length` 做 UI 列表，行为会变；`loadPreset('in_use')` 本身两边都能工作（`CRT:89`、`CV:21770`），所以只有「读列表」的卡受影响。

5. **`preset_changed` 载荷不同 + `tavern_events` 缺常量。**
   PiuPiu 派发 `(name)`，并且同时派发 `oai_preset_changed_after`（`CV:11347-11348`）；Xujing 派发 `(id)`（`CRT:102`）。卡的 `eventOn(tavern_events.PRESET_CHANGED, ...)` 在 Xujing 里拿到 `undefined` 作为事件名（`CRT:166` 无该项），监听会被注册到字符串 `"undefined"` 上，**永远不触发**。
   `PRESET_DELETED` / `PRESET_RENAMED` 在 PiuPiu 里也只有常量没有派发（全量 grep 只命中声明行），所以这一条两边都是「声明了但没人发」，属于**同等缺失**，不额外扣分。

6. **`injection_position=1` 的落点不同（影响预设内容位置，不是接口层）。**
   PiuPiu 把它追加到消息末尾（`CV:6332`），Xujing 按 depth 插入（`PromptEngine.kt:138-139`）。用 in-chat 深度的预设（作者注、越狱、状态栏）在两边会落在不同深度；如果卡片靠「深度 4 的指令」生效，表现会不同但不报错。

7. **`position=0 + depth>0` 的落点相反。**
   PiuPiu 的「新增提示词」默认值就是 `position:0, depth:4`（`IDX:1508-1510`），这类条目在 PiuPiu 会按深度插进历史；Xujing 则按 prompt_order 放在顶部。任何从 PiuPiu 导出、含此类默认条目的预设，迁移后位置会变。

8. **`getActivePresetId` 的类型不同。**
   PiuPiu 返回数字 id，找不到时回退成名字字符串（`CV:21877-21885`）；Xujing 返回字符串 id 或 `null`（`CRT:107`）。做 `===` 比较或把返回值当数字用的卡会失配；这也是 #12 传参问题的来源。

9. **`chatCompletionSettings` 缺失。**
   PiuPiu 在 `getContext()` 里挂了预设展开后的 `chatCompletionSettings`（`CV:22614`），Xujing 的 `getContext()` 没有（`CRT:117-126`）。读 `SillyTavern.getContext().chatCompletionSettings.prompts` 的卡会在 Xujing 得到 `undefined` 后继续往下读 → `TypeError`。

10. **`generate_before_combine_prompts` 缺失 + `generate_after_data` 参数形状偏窄。**
    PiuPiu 在组装前派发一个无参事件（`CV:26830`），主生成路径的 `generate_after_data` 只带 `prompt`（`CV:27044-27046`）；Xujing 覆盖了后者（并在运行时补 `prompt` 别名，`CRT:156-157`、`AC:288`），但没有前者。只监听 `prompt` 的卡两边都能工作；监听 `messages` 的卡在 PiuPiu 主路径反而拿不到——这是 Xujing 比 PiuPiu 更宽的一处，不算风险，但要注意别把它当「已对齐」。

---

## 5. 未验证项

1. `Hl()`（`CV:21687-21690`）里 `se()` 的解析目标。同名 `se` 在文件里有多处绑定（导入 `e.bv`、`CV:706`；组件内的 computed `CV:3275`），没有追到 `Hl` 所在闭包实际用的是哪一个，因此「无预设时回退到 `PiuPiu默认预设`」的触发条件**未验证**。
2. 运行期实际发出的 `messages` 数组（没有执行前端，也没有抓请求体）。第 3 节全部是静态推演。
3. PiuPiu 内置默认全局预设（`defaultGlobalPreset-legacy-tjZgE48x.js`）是否真的含 `injection_position:1` 的条目——只确认了「新增提示词」默认值是 `position:0/depth:4`（`IDX:1508-1510`），没有通读内置预设文本。
4. 普通消息的 `HtmlSandbox`（`GMS:33327`）里是否也能拿到 `window.getPreset*`。目前只确认**角色脚本 iframe**（`GMS:30786-31921` 段）有 bootstrap，未逐条确认消息沙箱。
5. Xujing `context.presets` 的 revision 失效逻辑在长会话、预设被后台修改后的实际表现（`CRT:184-189`）——只读代码，未运行。
6. PiuPiu 的「转换形」预设（`bo()`）与 SillyTavern 原生 `PresetManager` 返回形状的对应关系——没有对照 ST 源码，因此 #5 的「语义不同」只敢说「Xujing 与 PiuPiu 不同」，不敢说「谁更像原版」。
7. `PRESET_DELETED` / `PRESET_RENAMED` 是否还有动态拼接的派发点（已全量 grep 字符串与 `gn.` 前缀，零命中，但静态 grep 不能排除运行时拼串）。
8. Xujing 预设采样合并后的最终请求体（`GenerationSettings.kt` 只读，未跑 `ApiClient`）。

---

## 6. 复核命令

```powershell
# PiuPiu：预设管理对象与 window 安装点
Select-String -Path 'D:\Canxu-project\PiuPiu-source\frontend-readable\assets\c\ChatView-legacy-C8UPOooP.js' -Pattern 'getPresetManager|getAllPresets|loadPreset' -AllMatches
# PiuPiu：注入分类与消息拼装
Select-String -Path '...\ChatView-legacy-C8UPOooP.js' -Pattern 'injection_position|injection_depth|injection_order'
# Xujing：预设接口面
Select-String -Path 'D:\Canxu-project\Xujing\app\src\main\assets\card-runtime.js' -Pattern 'preset'
Select-String -Path 'D:\Canxu-project\Xujing\core\src\main\kotlin\app\xujing\core\engine\PromptEngine.kt' -Pattern 'prompt_order|injection_position|injection_depth'
```
