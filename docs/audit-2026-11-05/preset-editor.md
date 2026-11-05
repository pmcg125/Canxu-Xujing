# PiuPiu 2.7.9 预设（preset）子系统审计（只读）

审计对象：`D:\Canxu-project\PiuPiu-source\frontend-readable\assets\` 下的格式化产物（Vite legacy / System.register）。
本文只描述**原版**行为，不含任何实现建议或与其它项目的对比。

判读前提（沿用 `analysis/frontend-symbol-map.md`）：

- 产物是 minified bundle，变量名只有 `e/a/t/n`，不能用符号索引；下文行号均为格式化副本行号。
- 一个 chunk 里打包了多个业务模块（`ImageViewer-legacy-DD5gJaNv.js` 同时含图片查看 + 9 个设置弹窗），所以「文件名」不代表模块边界。
- 预设子系统的**代码**主要分布在三处：
  - `assets/e/index-legacy-B0tYRju5.js`：Pinia/`AppStore` 里的预设保存逻辑、SQLite 读写、归一化与兼容函数；
  - `assets/c/ImageViewer-legacy-DD5gJaNv.js`：三个弹窗（列表 / 编辑器 / 市场包装）；
  - `assets/c/app-security-legacy-BdXLf8ML.js`：建表语句、UUID 生成、localStorage JSON 读取；
  - 另有 `assets/c/defaultGlobalPreset-legacy-tjZgE48x.js`：内置默认预设安装器；
  - `assets/c/SettingsView-legacy-CYxjl7kQ.js`：设置备份里的预设导入/导出；
  - `assets/c/ChatView-legacy-C8UPOooP.js`：运行期消费 `prompt_order`、ST 兼容层的预设读写、prompt manager。

---

## 1. 数据模型

### 1.1 结论：两张表，一张存「壳」，一张存「每条提示词」

| 表 | 列 | 证据 |
|---|---|---|
| `preset_configs` | `preset_id INTEGER PRIMARY KEY`、`root_json TEXT NOT NULL DEFAULT '{}'`、`updated_at INTEGER NOT NULL DEFAULT 0` | `assets/c/app-security-legacy-BdXLf8ML.js:11958` |
| `preset_prompts` | `preset_id`、`prompt_index`、`visible_order`、`prompt_id`、`prompt_name`、`role`、`content`、`enabled`、`is_builtin`、`injection_position`、`injection_depth`、`injection_order`、`injection_trigger_json`、`prompt_json`、`updated_at`，主键 `(preset_id, prompt_index)` | `assets/c/app-security-legacy-BdXLf8ML.js:11959` |

索引：`idx_preset_prompts_order(preset_id, is_builtin, visible_order)`、`idx_preset_prompts_id(preset_id, prompt_id)`、`idx_preset_prompts_name(preset_id, prompt_name COLLATE NOCASE)`（`app-security-legacy-BdXLf8ML.js:11984-11986`）。

结论要点：

1. **预设没有 `name` / `description` 列。** 预设显示名、条目计数、`marketDownloaded` 只存在 localStorage 索引 `piupiu_global_preset_list` 里（`assets/e/index-legacy-B0tYRju5.js:6103`、`:6630-6640`、`:6774-6783`）；`updatedAt` 在索引与 `preset_configs.updated_at` 各存一份，用于缓存校验。
2. **`root_json` 是「除 prompts 外的整份预设」**：写库时先把归一化后的预设深拷贝一份并置 `prompts = []` 再序列化（`e/index-legacy-B0tYRju5.js:24297-24304`）。所以 `prompt_order`、`extensions`、以及所有未识别的 SillyTavern 顶层字段（如采样参数）都留在 `root_json`。
3. **`prompt_json` 是「整条 prompt 原样」**：写库时 `JSON.stringify(m)`（`e/index-legacy-B0tYRju5.js:24379`），未知字段不丢。`content` / `role` / `name` / 注入参数是**冗余投影**，便于列表和搜索。
4. 读回时 `root_json` + 所有 `prompt_json` 按 `prompt_index ASC` 拼回一个预设对象，再过归一化（`e/index-legacy-B0tYRju5.js:25357-25413`）。

### 1.2 一条 prompt 的字段全集

内存里的 `prompts[]` 元素（SillyTavern 形状，`enabled` 不在元素上）：

| 字段 | 含义 | 是否独立列 |
|---|---|---|
| `identifier` | 提示词唯一键；`prompt_order` 用它引用内容 | `preset_prompts.prompt_id` |
| `name` | 显示名；空名会被保存校验拦下 | `prompt_name` |
| `role` | `system` / `user` / `assistant`，其它值归一为 `system` | `role` |
| `content` | 正文（宏在运行期展开） | `content` |
| `injection_position` | `0` = 相对位置（按 `prompt_order` 走）；`1` = 聊天中注入 | `injection_position` |
| `injection_depth` | 注入深度；编辑器提示文案为「深度 0 表示最后一条消息之后」 | `injection_depth` |
| `injection_order` | 同深度内的先后，越小越靠前 | `injection_order` |
| `injection_trigger` | 生效的生成方式数组，只保留 `normal/continue/regenerate` | `injection_trigger_json` |
| `system_prompt` | 布尔；`false` 是「可删除」的必要条件（见 3.4） | 无，留在 `prompt_json` |
| `marker` | `true` 表示内置占位标记 | 无，留在 `prompt_json` |
| `forbid_overrides` | 新增条目时写死的 `false` | 无，留在 `prompt_json` |
| `_piupiu_source` / `_piupiu_original_identifier` / `_piupiu_before_anchors` / `_piupiu_after_anchors` | 兼容层合并「本地提示词」时打的临时标记 | 无（只在运行期对象上） |
| 其它 ST 字段（`extra`、`id`、`position` 等） | 原样保留 | 无，留在 `prompt_json` |

- `enabled` 的真实位置是 `prompt_order[].order[].enabled`（标准化时不往 prompt 上写）。写库时把它拍平到 `preset_prompts.enabled`（`e/index-legacy-B0tYRju5.js:24353-24355`），读回时 editor 再从 order 还原。
- `visible_order` 是**派生列**：等于该 prompt 在「可见列表」中的下标，内置/未出现在 order 中的为 `-1`（`e/index-legacy-B0tYRju5.js:24344-24348`）。
- `is_builtin` 也是**派生列**：`identifier` 命中 12 个内置标记之一或 `marker === true` 即 1（`e/index-legacy-B0tYRju5.js:5961-5963`、`:24356`）。12 个标记为：`main`、`worldInfoBefore`、`personaDescription`、`charDescription`、`charPersonality`、`scenario`、`enhanceDefinitions`、`nsfw`、`worldInfoAfter`、`dialogueExamples`、`chatHistory`、`jailbreak`（`e/index-legacy-B0tYRju5.js:5923-5936`）。
- `prompt_index` = 该 prompt 在 `prompts` 数组中的下标（`e/index-legacy-B0tYRju5.js:24343`），与「可见顺序」无关。

### 1.3 `prompt_order` 的结构与作用

结构（SillyTavern 原生形状，`character_id` 是分组键）：

```json
[{ "character_id": 100001, "order": [{ "identifier": "main", "enabled": true }] }]
```

- 空预设的默认值就是这一个分组（`e/index-legacy-B0tYRju5.js:5943-5948`）。
- 读取时只认**一个**分组：优先 `character_id === "100001"`，否则第一个非空 `order`，否则第 0 个（`e/index-legacy-B0tYRju5.js:5967-5979`）。其它分组会随 `root_json` 原样往返，但既不显示也不参与生成。
- 「可见提示词列表」= 按被选中分组的 `order` 顺序遍历，用 `identifier` 去 `prompts` 里找内容，去重后返回 `{prompt, enabled}`（`e/index-legacy-B0tYRju5.js:6057-6077`）。因此：**不在 order 里的 prompt 不会显示、也不会生效**。
- 运行期消费在 `ChatView`：遍历 order，跳过 `enabled !== true`，再按 `identifier` 找 prompt，并叠加 `injection_trigger` 与一批特殊 identifier 的开关（`assets/c/ChatView-legacy-C8UPOooP.js:5133-5149`）。
- 三种注入桶与实际排序（`ChatView-legacy-C8UPOooP.js:5373-5382`、`:5433-5447`）：
  - `injection_position === 1` → 进「聊天中/深度」桶，按 `injection_order` 排序；
  - 否则 `injection_depth > 0` → 进深度桶，按 `injection_depth` 升序、同深度按 `injection_order`；
  - 否则按「相对位置」桶，按 `injection_order` 排序。
- `prompt_order` 的增删改都在内存里完成：上移/下移只交换 order 数组中的两个元素，不动 `prompts`（`e/index-legacy-B0tYRju5.js:1582-1601`）；启用开关只改 order 元素的 `enabled`（`e/index-legacy-B0tYRju5.js:6083-6097`）。
- 所以 `prompt_order` 同时承担三件事：**顺序表、启用状态表、可见性白名单**。

### 1.4 内存中的规范形态

```js
{
  prompts: [ { identifier, name, role, content, injection_*, ... } ],
  prompt_order: [ { character_id: 100001, order: [ { identifier, enabled } ] } ],
  extensions: { regex_scripts: [...], piupiu_preset_market: {...}, ... },
  // 其余顶层字段（temperature / top_p / openai_max_tokens ...）原样保留
}
```

- 深拷贝工具是 `JSON.parse(JSON.stringify(e))`（`e/index-legacy-B0tYRju5.js:5940-5942`），所以**函数/undefined 一定会丢**，其余字段保真。
- 归一化函数 `$i()`（`e/index-legacy-B0tYRju5.js:5980-6056`）是唯一的入口：`preset_configs` 写库前（`:24244`）、文件导入（`:1546`）、市场下载（`GlobalResourceMarketModal-legacy-BvchFYLb.js:3146-3176` 通过导入的归一化函数 `g`）、设置备份恢复（`:7315`）都过它。

### 1.5 localStorage 键与「索引 / 正文」分离

| 键 | 内容 | 证据 |
|---|---|---|
| `piupiu_global_preset_list` | 预设索引数组 `{id, name, totalPromptCount, marketDownloaded, enabledPromptCount, updatedAt}` | `e/index-legacy-B0tYRju5.js:6103`、`:6605-6642`、`:6774-6783` |
| `piupiu_active_global_preset_id` | 当前选中的预设 id（字符串数字） | `e/index-legacy-B0tYRju5.js:6104`、`:6643-6650` |

- 启动时索引从 localStorage 读（`ee` 即 localStorage JSON getter，`app-security-legacy-BdXLf8ML.js:9761-9776`，导出名 `E`，见 `:582`），正文留在内存缓存 `o: Map<id,{updatedAt,preset}>` 与 SQLite（`e/index-legacy-B0tYRju5.js:6612-6613`、`:6964-7057`）。
- **风险事实（静态）**：预设正文在 SQLite，但「预设列表」没有从 SQLite 重建的路径（`fe()` 只做 localStorage→SQLite 的单向迁移，`:6784-6886`）。localStorage 被清空后，`globalPresets` 为空，`loadGlobalPreset(id)` 也会在索引里找不到而返回 `null`（`:6984-6992`）。
- 旧版 localStorage 里是「索引 + 完整 preset」；迁移时把完整 preset 放进 `i: Map<id,preset>` 并逐条 `replacePresetIndex`，成功后 `i.clear()` 并重写索引（`e/index-legacy-B0tYRju5.js:6605-6613`、`:6784-6886`、`:6863`）。`hasLegacyGlobalPresetPayload(id)` 就是 `i.has(id)`（`:7980-7982`）。

---

## 2. 归一化与兼容

### 2.1 `$i()` 做的六件事

证据：`assets/e/index-legacy-B0tYRju5.js:5980-6056`。

1. **门槛**：非对象、或 `prompts` 与 `prompt_order` 都不是数组 → 返回 `null`（`:5981-5983`）。文件导入据此报「没有找到有效的 SillyTavern 预设数据」（`:1546-1547`）。
2. **prompt 逐条清洗**（`:5987-6005`）：非对象条目丢弃；`identifier` 取字符串并 trim，**为空或与前面重复时改成新 UUID**（`:5990-5993`）；`name` 非字符串→`""`；`role` 只接受 `user/assistant`，其余→`system`（`:5949-5951`）；`content` 非字符串→`""`。
3. **prompt_order 逐组清洗**（`:6009-6046`）：组内 `order` 只保留「对象 + 非空字符串 identifier」的项；每个项补 `enabled: !1 === e.enabled ? false : true`（即只有显式 `false` 才关）；组 `character_id` 非 string/number 时补 `100001`。
4. **空 prompt_order 兜底**：`n.length > 0 ? n : [{character_id:100001, order:[]}]`（`:6051-6053`）。
5. **未知字段保留**：prompt 与顶层对象都是「先展开原对象再覆盖已知键」，所以 `extra`、`temperature`、`extensions` 等不会丢。
6. **不补 `system_prompt`**：归一化不会给 ST 条目补这个布尔（只有编辑器新建条目时才写 `system_prompt:false`，见 `:1498-1520`）。这直接影响删除按钮的可见性（3.4）。

### 2.2 与 SillyTavern 的 `prompts` + `prompt_order` 兼容

- **文件导入**（`Ze` = `e.bf`）：`JSON.parse` → `$i()`；只接受 `prompts` / `prompt_order` 形状，不接受 ST 的 `settings` / `prompts_unused` 包装（`e/index-legacy-B0tYRju5.js:1539-1549`）。
- **运行时兼容层**（ChatView，导出给 TavernHelper / `scripts/preset-manager.js` 的形态）另有一套转换：
  - PiuPiu → ST：`{ settings, prompts, prompts_unused, extensions }`（`ChatView-legacy-C8UPOooP.js:7058-7090`）；
  - ST → PiuPiu：把 `settings.*` 映射回顶层采样字段、把 `prompts[].id`/`position` 转成 `identifier`/`injection_*`、把 `prompts_unused` 并入 `prompts`、并重建 `prompt_order`（`ChatView-legacy-C8UPOooP.js:7092-7124`、`:7126-7240`）。
  - 因此存在**两种 JSON 形态**：编辑器文件导入导出用 PiuPiu 原生形状；JS API（`getPreset` / `savePreset` / `updatePresetWith`）用 ST 形状（`ChatView-legacy-C8UPOooP.js:21814-21940`、`:22037-22136`）。
- **重复 identifier**：归一化成 UUID 后，`prompt_order` 里仍写的是原 identifier，于是这条重复项会变成「order 里找不到内容」而被隐藏（静态推断，见第 9 节）。

### 2.3 `preset_json` 与 `root_json` 各保留什么

- `root_json`：数据库列，存「无 prompts 的整份预设」——`prompt_order`、`extensions`（含 `regex_scripts`、`piupiu_preset_market`、`tavern_helper`）、全部未识别顶层字段（`e/index-legacy-B0tYRju5.js:24297-24304`）。
- `prompt_json`：数据库列，存每条 prompt 的完整原对象（`e/index-legacy-B0tYRju5.js:24379`）。
- `preset_json`：**不是数据库列**，只是市场接口的上传字段名（`assets/c/GlobalResourceMarketModal-legacy-BvchFYLb.js:3133-3137`），值是 `JSON.stringify(整份预设)`。

### 2.4 预设里的正则：`extensions.regex_scripts` 才是源

- 保存预设时只写 `preset_configs` / `preset_prompts`，同时会先删掉 `scope_type='preset'` 的 `regex_configs` / `regex_scripts`（`e/index-legacy-B0tYRju5.js:24257-24289`）。
- 这些正则行是**派生索引**：`ensurePresetRegexIndexed()`（`lv`，`e/index-legacy-B0tYRju5.js:25419-25510`）比较 `regex_configs.updated_at` 与 `preset_configs.updated_at`，不一致就用 `json_each(root_json,'$.extensions.regex_scripts')` 重建整表（`:25482-25496`）。列表/详情读取前都会先调它（`:25523`、`:25568`）。
- 结论：预设正则的真身在 `root_json.extensions.regex_scripts`，表只是可重建的缓存。

---

## 3. 编辑 UI

### 3.1 入口与组件关系

| 组件 | 位置 | 角色 |
|---|---|---|
| `GlobalPresetListModal` | `assets/c/ImageViewer-legacy-DD5gJaNv.js:15172` | 预设列表 + 操作菜单 + 删除确认；同时挂载编辑器与市场 |
| `GlobalPresetSettingsModal` | `assets/c/ImageViewer-legacy-DD5gJaNv.js:12329` | 单套预设编辑器（名称 / 提示词 / 注入参数 / 预设正则） |
| `GlobalPresetMarketModal` | `assets/c/ImageViewer-legacy-DD5gJaNv.js:11772` | 只是一层壳：`v(ba, { kind:"preset", ... })`，真正逻辑在 `assets/c/GlobalResourceMarketModal-legacy-BvchFYLb.js` |

- 聊天页设置面板发 `settings-global-preset`（`assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js:1286`），ChatView 收到后 `Et.value = true`（`ChatView-legacy-C8UPOooP.js:28886-28890`）并渲染列表弹窗（`:29181-29194`，组件 `Mr = e.c`，其中 ImageViewer 以 `r("c", ...)` 导出列表弹窗、`r("e", ...)` 导出编辑器，见 `ImageViewer-legacy-DD5gJaNv.js:15156`、`:15997`）。
- **第二个入口**：ChatView 的 ST 兼容 prompt manager 列表里，每条有铅笔/开关（`ChatView-legacy-C8UPOooP.js:28217-28330`）：
  - 铅笔 → `Tt.value = identifier; jt.value = true`（`:28305-28313`），直接打开同一个 `GlobalPresetSettingsModal`，并带 `initial-prompt-id` 深链到该条（`:29198-29221`）；
  - 开关 → 直接改 `prompt_order.enabled` 后立即落库（`:28331-28396`），不经过脏标记。

### 3.2 列表弹窗能做什么

| 操作 | 函数 | 证据 |
|---|---|---|
| 选择 / 取消选择 | `setActiveGlobalPreset(id)` / `null` | `ImageViewer-legacy-DD5gJaNv.js:15601-15616` |
| 新建 | `re()`：名字默认 `全局预设 N`，内容用空预设 `Ye()` | `:15220-15238` |
| 打开操作菜单 | `F.value = item; R.value = true` | `:15663-15675` |
| 编辑 | `oe(item)`：确保存储就绪 → `loadGlobalPreset(id)` → 打开编辑器 | `:15239-15284`、`:15759-15771` |
| 上传到市场 | `le()` → 同意条款 → `de()` → `fa(name, preset)` | `:15351-15437`、`:15793-15834` |
| 删除整套 | 确认弹窗 → `se()` → `deleteGlobalPreset(id)` | `:15439-15466`、`:15735-15873` |
| 打开市场 | `O.value = true` → `GlobalPresetMarketModal` | `:15536-15564`、`:15884-15902` |
| 保存（编辑器回调） | `ne({name, preset})` → `addGlobalPreset` 或 `updateGlobalPresetItem` | `:15285-15349`、`:15939-15963` |

### 3.3 编辑器布局与字段控件

顶部：标题「全局预设设置」、导入 / 导出按钮、预设名称按钮（`ImageViewer-legacy-DD5gJaNv.js:13774-13836`）。
中部两个 tab：「预设条目」「预设正则」（`:13837-13908`）。
底部：「取消」「保存」；隐藏的 `<input type=file accept=".json,application/json">` 负责导入（`:14918-14961`）。

字段与控件（全部在 `ImageViewer-legacy-DD5gJaNv.js`）：

| 字段 | 控件 | 证据 |
|---|---|---|
| 预设名称 | 点击后弹出单行输入弹窗（`max-length 120`） | `:12919-12929`、`:15054-15082` |
| prompt 名称 | 点击后弹出单行输入弹窗 | `:12931-12982`、`:14468-14494` |
| prompt 内容 | 点击后弹出多行文本弹窗（`max-length 100000`） | `:12965-12975`、`:15068-15069` |
| 发送身份 role | 循环按钮 `system → user → assistant` | `:13067-13110`、`:14512-14565` |
| 注入位置 | 二态切换按钮：`0` ↔ `1`，文案「相对位置 / 聊天中」 | `:13111-13113`、`:13114-13154`、`:14567-14617` |
| 注入深度 | 数字弹窗，校验 `0..9999` 整数，缺省显示 `4` | `:13010-13066`、`:12988-13000`、`:14618-14665` |
| 注入顺序 | 数字弹窗，校验 `0..9999` 整数，缺省显示 `100` | `:13010-13066`、`:14667-14715` |
| 触发条件 | 三个 chip 多选：正常回复 / 继续生成 / 重新生成（可全不选 = 全部生效） | `:12543-12547`、`:13155-13215`、`:14735-14821` |
| 启用 | 开关（`ga` 组件） | `:13260-13286`、`:14380-14451` |
| 上移 / 下移 | 两个按钮（不是拖拽） | `:14142-14252` |
| 删除本条 | 红色小按钮，**条件渲染** | `:14282-14349` |
| 添加条目 | 底部「添加提示词条目」，新建后自动打开名称弹窗 | `:13223-13259`、`:14891-14911` |
| 预设正则 | 复用正则块编辑器，`scope = preset`，隐藏启用开关 | `:13916-13963`、`:12376-12411` |

搜索框按名称/内容过滤（`:14000-14013`、`:13675-13712`）；列表空态文案明确写了「可以导入傻酒馆预设」（`:14042-14062`）。

### 3.4 增删 prompt 的细节约束

- **新增**：`qe`（导出名 `bd`）深拷贝预设，push 一个带默认值的 prompt，并在 order 末尾 push `{identifier, enabled:true}`（`e/index-legacy-B0tYRju5.js:1498-1520`）。默认值为 `system_prompt:false`、`injection_position:0`、`injection_depth:4`、`injection_order:100`、`injection_trigger:[]`、`forbid_overrides:false`。
- **删除**：`Ke`（导出名 `be`）只有在 `Wi(prompt)` 为真时才真删；`Wi` 的定义是「`identifier !== "main"` 且 `system_prompt === false`」（`e/index-legacy-B0tYRju5.js:5964-5966`、`:1522-1538`）。
  - UI 侧同样用这个判据决定**是否渲染删除按钮**（`ImageViewer-legacy-DD5gJaNv.js:14282-14286`），所以不会出现「点了没反应」。
  - 推论（静态）：`system_prompt` 缺省的导入条目在 UI 上**没有删除按钮**；`main` 永远不可删。
- **排序**：`Oa`（导出名 `bj`）只交换 `prompt_order` 中被选中分组里的两个相邻元素；越界直接原样返回（`e/index-legacy-B0tYRju5.js:1582-1601`）。
- **启用**：`Ma`（导出名 `aF`=`Yi`）在 order 中找 `identifier`，找到就改 `enabled`，找不到就补一条（`e/index-legacy-B0tYRju5.js:6083-6097`）。

### 3.5 「重置 / 导入 / 导出 / 删除」

| 操作 | 行为 | 证据 |
|---|---|---|
| 重置预设（清空内容） | 弹确认「确定要清空当前预设中的所有内容吗？预设名称会保留」→ `Te`：把 `p.value` 换成空预设；若原来是市场下载的，则用 `Vr` 把 `extensions.piupiu_preset_market` 标记补回去 | `ImageViewer-legacy-DD5gJaNv.js:12821-12857`、`:15085-15105`、`e/index-legacy-B0tYRju5.js:5217-5229` |
| 导入 | 文件选择 → `Fa`：`o.text()` → `Ze` 解析归一 → 若当前没有提示词直接「覆盖」，否则弹「检测到已有提示词数据，请选择导入方式」 | `:13365-13443`、`:14948-14961`、`:14971-15047` |
| 导入-覆盖 | `p.value = Ae(imported)`（深拷贝）+ 改名 | `:13477-13480` |
| 导入-合并 | `Qe(p.value, imported)`：只遍历导入方的**可见** prompt，已存在的替换内容并同步 enabled，不存在的追加并 push order | `:13491`、`e/index-legacy-B0tYRju5.js:1550-1577` |
| 导出 | `Ha` → `ea(p.value, u.value)`：深拷贝后 `JSON.stringify(...,null,2)`，文件名 = 预设名做安全化 + `.json`；有原生桥走 `AndroidDownloader.saveTextFile`，否则 Blob 下载 | `ImageViewer-legacy-DD5gJaNv.js:13521-13565`、`e/index-legacy-B0tYRju5.js:1578-1580`、`:5872-5921` |
| 删除整套 | 列表操作菜单 → 二次确认 → `deleteGlobalPreset` | `ImageViewer-legacy-DD5gJaNv.js:15735-15873`、`:15439-15466` |
| 未保存退出 | `Ue`：脏则弹「当前预设还有未保存的修改，确定要放弃吗？」（取消文案「继续编辑」） | `:12713-12718`、`:15126-15145` |

---

## 4. 保存流程

### 4.1 结论：编辑器只产出一个「整份预设」，父层整本替换落库（非 diff）

```
编辑器改动 → p.value（内存）
   ↓ computed ze/Ce 做脏标记
点保存 → Oe() 校验 → emit("confirm", {name, preset: 深拷贝})
   ↓ GlobalPresetListModal.ne()
store.addGlobalPreset / updateGlobalPresetItem
   ↓
replacePresetIndex(id, preset)
   ↓ 一个 SQLite 事务
DELETE regex_configs / regex_scripts / preset_configs / preset_prompts  (scope=preset)
INSERT preset_configs(root_json = preset without prompts, updated_at)
INSERT preset_prompts × N (prompt_json = 整条 prompt)
   ↓
内存索引 oe/ue 更新 + localStorage 列表写入
```

证据链：

1. **脏标记**：`ze = JSON.stringify({name, preset})`，与打开时快照 `P` 比较得到 `Ce`（`ImageViewer-legacy-DD5gJaNv.js:12537-12542`、`:13599-13602`）。
2. **保存前校验**（`Oe`，`:12719-12820`）：
   - 名字为空 → 弹名称弹窗 + toast；
   - `Ve(p.value)`（导出名 `b8`）在**可见列表**里找第一个空名 prompt → 切到该 tab 并定位；
   - 正则 tab 里存在 `scriptName`/`findRegex` 为空的行 → 切 tab 并 `focusFirstInvalidRule()`；
   - 通过后 `l("confirm", { name: t, preset: Ae(p.value) })`。
3. **父层保存**（`ne`，`:15285-15349`）：`null !== V.value` 则 `updateGlobalPresetItem(id, name, preset)`，否则 `addGlobalPreset(name, preset)`；`Z.value` 是 saving 标志，成功后关闭编辑器并 toast。
4. **store 更新**（`xe`，`e/index-legacy-B0tYRju5.js:7133-7231`）：
   - `$i(preset)` 归一；失败直接 `return`（静默不保存）；
   - `De(id)` 先读出旧预设，用 `Wr` 检测 `extensions.piupiu_preset_market`，存在则 `Vr` 把市场标记合并回新预设（防止编辑器把「市场来源」弄丢）；
   - `replacePresetIndex(id, normalized)`；
   - 更新内存索引条目的 `name/marketDownloaded/totalPromptCount/enabledPromptCount/updatedAt`，并更新 `o` 缓存。
5. **落库**（`Im`/`Nm`，`e/index-legacy-B0tYRju5.js:24223-24403`）：单个 `transaction` 内先删四个表的 preset 相关行，再插入 `root_json` 与逐条 `prompt_json`。**是整本替换，不做行级 diff。**
6. **索引持久化**：`ce()` 把 `piupiu_global_preset_list` 和 `piupiu_active_global_preset_id` 写回 localStorage（`e/index-legacy-B0tYRju5.js:6774-6783`）。注意 `ce()` 只在「旧版迁移 map `i` 为空」时才写列表（`:6775-6776`）。
7. **取消**：`Ue()` 不写任何东西；若脏则只弹确认框（`ImageViewer-legacy-DD5gJaNv.js:12716-12718`）。`saving` 或提交中时按钮禁用（`ge` computed，`:12484-12486`）。

**草稿索引的特殊路径**：新建预设（`indexId` 为空）时，编辑器会把当前草稿整体写进 SQLite 的哨兵 id `2147483647`（`Ol`，`ImageViewer-legacy-DD5gJaNv.js:12327`、`:12477`、`:12612-12649`），目的是让列表分页/摘要查询 `listPresetPromptSummaries` 能工作；正式保存后再写到真实 id。关闭编辑器时**没有看到**对哨兵行的清理调用（全仓仅两处出现该常量）。

---

## 5. 导入 / 导出 / 市场

### 5.1 接受的 JSON（文件导入）

- `accept=".json,application/json"`（`ImageViewer-legacy-DD5gJaNv.js:14948-14961`）。
- 解析：`JSON.parse` → `$i()`；错误文案「文件不是有效的 JSON」「没有找到有效的 SillyTavern 预设数据」（`e/index-legacy-B0tYRju5.js:1539-1549`）。
- 形状要求：顶层对象且 `prompts` 或 `prompt_order` 至少一个是数组；`prompts[]` 用 `identifier` 作为键（不是数字 `id`）。
- 大小限制：前端未见（市场上传有 5 MiB 限制，见 5.4）。

### 5.2 产出的 JSON（文件导出）

- 内容 = 内存里的整份预设（`prompts` + `prompt_order` + `extensions` + 所有未知顶层字段），`JSON.stringify(x, null, 2)`。
- 文件名 = `Oi(presetName, "global-preset") + ".json"`，即把空白/标点替换成 `_`（`e/index-legacy-B0tYRju5.js:5872-5882`、`:1578-1580`）。
- 正则没有独立文件：随 `extensions.regex_scripts` 一起导出。

### 5.3 设置备份里的预设（第三态）

- 导出：`exportGlobalPresetItems()` 逐条 `loadGlobalPreset`，产出 `[{id, name, preset}]`（`e/index-legacy-B0tYRju5.js:7449-7491`），塞进设置备份 JSON 的 `settings.globalPresets`，同时带 `settings.activeGlobalPresetId`（`assets/c/SettingsView-legacy-CYxjl7kQ.js:2767-2808`）。
- 文件名 `PiuPiu_SettingsBackup_<时间戳>.json`（`SettingsView-legacy-CYxjl7kQ.js:2831`）。
- 导入：校验 `app`/`schemaVersion === 1` 后，若 `globalPresets` 是数组则 `replaceGlobalPresets(items, activeId)`（`SettingsView-legacy-CYxjl7kQ.js:2846-2848`、`:2952-2965`）。
- `replaceGlobalPresets`（`e/index-legacy-B0tYRju5.js:7298-7448`）：先删掉现有全部预设索引行，再逐条 `replacePresetIndex`，最后整体替换索引并设置 active。**是「全量替换」，不是合并。**

### 5.4 市场（GlobalPresetMarketModal）与文件导入的差异

- `GlobalPresetMarketModal` 本身没有逻辑，只把 `kind:"preset"` 传给通用市场组件（`ImageViewer-legacy-DD5gJaNv.js:11772-11799`）。
- 端点：`/preset_market.php`（`assets/c/GlobalResourceMarketModal-legacy-BvchFYLb.js:3070`），也在业务请求签名白名单内（`app-security-legacy-BdXLf8ML.js:7508`）。
- 下载：
  - `action:"download", id`（`GlobalResourceMarketModal-legacy-BvchFYLb.js:3197`）；
  - 返回 `data_base64` / `data_sha256` / `data_size` / `download_count`，先校验 `data_base64.length` 不超过 `4 * ceil(1769472)`（约 5 MiB 原文）并与 SHA-256 比对（`:3200-3216`）；
  - 解析后套上市场标记 `extensions.piupiu_preset_market = { id: 市场条目id, downloadedAt, uploadLocked:true }`（`:3164-3172`），再 `addGlobalPreset(name, preset)`（`:3776-3781`）。
  - 结果：列表里的 `marketDownloaded` 为真（由 `Wr` 判定，`e/index-legacy-B0tYRju5.js:5203-5216`）。
- 上传：
  - 必须登录（`GlobalResourceMarketModal-legacy-BvchFYLb.js:3110-3114`）；
  - 市场下载来的预设拒绝再上传（`:3116-3120`）；
  - 请求体 `{ action:"upload", name, preset_json }`，`preset_json` 是整份预设的 JSON 字符串，超过 5 MiB 拒绝（`:3122-3137`）。
- 与文件导入的核心差异：
  1. 市场多一个**来源标记** `extensions.piupiu_preset_market`，并据此禁止二次上传；
  2. 市场下载/上传要登录 + 走签名请求；
  3. 市场条目 id 存进预设 `extensions`，但**不影响**本地 `id`（本地 id 仍是 `Date.now()` 递增）。
- 角色大厅也有「预设」市场分类（`assets/c/RoleHallView-legacy-OCYgw5tR.js:908-913`、`:12314`）；其市场列表是否复用同一个通用市场组件，本次未逐行验证。

### 5.5 「从角色卡导入预设」

- 结论：**本次审计未在前端找到「角色卡 → 预设」的导入路径。** 预设的导入来源只有四条：编辑器 JSON 文件、设置备份 JSON、市场下载、ST 兼容 JS API（`savePreset` / `updatePresetWith`）。
- 旁证：`RoleHallView` 里 `preset` 只作为市场分类（`RoleHallView-legacy-OCYgw5tR.js:908-913`、`:12314`）；角色卡导入/上传流程里没有 `prompt_order` / `preset_json` 相关字样。
- 如果「角色卡导入预设」指的是 ST 卡内嵌的 `preset` 字段，需要再查卡片解析链（见第 9 节未验证项）。

---

## 6. 内置预设

### 6.1 结论：有「内置默认预设」，但它不在 JS 包里，而是运行时从 `/presets/default-global-preset.json` 拉取后写入本地

- 安装器：`installDefaultGlobalPreset(store, localStorage)`（`assets/c/defaultGlobalPreset-legacy-tjZgE48x.js:290-618`）。
- 常量：
  - 安装标记键 `piupiu_default_global_preset_v2`（`:619-622`）；
  - 内容版本键 `piupiu_default_global_preset_content_version`（`:623-626`）；
  - 内容版本值 `20261012-helper-frontends-1`（`:627-630`）；
  - 预设名 `双人成行`（`:631`）。
- 流程（`:332-587`）：
  1. `ensureGlobalPresetStorageReady()`；
  2. 已安装且内容版本一致 → 直接返回；已安装、版本变了、但同名预设**已被用户删掉** → 只写新版本号后返回（不会复活被删的预设，`:342-352`）；
  3. 其余情况（未安装，或同名预设仍在）才 `fetch("/presets/default-global-preset.json?v=" + 版本)`（`:353-362`），失败抛「默认预设读取失败，请重试」；
  4. 校验至少有一条 `main` 且内容非空、且 `prompt_order` 里 `main` 是启用状态，否则抛「默认预设内容不完整」（`:373-385`）；
  5. 若本地已有同名预设：只有当旧内容版本正好是 `20261010-neutral-expanded-1` 时，才先 `loadGlobalPreset` 读出旧预设并合并 `extensions` 里的正则/脚本（`:406-418`、`:429-558`）；否则直接用默认内容 `updateGlobalPresetItem` 覆盖（`:554-558`）。同名预设可能有多条，会逐条处理；
  6. 若没有同名预设：`addGlobalPreset("双人成行", preset)`（`:572-579`）；
  7. 若当前没有选中预设，自动把它设为 active（`:580-585`）；
  8. 写安装标记与内容版本号（`:585-586`）。
- 调用点：App 启动、数据库初始化与备份恢复之后（`assets/e/index-legacy-B0tYRju5.js:33723-33764`）。
- **没有任何固定的内置预设 id**：id 由 `he()` = `Date.now()`（冲突则 +1）在添加时生成（`e/index-legacy-B0tYRju5.js:6940-6949`）；`/presets/default-global-preset.json` 的实际内容不在前端 bundle 内（见未验证项）。

### 6.2 `is_builtin` 的用途

- 来源：`Ui(prompt)` —— `identifier` 属于 12 个内置标记或 `marker === true`（`e/index-legacy-B0tYRju5.js:5961-5963`、`:5923-5936`）。
- 写入：`preset_prompts.is_builtin`（`e/index-legacy-B0tYRju5.js:24356`）。
- 读取：**只有一处**——提示词摘要查询用 `(is_builtin = 0 OR prompt_id = 'main')` 过滤（`e/index-legacy-B0tYRju5.js:25230-25243`），即「列表里只显示自定义条目 + `main`，其它内置标记（`worldInfoBefore` / `charDescription` / `chatHistory` …）全部隐藏」。
- 同一个判据也被 `Gi()` 用于「可见列表」（`e/index-legacy-B0tYRju5.js:6060-6063`），所以内置标记既不可见、也不会被误删。
- 注意：**预设行本身没有「内置」标记**。`preset_configs` 没有类似列，`is_builtin` 只描述单条 prompt 是否为内置占位标记。内置默认预设安装后就是一条普通预设记录，可以像用户预设一样改名、编辑、导出、删除。

---

## 7. 字段 → 存储列 → 默认值 → 编辑入口

| 字段 | 存储列 | 缺省/默认值 | 编辑入口 |
|---|---|---|---|
| `prompts[].identifier` | `preset_prompts.prompt_id` | 缺失或重复 → 新 UUID v4（`e/index:5992`、`app-security:7691-7717`） | 只读；新建时自动生成，不可编辑 |
| `prompts[].name` | `preset_prompts.prompt_name` | `""`（`e/index:5999`） | 条目「名称」按钮 → 单行弹窗（`ImageViewer:14468-14494`） |
| `prompts[].role` | `preset_prompts.role` | `system`（非 user/assistant 一律回落，`e/index:5949-5951`） | 「发送身份」循环按钮（`ImageViewer:14512-14565`） |
| `prompts[].content` | `preset_prompts.content` | `""`（`e/index:6001`） | 条目「提示词内容」按钮 → 多行弹窗（`ImageViewer:14829-14890`） |
| `prompts[].injection_position` | `preset_prompts.injection_position` | `0`（`e/index:24357-24363`） | 「注入位置」二态切换（`ImageViewer:14567-14617`） |
| `prompts[].injection_depth` | `preset_prompts.injection_depth` | `4`（写库 `e/index:24364-24370`；编辑器显示兜底同值 `ImageViewer:13047-13050`；提示文案见 `:14726`） | 「注入深度」数字弹窗（`ImageViewer:14618-14665`） |
| `prompts[].injection_order` | `preset_prompts.injection_order` | `100`（`e/index:24371-24377`） | 「注入顺序」数字弹窗（`ImageViewer:14667-14715`） |
| `prompts[].injection_trigger` | `preset_prompts.injection_trigger_json` | `[]`；只保留 `normal/continue/regenerate`，`swipe/impersonate/quiet` 归一为 `normal`（`ImageViewer:12247-12276`） | 「触发条件」三 chip 多选（`ImageViewer:14735-14821`） |
| `enabled`（在 `prompt_order[].order[]` 上） | `preset_prompts.enabled` | `true`（只有显式 `false` 才关，`e/index:6024`） | 条目「启用提示词」开关（`ImageViewer:14380-14451`） |
| `prompt_order` 中的可见下标 | `preset_prompts.visible_order` | `-1`（不在 order 中/内置，`e/index:24344-24348`） | 只读派生；由 order 位置决定 |
| 内置判定 | `preset_prompts.is_builtin` | `0`（`e/index:24356`） | 只读派生；无 UI |
| `prompts[].system_prompt` | 无独立列（在 `prompt_json`） | 不补（导入时可能缺失；新建时 `false`） | 无直接入口；决定删除按钮是否渲染 |
| 其它未知 prompt 字段（`extra`、`forbid_overrides`、`_piupiu_*`…） | `preset_prompts.prompt_json` | `{}` | 无（原样往返） |
| `prompts` 数组顺序 | `preset_prompts.prompt_index` | 数组下标 `0..n-1` | 无直接 UI（增删时自然变化） |
| `prompt_order` | `preset_configs.root_json.$.prompt_order` | `[{character_id:100001, order:[]}]`（`e/index:5943-5948`） | 上移/下移 + 启用开关（`ImageViewer:14142-14252`、`:13260-13286`） |
| 顶层其它字段（`temperature`、`top_p`、`openai_max_tokens`…） | `preset_configs.root_json` | 无（原样保留） | 无 UI |
| `extensions.regex_scripts` | `preset_configs.root_json`（派生到 `regex_scripts` 表） | 无 | 「预设正则」tab（`ImageViewer:13916-13963`） |
| `extensions.piupiu_preset_market` | `preset_configs.root_json` | 无 | 只读；市场下载写入，保存时保留（`e/index:5217-5229`、`:7164-7166`） |
| 预设显示名 | 仅 localStorage 索引 `name` | 新建时 `全局预设 N` / `未命名全局预设`（`e/index:6950-6962`、`:6632-6635`） | 「预设名称」按钮（`ImageViewer:13815-13836`） |
| 预设 id | 内存索引 `id` + `preset_configs.preset_id` | `Date.now()`，冲突 +1（`e/index:6940-6949`） | 不可编辑 |
| 预设计数 / 市场来源 | localStorage 索引 `totalPromptCount` / `enabledPromptCount` / `marketDownloaded` | 由 `Gi()`/`Ji()`/`Wr()` 派生（`e/index:6636-6639`） | 只读派生 |

---

## 8. UI 操作 → 内部函数 → 影响字段

| UI 操作 | 内部函数（导出名） | 影响的字段 |
|---|---|---|
| 设置面板点「全局预设」 | `onSettingsGlobalPreset` → `Et=true` → `GlobalPresetListModal`（`ChatView:28886-28890`、`:29181-29194`） | — |
| 列表里点某套装 / 取消 | `setActiveGlobalPreset(id/null)`（`ImageViewer:15601-15616`） | `piupiu_active_global_preset_id` |
| 「添加全局预设」 | `re()` + 编辑器 `addGlobalPreset`（`ImageViewer:15220-15238`、`e/index:7058-7132`） | 新建 `preset_configs` + N 条 `preset_prompts` + 索引项 |
| 操作菜单「编辑」 | `oe(item)` → `loadGlobalPreset(id)`（`ImageViewer:15239-15284`、`e/index:6964-7057`） | —（只读加载） |
| 改「预设名称」 | `sa()` → 弹窗 → `pa("presetName")` 写 `u.value`（`ImageViewer:12919-12929`、`:12983-13009`） | 索引 `name`（保存时） |
| 改 prompt 名称 | `ua(id,"promptName")` → `pa` → `$e`（`e/index:1476-1497`） | `prompts[].name` → `prompt_name` |
| 改 prompt 内容 | `ua(id,"promptContent")` → `pa` → `$e` | `prompts[].content` → `content` |
| 改「发送身份」 | `za()`（`ImageViewer:13067-13110`） | `prompts[].role` → `role` |
| 切「注入位置」 | `Sa()`（`ImageViewer:13114-13154`） | `prompts[].injection_position` → `injection_position` |
| 改「注入深度」/「注入顺序」 | `fa(id, field)` → `pa` 校验 0..9999（`ImageViewer:13010-13066`、`:12986-13001`） | `prompts[].injection_depth` / `injection_order` |
| 点「触发条件」chip | `_a(id, value)`（`ImageViewer:13155-13215`） | `prompts[].injection_trigger` → `injection_trigger_json` |
| 「启用提示词」开关 | `Ma(id, enabled)` → `Xe`（`e/index:6083-6097`） | `prompt_order[].order[].enabled` → `enabled` |
| 上移 / 下移 | `Oa(id,"up"/"down")` → `ta`（`e/index:1582-1601`） | `prompt_order` 相邻元素交换（不动 prompts） |
| 「添加提示词条目」 | `Pa()` → `qe`（`e/index:1498-1520`）+ 自动开名称弹窗（`ImageViewer:13223-13259`） | push `prompts[]` + order 末尾 |
| 条目「删除」 | `Ba()` → `Ke`（`e/index:1522-1538`） | 从 `prompts` 与所有 order 组里移除 |
| 「重置预设」 | `Te()` → `Ye()` / `Vr()`（`ImageViewer:12821-12857`） | 清空 `prompts`、`prompt_order`，保留名称与市场标记 |
| 「导入预设」 | `Ra()`（点隐藏 input）→ `Fa` → `Ze`（`e/index:1539-1549`） | 覆盖：整份替换；合并：`Qe`（`e/index:1550-1577`） |
| 「导出预设」 | `Ha()` → `ea`（`e/index:1578-1580`） | 无（只读导出） |
| 「保存」 | `Oe()` → emit `confirm` → `ne()` → `add/updateGlobalPresetItem` → `Im`（`e/index:24223-24403`） | 整本替换落库 + 索引元数据 |
| 「取消」 | `Ue()`（`ImageViewer:12716-12718`） | 无（脏则只弹确认） |
| 列表「删除」 | `se()` → `deleteGlobalPreset` → `Pm`（`e/index:24405-24480`） | 删 4 张表对应行 + 索引项 |
| 列表「上传」 | `le()` → `de()` → `Jt`（`GlobalResourceMarketModal:3102-3145`） | 仅服务端；不改本地预设（除刷新计数） |
| 市场「下载」 | `Ve()` → `Wt` → `addGlobalPreset`（`GlobalResourceMarketModal:3178-3225`、`:3776-3781`） | 新预设 + `extensions.piupiu_preset_market` |
| ST prompt manager 的铅笔 | `Tt=identifier; jt=true`（`ChatView:28305-28313`） | 打开编辑器并深链 `initial-prompt-id` |
| ST prompt manager 的开关 | `Xe` + `updateGlobalPresetItem`（`ChatView:28331-28396`） | `prompt_order.enabled`，立即落库 |
| 设置备份导出 | `exportGlobalPresetItems()`（`e/index:7449-7491`） | 无（生成 `[{id,name,preset}]`） |
| 设置备份导入 | `replaceGlobalPresets(items, activeId)`（`e/index:7298-7448`） | 全量替换所有预设 |

---

## 9. 未验证 / 待复核

1. **`visible_order` 无人读取。** 全仓检索只见建表、索引 `idx_preset_prompts_order` 与写入（`e/index:24340-24348`），没有任何 `ORDER BY visible_order` 或 `SELECT ... visible_order`。是否有原生侧/未来用途未验证。
2. **草稿哨兵 id `2147483647` 的清理。** 全仓只有 `ImageViewer:12327`、`:12477` 两处出现；关闭/取消编辑器时未见删除该 id 行的调用。运行期是否残留一行 `preset_configs`/`preset_prompts` 未验证。
3. **正则 tab 的加载门控语义。** `V("regex")` 分支用 `!ce.value` 判断是否去读 DB 摘要（`ImageViewer:12380-12386`），`ce` 是「提示词摘要已加载」缓存。本文推断「草稿模式用内存 extensions，DB 模式读派生索引」，但未运行验证。
4. **重复 `identifier` 归一化的后果。** `$i` 把重复项改成 UUID（`e/index:5992`），但 `prompt_order` 未同步改写，理论上会让该条变成「不可见」。未构造样本验证。
5. **`extensions.regex_scripts` 与正则表的同步时机。** `ensurePresetRegexIndexed` 以 `updated_at` 相等为「已同步」判据（`e/index:25452-25460`）；同一毫秒内保存两次是否会漏重建，未验证。
6. **内置默认预设的实际内容。** `/presets/default-global-preset.json` 不在前端 bundle 内，本次没有读取该资源；提示词条数、是否带 `is_builtin` 标记、正则/脚本内容均未验证。
7. **「角色卡 → 预设」。** 前端未找到直接路径（见 5.5）；若指角色卡内嵌 `preset`/`chatCompletionSettings`，需另查卡片解析与导入链，本次未覆盖。
8. **`presetVariables`。** 兼容层另有一个 `piupiu_tavern_helper_preset_variables` localStorage 键（`GroupMemberSelector-legacy-BIJ8h7lR.js:18457`），它与本表存储的预设内容是两套数据；作用域是否按预设名区分未验证。
9. **`GlobalPresetSettingsModal` 由 ChatView 直接挂载时的 `initial-preset` 来源。** `Zt.value` 的赋值点有多处（`ChatView:10698`、`:11309`、`:21513`、`:22020`、`:28383`），本文只核对了 prompt manager 与保存回调两处，其余入口的语义未逐一验证。
10. **`preset_configs` 与 localStorage 索引的一致性。** 若 localStorage 索引丢失而 DB 仍在，预设是否真的不可恢复，本文是按代码路径推断（`e/index:6984-6992`），未做破坏性验证。

---

## 附：一次性检索命令（复现用）

```powershell
$f='D:\Canxu-project\PiuPiu-source\frontend-readable\assets\e\index-legacy-B0tYRju5.js'
Select-String -Path $f -Pattern 'prompt_order','preset_configs','preset_prompts' -SimpleMatch -Encoding utf8
$g='D:\Canxu-project\PiuPiu-source\frontend-readable\assets\c\ImageViewer-legacy-DD5gJaNv.js'
Select-String -Path $g -Pattern '__name: "GlobalPreset','injection_position','prompt_order' -SimpleMatch -Encoding utf8
```
