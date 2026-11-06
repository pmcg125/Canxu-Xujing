# 预设（preset）生成期能力与兼容细节对照

日期：2026-11-05。范围：预设级正则、预设级脚本、`getPresetList` 形状、写接口返回值、预设管理器入口、预设变量作用域、内置/远端预设合并。
性质：**只读静态对照**。未构建、未运行、未改任何源码；本文是下一轮实现的施工图。

补位关系：`preset-api.md` 已给出接口总表与生成期编排，`preset-editor.md` 已给出存储/归一化/编辑 UI，`core-gaps.md` 已给出 22 条引擎缺口。本文只写这三份**没有覆盖**的 7 项，不重复其结论。

## 0. 证据前缀

| 缩写 | 文件 |
|---|---|
| CV | `PiuPiu-source/frontend-readable/assets/c/ChatView-legacy-C8UPOooP.js` |
| IDX | `.../assets/e/index-legacy-B0tYRju5.js` |
| IV | `.../assets/c/ImageViewer-legacy-DD5gJaNv.js` |
| TH | `.../assets/c/tavernHelperScriptStorage-legacy-BVIVfja_.js` |
| GMS | `.../assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js` |
| DGP | `.../assets/c/defaultGlobalPreset-legacy-tjZgE48x.js` |
| CRT | `Xujing/app/src/main/assets/card-runtime.js` |
| AC | `Xujing/app/src/main/java/app/xujing/AppController.kt` |
| RP | `Xujing/core/src/main/kotlin/app/xujing/core/engine/RegexProcessor.kt` |
| SS | `Xujing/core/src/main/kotlin/app/xujing/core/compat/SessionScripts.kt` |
| SSR | `Xujing/app/src/main/java/app/xujing/runtime/SessionScriptRuntime.kt` |
| CVR | `Xujing/core/src/main/kotlin/app/xujing/core/compat/CardVariables.kt` |

---

## 1. 结论摘要

| # | 项目 | PiuPiu 行为 | 残叙现状 | 建议改法 | 优先级 |
|---|---|---|---|---|---|
| 1 | 预设正则 `extensions.regex_scripts` | **只**取当前预设（`Nr(Zt.value.data)`），执行顺序 global→preset→role，`Ke` 内统一处理 `disabled`/`placement`/`depth`/`runOnEdit` | `RP:15-24` 只收 `kind=="regex"` 库文档 + 角色卡 | `RP` 的 `buildList` 增加「当前预设」段，放在库正则之后、角色卡之前 | P0 |
| 2 | 预设脚本 `extensions.tavern_helper.scripts` | 当前预设是**第 N 个** runtime source，`scriptSource:"preset"`，`runtimeRoleId=-(1e9+presetId)`，排在角色之后 | `SS:29-40` 只遍历 `session.roleIds` | `SS.collect` 追加预设段，`owner="preset:<presetId>"`；`SSR:75` 的 `remember` 键加预设 | P0 |
| 3 | `getPresetList()` 形状 | `presets` = **预设数据数组**；`preset_names` = **对象（名字→下标）**；`settings` = `Ll()` | `CRT:190-193` 与 `AC:701-706` 都是 `{id,name}` + 数组 | 宿主返回 `data` + 对象；JS 另行组装 `settings`；**保留独立的“名字列表”缓存** | P0 |
| 4 | 写接口返回值 | `savePreset`/`replacePreset`/`updatePreset` → `undefined`；`setPreset`/`updatePresetWith` → 对象；`createPreset` 重名 → `false`；空名/内置 → `throw` | 宿主全 `{value:true}`；JS `presetMutate` 失败一律 `false` | 在 JS 侧按方法翻译返回值 + 名字/内置校验；宿主补返回文档 | P0 |
| 5 | 打开预设管理器 | 6 个别名全部 `() => { Et.value = true }`，同步返回 `undefined` | 无入口 | `openPresetManager` RPC + `UiRuntime` 标志 + 导航；或降级只返回 `true` | P1 |
| 6 | 预设变量作用域 | `getVariables({type:'preset'})`，localStorage 单桶 `piupiu_tavern_helper_preset_variables`，**不按预设名分桶** | `CRT:36-42`、`CVR:19-23` 只有 `chat`/`message` | 加 `preset` scope，存 `AppState.compatibility`，两个 snapshot 透出 | P1 |
| 7 | 内置只读预设 + 远端/本地合并 | 内置 `se()` 作为 **remote 基座**，选中预设作为 **local** 叠加，带 anchors 与 `piupiu-local:` 改名 | 只用选中预设，无基座、无 anchors | 本轮不做；需先有“基座预设”来源 | P2 |

---

## 2. 逐节详述

### 2.1 预设级正则 `extensions.regex_scripts`

#### PiuPiu 证据

| 事实 | 证据 |
|---|---|
| 预设正则的唯一真身是 `root_json.extensions.regex_scripts` | IDX:25482-25496（`json_each(p.root_json,'$.extensions.regex_scripts')`） |
| `scope_type='preset'` 的两张表只是可重建索引 | IDX:25419-25510（`lv/cv`）；以 `regex_configs.updated_at == preset_configs.updated_at` 判定已同步，否则整表重建 |
| 索引重建只在**读取编辑器列表/详情**前触发 | IDX:25523、25568（`preset-editor.md:140`） |
| 执行期从内存预设取，不走索引表 | IV:11805-11818（`ji`）+ CV:850（`Nr = e.g`） |
| 只对**当前选中预设**生效 | CV:11237（清空 `Qt.value=[]`）、CV:11308（active 变化时 `Qt.value=Nr(s)`）、CV:25638（生成前 `Qt.value=Nr(N)`） |
| 预设正则与普通正则**同一套执行器、同一套 stage** | CV:19187-19204（`bs`）、CV:19495-19517（`Ms` 显示路径）、CV:19146-19163（`gs`） |
| 应用顺序：全局正则 → 预设正则 → 角色正则 | CV:19191/19198（global）、CV:19198-19204（`Qt`）、CV:19205（`hs` 角色） |
| `placement` 由调用点决定：assistant=2、user=1 | CV:19188（`i = "assistant"===n ? 2 : 1`）；IDX:6911-6915（`1/2/3/5/6` 枚举） |
| 无“只对 assistant 输出”的额外过滤 | CV:19198-19204 两个 role 都套；显示路径 CV:19504 也套 |
| `disabled`/空 `findRegex` 在引擎内跳过 | IDX:1024-1032 |
| `markdownOnly`/`promptOnly`/`runOnEdit`/`minDepth`/`maxDepth` 与普通正则同规则 | IDX:1045-1125 |
| 切换预设**不需要重建任何索引** | `Qt` 是内存数组；`ensurePresetRegexIndexed` 只服务编辑器 SQL，不参与生成 |

关键实现（IV:11805-11818，注意它按 `extensions` 取、按 `Oe` 归一、null 过滤）：

```js
function ji(e) {
  var a = e?.extensions;
  if (!a || "object" !== p(a) || Array.isArray(a)) return [];
  var t = a.regex_scripts;
  return Array.isArray(t) ? t.map(Oe).filter(e => null !== e) : [];
}
```

#### 残叙现状

`RP:15-24`：只收集 `state.library` 中 `kind=="regex"` 且被会话选中/全局的文档，以及 `session.roleIds` 对应角色卡的 `extensions.regex_scripts`。**预设文档完全没进集合**。
`RP:26-42` 的 `stage`/`placement` 映射已经覆盖 PiuPiu 的全部值（`INPUT=1/OUTPUT=2/WORLDINFO=5/DISPLAY=按 role`），预设条目直接复用即可，不需要新增枚举。
`RP:24` 的 `distinctBy(id+findRegex+replaceString)` 不含来源，新增预设段后可能把「预设里与角色卡完全相同的一条」吞掉（PiuPiu 会执行两次）。

#### 实现清单

| 文件 | 函数 | 改什么 |
|---|---|---|
| `core/.../engine/RegexProcessor.kt` | 属性 `rules`（:15-24） | 在库正则之后、`roles.forEach` 之前插入当前预设段 |
| 同上 | `rules` 元素类型 | 由 `JsonObject` 改为携带来源标签的 `Pair<String,JsonObject>`（或 `data class Rule(origin:String, json:JsonObject)`），`distinctBy` 用 `origin + id + findRegex + replaceString` |
| 同上 | `apply`（:28） | 无需改；`rule` 取 `.second` 即可 |
| `core/.../engine/PromptEngine.kt` | `:27-28` 的 `presetDocument` | 可复用同一取法（`enabled && kind=="preset" && id==session.presetId`），避免两处判断漂移 |

建议片段（放在 `RP:17` 之后）：

```kotlin
state.library.filter { it.enabled && it.kind == "preset" && it.id == session.presetId }
    .forEach { doc ->
        val ext = unwrap(doc.data as? JsonObject ?: EmptyObject)["extensions"].objectOrEmpty()
        records(ext["regex_scripts"], "scripts", "regex_scripts")
            .forEach { (_, rule) -> add("preset" to rule) }
    }
```

要点：
- 必须带 `it.enabled`（与 `PromptEngine.kt:27` 一致）；PiuPiu 没有预设级 enabled，这是残叙自己的开关。
- 用 `unwrap` 对齐 `PromptEngine.kt:28` 的取法，兼容 `{data:{...}}` 包装的导入预设。
- **不要**给预设正则加 role/scope 过滤；`scope_type='preset'` 在 PiuPiu 执行期不存在。
- 不需要任何“重建索引”逻辑；`RegexProcessor` 每次 `PromptEngine.build/processOutput/processDisplay` 都新建（`PromptEngine.kt:42/186/200/209`）。
- 若一个预设同时被多个会话选中，各自 build 各取一次，无共享状态。

#### 风险与回归点

| 风险 | 说明 |
|---|---|
| 双重执行 | 若角色卡与预设里存在同一条（同 id+find+replace），改去重键前会被吞，改后会被执行两次——PiuPiu 就是两次。 |
| 非 PiuPiu 原生形状 | 若预设是 ST 原始形状（`find_regex`/`source`/`destination`），`RP:39-43` 读不到 `placement`/`findRegex`，会静默跳过。属既有导入缺口，不在本节修。 |
| 诊断文本 | `RP:53/80` 的 diagnostics 会新增预设条目，`PromptBuild.diagnostics` 长度变化可能影响 UI 预览断言（无自动化测试覆盖）。 |

---

### 2.2 预设级脚本 `extensions.tavern_helper.scripts`

#### PiuPiu 证据

| 事实 | 证据 |
|---|---|
| 预设脚本抽取器 = `Lr`（`e.c`），按 `tavern_helper.scripts` 优先、`TavernHelper_scripts` 回退，按 `id\0content` 去重 | TH:357-383；CV:867（`Lr = e.c`） |
| 脚本树变化检测用 `ca = JSON.stringify(Lr(data))` | CV:10668-10670、CV:22026-22029、CV:11328-11333 |
| 当前预设作为**独立 runtime source** 追加在角色之后 | CV:18923-18945（先 `l.map` 角色，再 `g && b.push({scriptSource:"preset", ...})`） |
| 预设 source 的 `data` 是整份预设（脚本仍在 `data.extensions` 内） | CV:18944（`data: g.data`）；CV:18962-18971（`characterData: n.data`） |
| 预设 source 的 runtime id 是**负数命名空间** | CV:18720-18722（`-(1e9 + max(0,trunc(id)))`）；CV:18938（`runtimeRoleId: cs(g.id)`） |
| `hostRoleId` 仍取当前角色 | CV:18939（`hostRoleId: Number(n.id)`） |
| 预设脚本若未启用 folder 则整棵跳过 | TH:377-383（`pe(...,!0,...)`）；`SS:17` 同语义 |
| `characterId`/角色名来自当前角色 | CV:18968-18969; CV:18971（`hostApi: ss(n.hostRoleId)`） |
| 切换/保存预设会**销毁并重建全部脚本 runtime** | CV:11326-11350（`ds` 调度）；CV:18723-18815（`ds→us→ps`）；CV:22025-22029（保存后脚本树变化也 `ds`） |
| 按钮参与全局聚合 | CV:24231-24261（`getAllEnabledScriptButtons` 遍历 `L.value`，`button_id = "<scriptId>:<buttonName>"`） |
| 按钮事件按 scriptId 命名，不按 runtimeRoleId | 同 CV:24249-24252 |

#### 残叙现状

`SS:10-43`：只遍历 `state.roles.filter { it.id in session.roleIds }`，取 `extensions.tavern_helper.scripts`（或旧 `TavernHelper_scripts`），`owner=role.id`，`id="$owner:${record.id|key}"`。
`SSR:75` 的 `remember(state.roles, session.roleIds, session.extra["disabledSessionScriptIds"])` **不含预设**——即使 `SS` 补了预设，预设内容变更（`state.library` 新对象）也不会重建引擎，除非恰好 roleIds/roles 变化。
`SSR:104` 的 snapshot 有 `activePresetId` 但没有预设脚本数据；`CardMessage.kt:84-89` 同样。
`SessionScript.id` 的全部消费点：`SSR:119/145/218`（trust/untrust）、`session-runtime.js:10/21/34/52/75`（iframe token、按钮事件名 `scriptId + '_' + hash(name)`、广播 `sourceToken`）、`SSR:190`（`__xujingRunButton`）。**没有落库字段直接存 `SessionScript.id`**，唯一持久化的是 `session.extra.disabledSessionScriptIds`（`SS:41`）与 UI 列表（`SessionSettings.kt:54-59`）。

#### 实现清单

| 文件 | 函数 | 改什么 |
|---|---|---|
| `core/.../compat/SessionScripts.kt` | `collect`（:29-40） | 在角色遍历之后追加当前预设段；`owner="preset:${preset.id}"`；`prefix="preset"` |
| 同上 | `collect` 尾部（:42） | 保持 `distinctBy { id to content }`（owner 前缀不同，不会误吞角色脚本） |
| `app/.../runtime/SessionScriptRuntime.kt` | `remember`（:75） | 加 `session.presetId` 与被选预设文档（或 `state.library`）作为 key |
| `app/.../runtime/SessionScriptRuntime.kt` | `update`/`snapshot`（:98-112、:211-214） | 无需改；只要 `scripts` 列表变化即重建 engine |
| （可选）`app/.../runtime/SessionScriptRuntime.kt` | `start`（:115-119） | 无需改；preset 脚本与角色脚本同属 `expectedScripts` |

建议片段（放在 `SS:40` 之后）：

```kotlin
state.library.firstOrNull { it.enabled && it.kind == "preset" && it.id == session.presetId }?.let { preset ->
    val extensions = unwrap(preset.data as? JsonObject ?: EmptyObject)["extensions"].objectOrEmpty()
    val modern = extensions["tavern_helper"]
    val helper = modern as? JsonObject ?: (modern as? JsonArray)?.mapNotNull { item ->
        val pair = item as? JsonArray ?: return@mapNotNull null
        (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull to (pair.getOrNull(1) ?: JsonNull)
    }?.toMap()?.let(::JsonObject)
    visit(if(extensions.containsKey("tavern_helper")) helper?.get("scripts") else extensions["TavernHelper_scripts"],
        true, "preset", "preset:${preset.id}")
}
```

要点与判断：
- **顺序**：PiuPiu 是角色在前、预设在后（CV:18923-18945），照抄；`SessionHooks.configureScripts`（SSR:45）保证按钮按该顺序排。
- **owner**：`preset:<presetId>` 足够；不要用预设名（会随改名失效）。完整 id 形如 `preset:<id>:<recordId>`，与现有 `roleId:id` 风格一致。
- **`distinctBy`**：不会误吞，因为 id 带 owner；但**同名同内容的预设脚本与角色脚本仍各跑一次**（与 PiuPiu 一致）。同一预设内两条显式同 id 的脚本会互相覆盖（既有行为，未变差）。
- **`disabledSessionScriptIds`**：继续生效，过滤在 :42；用户在 `SessionSettings` 里看到的是新 id，会新增两条记录。
- **`_piupiu_source`**：只写在 prompt 上（IV:984/1061），预设脚本读取的是同一份 `extensions`，merge 不改 `extensions`；不影响。
- **按钮/事件**：`scriptId` 进入 `buttonEvent(name)=scriptId+'_'+hash(name)`（session-script-runtime.js:21），前缀含 `:` 合法；iframe 内 `getScriptId/getButtonEvent` 返回新 id，与宿主 `SessionHooks` 一致。**不会破坏已存数据**，因为按钮事件名每次运行时重算。
- **PiuPiu 的负 runtime id 不需要复刻**：残叙每个脚本一个 iframe（session-runtime.js:22-39），id 只在本进程内使用；用字符串前缀即可，避免负数与现有 `Number` 假设冲突。
- `SessionScripts.collect(..., includeDisabled=true)` 被 `SessionSettings.kt:54/59` 用于“显示全部并逐项开关”，补预设后 UI 自动出现预设脚本，无需额外改动。

#### 风险与回归点

| 风险 | 说明 |
|---|---|
| `remember` 漏键 | 只加 `SS` 不改 `SSR:75`，保存预设脚本后必须切会话才生效。 |
| 禁用列表迁移 | 已存的 `disabledSessionScriptIds` 只含角色脚本 id；新增预设 id 默认启用，符合预期。 |
| 两个同名脚本 | `SessionHooks.updateButtons` 以 `scriptId` 为 key（SSR:50），若同一预设内两条脚本显式同 id，后者按钮覆盖前者（既有）。 |
| 按钮事件与角色脚本隔离 | 事件名带预设前缀，不会与角色脚本同名按钮串线；但卡若硬编码 `getScriptId()` 的旧字符串会看到新值（本来也不保证稳定）。 |
| `scripts` 为空即返回 | `SSR:76` 在 `scripts.isEmpty()` 时直接 return；若角色无脚本但预设有一堆脚本，补完后会开始启动 WebView，属预期但仍需回归。 |

---

### 2.3 `getPresetList()` 形状

#### PiuPiu 证据

`CV:21823-21846`：

```js
getPresetList: function () {
  var n = [e].concat(t(Kt.globalPresets.map(x => x.name)))
    .flatMap(function (e) { var n = Ql(e); return n ? [{name:e, preset:n}] : []; });
  return {
    presets: n.map(x => x.preset),
    preset_names: Object.fromEntries(n.map((x, i) => [x.name, i])),
    settings: Ll(),
  };
}
```

| 事实 | 证据 |
|---|---|
| `presets` 是**预设数据数组**（`Ql` = `De(Xl(name))`，原始 PiuPiu 形状，含 `prompts`/`prompt_order`/`extensions`） | CV:21793-21799、CV:21837-21839 |
| 名单首项是内置只读名 `Hl()`，其后是全部 `globalPresets` 名（当前名会重复） | CV:21814-21822、CV:21687-21690 |
| 取不到数据的名字被**丢弃**（不会占位） | CV:21832-21835 |
| `preset_names` 是**对象**，值为在 `presets` 中的下标 | CV:21840-21844 |
| `settings` 是 `Ll()`，同一个可变对象 `Pl`（模型/流式/采样/`preset_settings_openai`） | CV:21339-21398、CV:21845 |
| 同步返回，不抛错 | CV:21823 |

#### 残叙现状

- `CRT:186-189`：`refreshPresetList()` 把宿主返回的 `result.presets` 直接当“名字/引用列表”缓存。
- `CRT:190-193`：`getPresetList()` 返回 `{presets: [{id,name}...], preset_names: [名字...]}`。
- `CRT:91-95`：`getAllPresets()` 依赖 `presetListCache` 做 `.map(p=>p.name)`；测试 `tools/test-card-modules.cjs:98` 断言了 `getAllPresets()` 的值。
- `AC:701-706`：宿主返回 `{presets:[{id,name}], preset_names:[...]}`，不含数据、不含 `settings`。

#### 实现清单

| 文件 | 函数 | 改什么 |
|---|---|---|
| `app/.../AppController.kt` | `cardRequest` 的 `"getPresetList"` 分支（:701-706） | `presets` 改为 `CardLibrary.presets(state.value).map { it.data }`；`preset_names` 改为 `buildJsonObject { forEachIndexed { i,p -> put(p.name, i) } }` |
| `app/src/main/assets/card-runtime.js` | `refreshPresetList`（:186-189） | 把 `result.presets` 存到新的 `presetListData`；另从 `result.preset_names` 派生 `presetListNames`，**不要**再写 `presetListCache` 供名字用 |
| 同上 | `getPresetList`（:190-193） | 返回 `{presets: presetListData||[], preset_names: presetListNames||{}, settings: presetSettings(presetData())}` |
| 同上 | `getAllPresets`（:91-94） | 保持“名字数组”路径，改读 `presetListNames` 的 key 或 `libraryPresets().map(name)`；**不得**读数据数组 |
| 同上 | `presetMutate`（:203-204） | 写完后同时清空 `presetListData`/`presetListNames` |

约束与注意：
- 不能把 `presets` 直接塞进现有 `presetListCache`：`getAllPresets` 和 `findPreset` 都会读到数据对象，`p.name` 变 `undefined`。
- 缓存未预热时（`getPresetList` 在 `refreshPresetList()` 完成前被同步调用），按 PiuPiu“取不到就丢弃”的语义返回 `{presets:[], preset_names:{}, settings:{...}}`；不要退回 `{id,name}`，否则形状会时对时错。若担心空列表影响卡，可退回“只列已缓存数据”的等价实现。
- `settings` 只能给 `presetSettings(activeData)`（`CRT:136-140` 现成），无法给 PiuPiu 的 model/stream 字段；文档需注明这是子集。
- 宿主侧**不要**把 `settings` 硬编码进去：宿主不持有模型/流式设置，`GenerationSettings` 才是。

#### 风险与回归点

| 风险 | 说明 |
|---|---|
| `tools/test-card-modules.cjs` | 第 22 行 mock 返回 `{presets: [], preset_names: []}`，未断言形状；第 98/102/105 行断言 `getAllPresets` 与 `getPresetSettings`，改 `getAllPresets` 数据源会直接失败。 |
| `card-modules/preset-manager.js` | 只做 `context.getPresetManager(apiId)` 转发（:2-5），不消费形状。 |
| 预设数据体积 | 一次返回全部预设全文，长预设（100k 内容上限）会放大 RPC JSON；当前 `getPresetList` 只被 `refreshPresetList` 调用一次（`CRT:285`），可接受。 |
| 引入内置名 | 残叙没有 `Hl()` 等价物，`presets[0]` 不会是对应内置预设；卡若假设“下标 0 = 当前/内置”，仍会错位。 |

---

### 2.4 写接口的返回值与守卫

#### PiuPiu 真实返回（逐项）

| 方法 | 情形 | 返回/抛出 | 证据 |
|---|---|---|---|
| `createPreset(name,data?,opts?)` | 名字命中 `["in_use", Hl(), ...所有预设名]` | `false` | CV:22297-22318（`cu().includes`）、CV:22169-22171 |
| 同上 | 其他（交给 `vu`） | 见 `createOrReplacePreset` | CV:22313 |
| `createOrReplacePreset` | `trim(name)` 为空 | `throw Error("预设名称不能为空")` | CV:22212-22217 |
| 同上 | `name === "in_use"` | 先执行 `ou("in_use")`，再 `false` | CV:22219-22225 |
| 同上 | `name === Hl()`（内置只读） | `throw Error("远端或内置预设为只读，不能保存")` | CV:22227-22233 |
| 同上 | 同名已存在 | 覆盖后 `false` | CV:22245-22257 |
| 同上 | 新名 | `addGlobalPreset` 后 `true` | CV:22258-22283 |
| 同上 | `data` 非法 | `throw TypeError("预设数据格式无效")`（`pu`/`an`） | CV:22175-22187 |
| `savePreset(name,data?,opts?)` | 空名或 `"in_use"` | `throw Error("保存预设名称无效")` | CV:22456-22462 |
| 同上 | `name === Hl()` | `throw Error("远端或内置预设为只读，不能保存")` | CV:22464-22470 |
| 同上 | 成功（新建或覆盖） | **`undefined`** | CV:22545-22554 |
| 同上 | `data === undefined` | 复制**当前 active 预设**数据（不是目标预设） | CV:22482-22484、CV:22499-22502 |
| `replacePreset(name,data,opts?)` | `name` 空 → 视为 `"in_use"`；内置/无 active/不存在 | `throw`（`nu`） | CV:22037-22061、CV:21948-21989 |
| 同上 | 成功 | **`undefined`**；`data` 缺失会抛 `TypeError`（`wo` 解引用 `n.prompts`，CV:7215）；`data={}` 会清空 prompts 并重建空 order | CV:22031-22035、CV:7215-7229 |
| `setPreset(name,data,opts?)` | 成功 | 返回 `ko(current, data)` 合并对象（ST 形状） | CV:22137-22168、CV:7339-7355 |
| `updatePresetWith(name,fn,opts?)` | `fn` 非函数 | `throw TypeError` | CV:22075-22081 |
| 同上 | updater 返回非对象 | `throw TypeError` | CV:22104-22113 |
| 同上 | 成功 | 返回 updater 的返回对象 | CV:22128-22129 |
| `updatePreset(patch)`（manager） | 当前无预设 | `Promise.reject(Error("远端或内置预设为只读，不能作为本地预设"))` | CV:21896-21909 |
| 同上 | 有当前预设 | **`undefined`；`patch` 实际被丢弃**（`_u(name,undefined,patch)` 的第三参未被读取） | CV:21905、CV:22445-22561 |
| `deletePreset(name?)` | 空名/`"in_use"`/`Hl()` | `false` | CV:22330-22338 |
| 同上 | 目标不存在 | `false` | CV:22349-22354 |
| 同上 | 成功 | `true` | CV:22360-22373 |
| `renamePreset(old,new)` | 空 old/new、old=`in_use`、old=`Hl()` | `false` | CV:22396-22404 |
| 同上 | new 已存在或 new=`Hl()` | `false` | CV:22405-22410 |
| 同上 | old 不存在 | `throw`（`Kl` 抛“预设不存在”） | CV:22421-22422、CV:21722 |
| 同上 | 成功 | `true` | CV:22429-22430 |
| `selectPreset/setActivePresetId` | 传入内置名 | 取消选中，`true` | CV:21698-21701 |
| 同上 | 找不到 | `false` | CV:21702-21705 |
| `loadPreset` | 空或 `"in_use"` | `false`（不加载数据） | CV:22172-22174 |
| `open/show/...` | 任意 | `undefined` | CV:21809-21811、21929-21934 |

#### 残叙现状

`AC:126-162`（`presetChange`）：空名 `require` 抛；`createPreset` 重名抛；`savePreset`/`setPreset` 都按“有则覆盖、无则新建”；`replacePreset` 不存在抛；`deletePreset`/`renamePreset` 不存在抛。
`AC:164-191`（`commitPreset`）：所有成功路径统一 `{value:true}`。
`CRT:195-210`（`presetMutate`）：异常或 `value===false` 一律 `return false`，成功 `return true`；`CRT:212-225` 各方法直接转调。

#### 兼容优先级（哪些必须改）

| 优先级 | 项 | 理由 |
|---|---|---|
| 必须 | `savePreset`/`replacePreset`/`updatePreset` 成功返回 `undefined` | 卡最常见写法 `if (await savePreset(...)) {...}`；现在恒 `true` 会**多走一个分支**。`undefined` 也会让 `=== false` 判断落空，与 PiuPiu 一致。 |
| 必须 | `createOrReplacePreset` 覆盖已存在 → `false`，新建 → `true` | 卡常据此决定“是否首次创建/是否提示”。 |
| 必须 | `setPreset` 返回合并对象、`updatePresetWith` 返回 updater 结果 | 卡会把返回值继续当预设读写。 |
| 必须 | 空名、`"in_use"`（按方法）、内置名 → `throw` | 与 PiuPiu 的 reject 对齐；现在被 `presetMutate` 吞成 `false`，卡会误判“操作被拒绝但无错误”。 |
| 建议 | `createPreset` 重名 → `false`（不抛） | 现在宿主抛、JS 转 false，**可观测结果已一致**，只需保持 JS 不抛。 |
| 保持现状（更好） | `updatePreset(patch)` 真正应用 patch | PiuPiu 丢弃 patch 是缺陷；卡依赖“空操作”的概率极低。保持现有行为并文档注明差异。 |
| 保持现状 | 名称长度上限 200、去空白后唯一 | 属残叙额外校验，不会让正常卡走错分支。 |

#### 实现清单

| 文件 | 函数 | 改什么 |
|---|---|---|
| `app/.../AppController.kt` | `commitPreset`（:164-191） | 返回体补 `id`/`name`/`data`（或至少 `data`），便于 JS 构造 `setPreset`/`updatePresetWith` 返回值；仍可保留 `value:true` |
| 同上 | `presetChange`（:126-162） | `createPreset` 重名改为返回“未变更”并让 JS 得到 `false`（或 JS 先查重）；`savePreset` 无 `data` 时按 PiuPiu 复制 active 预设；拒绝 `name=="in_use"` |
| `app/src/main/assets/card-runtime.js` | `presetMutate`（:195-210） | 降级为底层 `request + 刷新缓存`，不再统一 `return true` |
| 同上 | 各包装（:212-233） | 按方法翻译：`savePreset`/`replacePreset` → `undefined`；`createOrReplacePreset` → `result.created`；`setPreset` → 合并后的数据对象；`updatePresetWith` → updater 结果；`deletePreset`/`renamePreset` → `false` 兜底 |
| 同上 | `updatePreset`（:219-225） | 保留应用 patch；成功返回 `undefined` |
| 同上 | 新增 `assertWritableName` | 空名/`in_use`/（未来）内置名统一抛错，避免每个方法重复 |

注意：`CRT:232` 的 `updatePresetWith` 目前 `return savePreset(...)`，改完后必须返回 `updated`；`CRT:213` 的 `createOrReplacePreset` 直接复用 `savePreset` 会丢失“true/false”语义。

#### 风险与回归点

| 风险 | 说明 |
|---|---|
| `savePreset` 从 `true` 改 `undefined` | 若残叙自身 UI/模块依赖 `true`（`grep` 未发现内部调用），外部卡可能从“进入分支”变“跳过”，这是**修正**但属行为变更，需在 changelog 写明。 |
| `setPreset` 返回对象 | 需要宿主回传写后数据；否则 JS 只能用请求里的 `data`，与 PiuPiu 的“规范化后对象”仍有差异。 |
| 空名抛错 | `presetMutate` 的 `catch` 会把它转 `false`；必须让 `assertWritableName` 在 `try` **之外**执行。 |
| `tools/test-card-modules.cjs` | 未覆盖写接口返回值，本次改动不会被现有断言拦住；建议实现后手工补一条（不在本轮）。 |

---

### 2.5 打开预设管理器 UI

#### PiuPiu 证据

| 事实 | 证据 |
|---|---|
| 6 个别名共用同一个函数 `r = () => { Et.value = true }` | CV:21809-21811 |
| 挂在返回对象尾部：`open/show/openManager/openPresetManager/showPresetManager/openPromptManager` | CV:21929-21934 |
| `Et` 控制 `GlobalPresetListModal` 渲染 | CV:29181-29194（`Et` 布尔） |
| 另有设置面板入口 `settings-global-preset` → `Et.value = true` | GMS:1286、CV:28886-28890 |
| 返回值：`undefined`（箭头函数无 return） | CV:21809-21811 |

#### 残叙现状

- `CRT:241-250` 的 `presetManager` **没有**任何 open/show 别名。
- 无 `openPresetManager` RPC（`AC:666-729` 的 when 无该分支）。
- UI 有等价页面：`XujingApp.kt:159-188` 的 `LibraryScreen`，按 `libraryKinds` 分 tab（`Common.kt:138` 含 `"preset" to "预设"`），但 `kind` 是 `LibraryScreen` 内部 `rememberSaveable`（`XujingApp.kt:160`），外部无法定位到预设 tab。
- 无“打开指定资料 tab”的动作（`UiContract.kt:14-48` 无对应方法）。

#### 最小实现（推荐：真打开）

| 文件 | 位置 | 改什么 |
|---|---|---|
| `app/src/main/assets/card-runtime.js` | `presetManager`（:241-250） | 加 `open/show/openManager/openPresetManager/showPresetManager/openPromptManager`，全部 `() => { request('openPresetManager', {}).catch(() => {}); }`（同步返回 `undefined`） |
| `app/.../AppController.kt` | `cardRequest`（:666 起 when） | 加 `"openPresetManager" -> { _runtime.update { it.copy(openLibraryKind="preset") }; JsonPrimitive(true) }` |
| `app/.../ui/UiContract.kt` | `UiRuntime`（:5-13） | 加 `val openLibraryKind:String? = null` |
| `app/.../ui/UiContract.kt` | `AppActions`（:14-47） | 加 `fun consumeOpenLibrary()` |
| `app/.../AppController.kt` | 类内 | 实现 `consumeOpenLibrary()` → `_runtime.update { it.copy(openLibraryKind=null) }` |
| `app/.../ui/XujingApp.kt` | `LibraryScreen`（:159-165） | 把 `kind` 提升到 `XujingApp`（`var libraryKind`），以参数传入；`LaunchedEffect(runtime.openLibraryKind)` 设 `screen="library"`、`libraryKind="preset"`，然后 `actions.consumeOpenLibrary()` |

注意：
- 卡片调用时当前会话页面正打开（`ChatScreen` 覆盖整个 `Surface`，`XujingApp.kt:46`）。要真正“看见”资料库，需先 `actions.openSession(null)` 再切屏；PiuPiu 是弹窗叠加，残叙是全屏导航。最小实现可直接 `openSession(null)` + `screen="library"`，代价是离开聊天页（与 PiuPiu 的弹窗体验不同，但功能等价）。
- 不要用 `AppState` 存这个一次性 UI 意图（会触发落盘/导入导出）。

#### 降级方案（只返回 true，不打开）

`presetManager.open = () => true`（其余别名同）而不发 RPC。风险：卡会认为“管理器已打开”并继续读 `getPresetList()`/等待用户操作；用户看不到任何反馈，属于“静默无效”。仅在工期不足时使用，并在文档中标注未实现。

#### 风险与回归点

| 风险 | 说明 |
|---|---|
| `UiRuntime` 新字段 | 记忆化/预览的 `copy` 调用很多，默认值必须给 `null`，否则编译期到处补参数。 |
| 离开会话 | `openSession(null)` 会触发 `clearSuggestions()`（`AC:219`）；生成中调用可能被 `generating` 守卫挡住，建议 RPC 里先判断，返回 `false`。 |
| 无 Compose 访问权 | `cardRequest` 运行在 `viewModelScope`，只能改 `_runtime`；不要直接操作 `NavController`。 |

---

### 2.6 预设变量作用域

#### PiuPiu 证据

| 事实 | 证据 |
|---|---|
| 预设变量只有一个 localStorage 键 `piupiu_tavern_helper_preset_variables` | GMS:18457 |
| 沙箱 `getVariables({type:'preset'})` 优先读父桥 `getPresetVariables()`，否则读 `window.__PIUPIU_PRESET_VARIABLES` | GMS:34749（巨型模板串内 `getVariablesByScope`） |
| 父桥 `getPresetVariables/setPresetVariables` 直连 localStorage 读写 | GMS:34153-34157 |
| 写入时同时更新本地缓存与父桥 | GMS:34749（`setVariablesByScope('preset')`） |
| `getVariables()` 未指定 scope 时默认 `chat` | GMS:34749（`getVariablesByScope` 末尾 `return window.__PIUPIU_CHAT_VARIABLES`） |
| 支持的全部 scope：`all/message/character/global/preset/script/extension/chat` | GMS:34749（同函数分支） |
| **不按预设名分桶**（键是常量，没有 `preset:<name>` 前缀） | GMS:18457、GMS:34153-34157 |
| 变量更新不会把 preset scope 广播给父窗口 | GMS:34749（`postVariableUpdate` 对 `preset/script/extension/message` 直接 return） |

#### 残叙现状

- `CRT:36-42`：`scope()` 只接受 `chat`/`message`，其余 `throw Error('尚未实现的变量作用域：'+type)`。
- `CRT:43`：`getVariables` 只有 chat/message 两条取值路径。
- `CRT:45-55`：`replaceVariables` 用 `option` 做陈旧校验并回写 `context.variables` 或消息变量。
- `CVR:19-23`：`read` 只认 `chat`/`message`，其余 `error("尚未实现的变量作用域：...")`。
- `CVR:24-38`：`apply` 按 `option.type=="message"` 分流，否则写 `session.variables`；**没有 state 级桶**。
- 快照：`CardMessage.kt:84-89` 与 `SSR:98-105` 只透出 `variables`（= chat 作用域）。

#### 实现方案（本轮至少 `preset`）

| 文件 | 函数 | 改什么 |
|---|---|---|
| `core/.../compat/CardVariables.kt` | `read`（:19-23） | 签名改 `read(state:AppState, session:ChatSession, option:JsonObject)`；新增 `"preset" -> (state.compatibility["presetVariables"] as? JsonObject ?: EmptyObject)` |
| 同上 | `apply`（:24-38） | 读取 prior 时传 `state`；`preset` 分支写 `state.copy(compatibility=JsonObject(state.compatibility+("presetVariables" to variables)))`，不动 session |
| `app/.../AppController.kt` | `"getVariables"`（:674） | 传 `state.value` |
| 同上 | `replaceVariables` 分支（:675） | 不变（走 `CardVariables.apply`） |
| `app/src/main/assets/card-runtime.js` | `scope()`（:36-42） | 加 `if(type==='preset') return {type:'preset'};` |
| 同上 | `getVariables`（:43） | 加 `context.presetVariables || {}` 分支 |
| 同上 | `replaceVariables`（:45-55） | 结果回写 `context.presetVariables`；事件仍发 `mag_variable_update_ended`（与现状一致） |
| 同上 | `api`（:268-279） | 可选加 `getPresetVariables: () => getVariables({type:'preset'})`、`setPresetVariables: v => replaceVariables(v,{type:'preset'})` |
| `app/.../runtime/CardMessage.kt` | `snapshot`（:84-89） | 加 `put("presetVariables", state.compatibility["presetVariables"] ?: EmptyObject)` |
| `app/.../runtime/SessionScriptRuntime.kt` | `snapshot`（:98-105） | 同上 |

设计取舍：
- **存储位置**：`AppState.compatibility["presetVariables"]`。理由：`compatibility` 已是 `JsonObject`（`Models.kt:61`），`serialization` 默认值 + `ignoreUnknownKeys` 保证旧存档兼容，无需迁移；`CompatibilityCodec` 原样保留该对象。若未来要按预设名分桶，再改键结构即可，但那是**偏离 PiuPiu**。
- **不按 presetId 分桶**：PiuPiu 单桶（GMS:18457），跨预设共享；本轮照抄，文档注明。
- **`getVariables()` 默认 scope** 仍为 `chat`（`CRT:37`），不受影响。
- **陈旧校验**：`expected` 仍由 JS 用 `getVariables(option)` 现取，Kotlin 端 `payload["expected"]==prior`（`CVR:28`）不改；preset 作用域同样适用。
- `context.presetVariables` 需要在 `__xujingUpdate`（`CRT:326-344`）整体替换时保留——它来自 `next`，无需特殊处理。

#### 风险与回归点

| 风险 | 说明 |
|---|---|
| `read` 签名变更 | `CVR:27` 与 `AC:674` 两处调用；无其它引用（已全仓 grep）。 |
| 会话切换 | preset 变量是全局单桶，切会话不变；PiuPiu 同样。 |
| 生成期写入 | `commitCardLibrary` 有 `require(!generating)`（`AC:97`），脚本在生成期写 preset 变量会被拒；PiuPiu 的父桥写 localStorage 不受限。这是既有策略差异，不在本节扩大。 |
| 导出/导入 | 当前 `exportSettings` 未导出 `compatibility` 全量（`CompatibilityCodec.kt:55-58` 只取 `piupiuSettings`）；若要求预设变量随备份迁移，需要另行加入导出/合并，本轮不做。 |
| 事件 | PiuPiu 不对 preset 发 `sandbox-variables-update`；残叙 `CRT:52` 会发本地事件。多一个本地事件通常无害，但严格对齐需按 scope 屏蔽。 |

---

### 2.7 内置只读默认预设与“远端 + 本地”合并（只分析）

#### `Hl()` / `se()` 是什么

| 事实 | 证据 |
|---|---|
| `Hl()` = `String(se()?.name \|\| "PiuPiu酱内置预设")` | CV:21687-21690 |
| `se()` 是“无选中预设时的内置/远端预设对象”，被 `an()` 归一化后作为生成基座与 `getPresetSettings` 兜底 | CV:21354、CV:21779-21788、CV:25596 |
| `ln()` 被用作 `createOrReplacePreset` 的默认 `data`（`bo(ln())`） | CV:22207、CV:22260 |
| 写入守卫用 `name === Hl()` 判定“内置只读” | CV:22227、CV:22464、CV:22333、CV:22399 |
| `getAllPresets()` 永远把 `Hl()` 排在首位 | CV:21814-21822 |
| 真正的“内置默认全局预设安装器”是另一套：名字「双人成行」，拉 `/presets/default-global-preset.json`，装成普通预设 | DGP:290-360、DGP:619-631 |
| `se()`/`ln()` 的导出模块未定位 | 见 §4 未验证 |

#### 合并算法（生成前）

调用点 `CV:25596-25648`：`A = se()`（内置，作为 **remote**），`R = activeGlobalPresetId`，`N = loadGlobalPreset(R)`（用户选中，作为 **local**），`P = Pr(A, N, {presetId:R.id, generationType:M})`。
实现 `IV:979-1085`：

| 步骤 | 行为 | 证据 |
|---|---|---|
| 1 | 深拷贝 remote，给每条 prompt 打 `_piupiu_source:"remote"` | IV:980-985 |
| 2 | 取 remote 的可见 order；没有分组则补 `{character_id:100001, order:[]}` | IV:986-988 |
| 3 | 取 local 的可见 order；无 local 直接返回 remote | IV:989-991 |
| 4 | 计算 remote 中“已启用且内容非空”的 identifier 集合 `b` | IV:1007-1016 |
| 5 | 对 local 的每个 order 项：未启用（`main` 例外）或 remote 没有 → 跳过；否则按 `injection_trigger`（`Fi`）过滤 | IV:1017-1032 |
| 6 | 若 identifier 与 remote 冲突 → 改名为 `piupiu-local:<presetId>:<index>:<identifier>`，仍冲突则继续追加 `:<index>` | IV:1035-1043 |
| 7 | 追加到 `n.prompts`，带 `_piupiu_source:"local"`、`_piupiu_original_identifier`、`_piupiu_before_anchors/_piupiu_after_anchors`；并在 order 末尾 push `{identifier, enabled:true}` | IV:1044-1079 |
| 8 | `main` 特殊：`before_anchors=[]`、`after_anchors=["main"]` | IV:1063-1075 |

anchors 的消费在生成编排：对每条 local prompt，先在剩余 identifier 集合里找**第一个存在的 before anchor**；找到就插到该 anchor 之后，否则找**第一个存在的 after anchor** 插到它之前，都没有就放尾部（CV:5458-5486）。remote 文本收集时跳过 `_piupiu_source==="local"`（CV:5327），local 内容由后续合并路径进入。

#### 残叙现状

- `PromptEngine.kt:27-28` 只用 `session.presetId` 指向的预设；没有基座、没有叠加。
- `PromptEngine.kt:111-118` 的 `prompt_order` 只从该预设里选一组；无 anchors 概念。
- 数据模型没有 `_piupiu_source/_piupiu_before_anchors/_piupiu_after_anchors` 的处理（`grep` 零命中）。
- `PromptEngine.kt:126-127` 对缺失 `enabled` 视作开启；PiuPiu 的合并对 local order 项要求 `!== false` 且必须存在于 remote（IV:1019-1024）。

#### 前置条件与工作量（不建议本轮做）

| 前置 | 说明 |
|---|---|
| 一个“基座预设”来源 | PiuPiu 是 `se()`（内置/远端）。残叙没有远端服务，也没有内置预设包；要么随包内置一份 JSON，要么以空预设置为基座。 |
| 合并结果可持久化/可解释 | `_piupiu_*` 是运行期临时字段，不入库；但 `presetId` 要参与 local 改名，缺了会重名。 |
| PromptEngine 插入算法 | 需要把 anchors 用在「顶部块」和「深度块」两处；PiuPiu 的锚点只解决顶部相对顺序（CV:5458-5486）。 |
| 交互影响 | 选中预设从“替换”变“叠加”，会改变所有现有会话的生成结果，必须作为单独里程碑并做 A/B 样本。 |
| 正则/脚本 | PiuPiu 的预设正则只取 local（`Nr(N)`，CV:25638），不含 remote；脚本只要当前预设（CV:18935-18945）。这两点不要跟着合并一起改。 |

建议：**P2，单独排期**。本轮不做；若短期必须缓解“预设不完整导致 main 缺失”，优先在 PromptEngine 保留现有“空 `prompt_order` 回退全部 enabled”逻辑（`PromptEngine.kt:116-118`），而不是引入合并。

---

## 3. 本轮建议实现的最小集合

| 顺序 | 项 | 理由 | 预估触点 |
|---|---|---|---|
| 1 | §2.1 预设正则 | 纯 core 改动，直接决定「预设里的状态栏/越狱规则」是否生效；无 UI 依赖 | `RegexProcessor.kt` 1 个文件 |
| 2 | §2.2 预设脚本 | 决定预设自带 MVU/前端脚本是否运行；改动集中在 core + 1 个 remember 键 | `SessionScripts.kt`、`SessionScriptRuntime.kt` |
| 3 | §2.4 写接口返回值 | 会改变卡分支的**正确性**问题；只改 JS 包装 + 宿主返回体 | `card-runtime.js`、`AppController.kt` |
| 4 | §2.3 `getPresetList` 形状 | 与 3 同批改最省事（都要动 `presetMutate`/缓存）；但不牵涉 UI | `AppController.kt`、`card-runtime.js` |

不建议塞进本轮：§2.5（UI 导航，需要 Compose 改动）、§2.6（变量作用域，需要新的持久化语义）、§2.7（架构级）。

建议单独排期：
- §2.6 预设变量：P1，独立一轮（storage + snapshot + JS scope + 陈旧校验）。
- §2.5 打开管理器：P1，和“资料库 tab 外部定位”一起做，避免两次改 `UiRuntime`。
- §2.7 内置/远端合并：P2，先决定产品上是否有“内置基座预设”。

---

## 4. 未验证项

1. **`se()` / `ln()` 的实现模块与内置预设内容**。只确认调用点与行为（CV:21687、22207、25596）；`se = e.bv` 所属 System.register 模块未定位到具体文件/行，因此“内置预设名与内容从哪来”**未验证**。
2. **`Js()`/`Ys()` 的具体实现**。只确认 `ss = "piupiu_tavern_helper_preset_variables"`（GMS:18457）与父桥 getter/setter（GMS:34153-34157）；`localStorage.getItem/setItem` 的实际写法在巨型模板串内，**未逐字符验证**。
3. **预设脚本与角色脚本同 id 的真实冲突表现**。PiuPiu 靠 runtime 隔离 + `id\0content` 去重；残叙靠 owner 前缀。没有构造同 id 样本，**未运行验证**按钮/禁用列表的实际表现。
4. **`getPresetList().settings` 的真实消费方**。只按 PiuPiu 代码判断形状；没有 SillyTavern/第三方卡样本，**未验证**卡是否真的读 `settings.preset_settings_openai`。
5. **ST 原始形状预设的正则**。若导入的 `extensions.regex_scripts` 使用 `find_regex/source/destination`（ST 规范），`RP:39-43` 读不到字段会静默跳过；没有样本，**未验证**残叙导入链是否会先归一化。
6. **预设内容变更时脚本引擎是否重建**。`SSR:75` 的 `remember` 目前不含 `state.library`；补 `session.presetId` 只能覆盖“切换预设”，同一预设内改脚本是否触发重建取决于 Compose 对 `state` 的重组时机，**静态推断，未运行**。
7. **`openPresetManager` 打开后离开会话的副作用**。`openSession(null)` 会清空建议并触发 `replayInBackground` 逻辑（AC:219-224），**未验证**生成中/回放中的实际表现。
8. **预设变量在导入/导出中的去留**。`CompatibilityCodec.exportSettings` 当前只导出 `piupiuSettings`（:55-58），`compatibility` 其它键是否应随备份导出**未验证**（可能是有意丢弃）。

---

## 5. 本轮实现状态（2026-11-06）

| 项 | 状态 | 落点 |
|---|---|---|
| §2.1 预设正则 | 已实现并实测 | `RegexProcessor.kt`：规则集带来源标签，顺序为 库 → 当前预设 → 角色卡；mock 捕获确认输入 `QA-REGEX-IN` 被改写为 `QA-REGEX-OUT` |
| §2.2 预设脚本 | 已实现并实测 | `SessionScripts.kt` 追加 `preset:<id>` 来源；`SessionScriptRuntime.kt` 的 `remember` 键含 `session.presetId` 与预设 `data`；`PRESET SCRIPT PING` 按钮可运行并写入变量 |
| §2.3 `getPresetList` 形状 | 已实现并实测 | 宿主返回 `presets`（data 数组）+ `preset_names`（名→下标）+ `names`/`ids`；JS 侧返回 `presets`/`preset_names`/`settings` |
| §2.4 写接口返回值 | 已实现并实测 | `savePreset` → `undefined`；`created` 区分新建/覆盖；`createOrReplacePreset` 覆盖返回 `false`；`setPreset` 返回转换形对象；`updatePresetWith` 返回 updater 结果 |
| §2.5 打开管理器 | 已实现并实测 | `openPresetManager` RPC → `UiRuntime.openLibraryKind` → `XujingApp` 退出会话并打开资料库「预设」tab；6 个别名同步返回 `undefined` |
| §2.6 预设变量 | 已实现并实测 | `CardVariables` 支持 `type:"preset"`，存 `AppState.compatibility["presetVariables"]`；快照透出 `presetVariables`；跨会话保留 |
| §2.7 内置/远端合并 | 未实现（P2） | 需要一个“基座预设”来源，单独排期 |
| 悬浮面板层 | 已实现并实测 | `setScriptPanel(html, options)` / `clearScriptPanel()`；宿主渲染可拖动覆盖层；`parent.document` 追加的 `position:fixed` 面板由 `session-runtime.js` 自动转发 |

### 面板 API

```js
await setScriptPanel('<div>状态栏</div>', { title: '状态', width: 300, height: 200, position: 'bottom-end' });
await clearScriptPanel();
```

- 面板由宿主 WebView 渲染，脚本拿不到 App 的 DOM；`XujingPanel.close()` 可让面板自己关闭。
- 兼容层只转发追加到 `parent.document.body`、且内联样式含 `position:fixed` 的元素；脚本 iframe 自身的 DOM 仍不可见。
- 切换会话时自动清空；面板默认右下角，可拖动标题栏移动。

实测记录（模拟器 `emulator-5554`，角色卡 `QA Preset Checks` / `QA Preset Cap` / `QA Preset UI`）：

```text
shape=true|true|true ; count=8/6
saved=undefined ; created=true ; replaceExisting=false
set=true|object ; delete=true ; deleteMissing=false
pvars={"n":1,"who":"cap"} ; pvars2={"n":1,"who":"cap","checked":true}
hasCap=true|hasTmp=false ; active=QA-CAP
```

已知差异：`count=8/6` 是同名预设导致的键合并（PiuPiu 的 `preset_names` 同样会合并），`presets`/`names`/`ids` 仍保留全部条目。

`core:regressionChecks` 全绿，Node 两组测试退出码 0。

## 6. 复核命令

```powershell
$cv='D:\Canxu-project\PiuPiu-source\frontend-readable\assets\c\ChatView-legacy-C8UPOooP.js'
Select-String -LiteralPath $cv -Pattern 'Qt.value = Nr|function cs\(|getAllEnabledScriptButtons|getPresetList'
$idx='D:\Canxu-project\PiuPiu-source\frontend-readable\assets\e\index-legacy-B0tYRju5.js'
Select-String -LiteralPath $idx -Pattern 'json_each\(p.root_json'
$iv='D:\Canxu-project\PiuPiu-source\frontend-readable\assets\c\ImageViewer-legacy-DD5gJaNv.js'
Select-String -LiteralPath $iv -Pattern '_piupiu_source|piupiu-local:'

$rp='D:\Canxu-project\Xujing\core\src\main\kotlin\app\xujing\core\engine\RegexProcessor.kt'
Select-String -LiteralPath $rp -Pattern 'kind == "regex"|distinctBy'
$ss='D:\Canxu-project\Xujing\core\src\main\kotlin\app\xujing\core\compat\SessionScripts.kt'
Select-String -LiteralPath $ss -Pattern 'roleIds|distinctBy'
$ac='D:\Canxu-project\Xujing\app\src\main\java\app\xujing\AppController.kt'
Select-String -LiteralPath $ac -Pattern 'getPresetList|commitPreset|presetChange'
```
