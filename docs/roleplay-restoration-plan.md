# 角色扮演能力后续还原计划：优先复用已恢复代码

日期：2026-11-01。对照目标：PiuPiu 酱 Android 2.7.9。状态：已开始第一批移植；块宏、字符串累加和查找式宏转义已有实现及核心回归；`openai.js` / `utils.js` 已接入并完成离线桥接检查。脚本树、MVU、EJS 及完整宿主适配仍待实现。

## 1. 结论与实施原则

现有材料足以继续补齐多项客户端能力。优先抽取已恢复 JavaScript 的语义实现、替换宿主接口；短小且确定的数据操作可以逐语义移植到 Kotlin。MD3 外壳、加密存储、自定义 API 和本地模型管理继续由残叙·残念负责。

最高收益在宏/正则语义、TavernHelper 脚本运行时、MVU 状态与事件、EJS 模板，以及生成前后的扩展钩子。补齐这些部分可以让已有复杂角色卡真正执行，而不仅是保留其 JSON 或显示 HTML。

已恢复的是构建产物。文件名不是业务边界：`ImageViewer` 中有宏解析器，`GroupMemberSelector` 中有脚本运行时与 EJS，入口 `index` 中有正则与世界书。整体复制某个 chunk 会同时带入 Vue/Pinia 页面、原数据层及无关服务依赖；应按函数和字面量抽取依赖闭包。

尚未移植的条目均为静态可行性判断。原资源完整恢复不等于这些模块已经脱离原应用独立运行；必须经过抽取、依赖适配和行为对照才能标为移植完成。

## 2. 现成代码与当前差距

| 优先级 | 能力 / 代码入口 | 当前残叙·残念差距 | 复用方式与工作量判断 |
| --- | --- | --- | --- |
| P0 | `ImageViewer` 的宏解析、变量展开和字符串累加 | 简单正则反复匹配不等价于嵌套/块宏；`addvar` 仅数值累加，文本被当成 0；`trim` 仅删标签 | 小缺口可先逐语义移植；完整块宏优先抽取 JS。直接影响文风、条件设定和预设组合 |
| P0 | 入口 `index` 的 JS 正则执行路径 | 使用 JVM Regex；`substituteRegex=1/2` 未区分，模式 2 应对替换进查找式的宏值做正则转义 | 抽取规则筛选、替换和 JS RegExp 语义；少量前置缺口可先修 Kotlin。需统一提示词/显示/变量处理阶段 |
| P0 | `GroupMemberSelector.Sd()` 生成的 TavernHelper 运行时 | 只有基础变量、消息及生成接口；缺脚本树、脚本按钮、多个作用域、完整事件及宿主能力 | 嵌入式脚本文本适合直接提取；消息运输层、存储和 `hostApi` 需改造，不能只复制导出名称 |
| P0 | MVU 更新、Zod 注册、初始化/重放链 | MVU 是小型文本语法解释器；事件名、初始化时间、消息/候选快照与原版链路不同 | 复用操作解析、状态更新与 schema/事件实现；宿主负责事务、保存、同步，工作量较高 |
| P1 | EJS 引擎与 PiuPiu 模板包装 | 尚无对应模板执行链；仅支持 HTML/JS 渲染不足以运行 EJS 条件和动态内容 | 已有本地 EJS 引擎及包装函数，复用引擎和包装语义；补齐变量读写、阶段和副作用提交 |
| P1 | `ChatView.ContextBuilder.buildAsync`、入口 `index` 世界书算法 | 预设、世界书已有较大子集，但扩展钩子、特殊 marker、扫描来源和组评分等仍有差异 | 按功能抽取或对照移植，依赖较多；先复用宏、正则、MVU 基础再接入 |
| P1 | `scripts/world-info.js`、`openai.js`、`preset-manager.js` | 目前模块拦截返回简化对象，部分只给空数据 | 小模块本身可直接复用；仍须实现背后的世界书 CRUD、预设管理和生成宿主接口 |
| P1 | 预设/世界书/脚本可操作编辑界面 | 资料编辑主要是原始 JSON；复杂预设条目缺逐项开关和排序 | 复用规范化、校验和顺序规则；MD3 条目编辑界面由 Compose 实现 |
| P2 | Markdown/HTML 消息渲染、脚本生命周期与媒体解析 | 普通消息 Markdown 简化；消息 WebView 生命周期随 Compose 项目销毁；嵌入 HTML/CSS 的媒体引用未完整解析 | 抽取渲染规则和相应库，重接本地媒体 URI；会话级脚本生命周期与消息展示分离 |
| P2 | RAG 查询与注入组织 | 当前固定取最近 3 条拼接查询，按相似度顺序注入；原版可配置查询范围并恢复时间顺序 | 复用查询、筛选和文本组织规则，保留残叙·残念的可切换模型与索引身份；不盲目复制阈值或随机回退 |

### 2.1 宏与预设：可立即定位的差异

- 原版 `Oi()` 在双方可数值化时求和，否则拼接文本；残叙·残念 `addvar` 目前只计算数值。多项文风拼接会受影响。[原版字符串/数值累加](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/c/ImageViewer-legacy-DD5gJaNv.js:12028)，[现有宏实现](D:/Roleplay/Xujing/core/src/main/kotlin/app/xujing/core/engine/MacroProcessor.kt:74)。
- 原版 `Pi/Ni/Ei` 识别宏节点、嵌套与配对结束标签，`Ti` 处理块级 `else`；残叙·残念主要匹配无花括号的宏体并重复展开。应对照 `setvar` 块、`if/else`、注释和嵌套参数，而不是仅增加几个名称。[原版宏解析](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/c/ImageViewer-legacy-DD5gJaNv.js:11949)，[块级 else](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/c/ImageViewer-legacy-DD5gJaNv.js:12234)。
- 原版正则 `substituteRegex=2` 对宏值转义后再构造查找式；当前对模式 1/2 做相同展开。[原版正则路径](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/e/index-legacy-B0tYRju5.js:1169)，[现有正则路径](D:/Roleplay/Xujing/core/src/main/kotlin/app/xujing/core/engine/RegexProcessor.kt:44)。
- `maxresponse` 等宏也应读实际生效的连接/预设参数，与上一轮已修正的上下文预算一致。[现有参数宏](D:/Roleplay/Xujing/core/src/main/kotlin/app/xujing/core/engine/MacroProcessor.kt:62)。
- APK 已有约 1.23 MB、251 个提示词条目的默认预设，可作为复杂组合的兼容检查材料；不应直接启用全部条目或沿用其 30,000 输出 token 默认值。它不是新的模型能力。[默认预设资料](D:/REA-Android/targets/piupiu-source/apktool/assets/public/presets/default-global-preset.json)。

### 2.2 脚本与 MVU：最值得直接提取的区域

- `Sd()` 由字面量和序列化会话数据构造运行时。30929 行包含事件系统；30934 行包含 MVU 事件名、`registerMvuSchema` 和 `safeParse`；30986 行提供消息、世界书、正则操作；30994 行提供提示注入、生成和取消；31035 行提供预设管理入口。[运行时构造入口](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js:30900)。这些嵌入字符串必须用语法树/字面量解码工具提取，避免靠行号裁剪或执行整个 chunk。
- 运行时已有 `options.hostApi[method]` 边界。宿主执行操作后刷新 frame 缓存，回传 `chatMessages`、`scopedVariables` 和 `worldbookContext`，可以替换成残叙·残念的宿主实现。[现成宿主接口](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js:32604)。
- 原版明确等待 InitVar/重放持久化、同步时间线后才视为 MVU 已初始化。残叙·残念当前在 `DOMContentLoaded` 就发送初始化事件，不能保证脚本拿到最终状态。[原版初始化约束](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js:30921)，[残叙·残念初始化](D:/Roleplay/Xujing/app/src/main/assets/card-runtime.js:52)。
- 原版内嵌 jQuery、Lodash、Vue、Pinia、Vue Router、Zod、YAML 的运行时字符串，装载点已找到。优先按卡片实际依赖提取，记录版本与许可头；不默认把全部库装入每条消息。[库装载点](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js:31143)。
- 不能把角色 JSON 内“保存了脚本树”当成“执行了脚本树”。需接入提取/规范化、启用过滤、加载顺序、按钮注册、销毁和状态保存。[脚本树规范化入口](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/c/tavernHelperScriptStorage-legacy-BVIVfja_.js:361)。

### 2.3 EJS、提示词与世界书

- 本地代码包含 EJS 3.1.10 及许可头；包装层支持条件指令、异步模板、`getvar/setvar`、变量结果。只替换 `<%=...%>` 无法覆盖这些行为。[EJS 引擎](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js:27398)，[模板包装](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js:28118)。
- 主聊天构造提示词后还会调用 `chat_completion_prompt_ready`、`generate_after_data`，允许扩展改写实际发出的 messages；残叙·残念目前没有这一整条钩子链。[主聊天钩子顺序](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/c/ChatView-legacy-C8UPOooP.js:27025)。需明确每个阶段的输入、可写内容及取消语义，防止变量更新执行两次。
- 世界书原版有 `useGroupScoring`、`matchCharacterDepthPrompt`、`matchCreatorNotes` 等实际计算路径；当前 Kotlin 实现尚未逐项覆盖。[组评分](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/e/index-legacy-B0tYRju5.js:20817)，[附加扫描来源](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/e/index-legacy-B0tYRju5.js:22544)。
- 群聊已找到 `targetRoleId/@姓名` 识别及 `group_targeted_reply/group_general_reply` 提示词分支，应先还原这两条路径。当前证据不足以把“自动连续生成多名成员回复”列为原版已确认能力。[群聊目标识别](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/c/ChatView-legacy-C8UPOooP.js:5251)，[群聊提示分支](D:/REA-Android/targets/piupiu-source/frontend-readable/assets/c/ChatView-legacy-C8UPOooP.js:5343)。

### 2.4 可以复制的小模块仍需要真实宿主

`scripts/world-info.js` 转接 `TavernHelper`，`preset-manager.js` 转接当前上下文，`openai.js` 提供 Message/MessageCollection/promptManager 等类型与接口。复制它们能解决导入路径和对象形状，无法自动实现读写、存储或生成。`script.js` 的空闲 `streamingProcessor=null` 也是兼容声明，不是原版完整流处理器。

[world-info](D:/REA-Android/targets/piupiu-source/frontend-readable/scripts/world-info.js:1)、[openai](D:/REA-Android/targets/piupiu-source/frontend-readable/scripts/openai.js:1)、[preset-manager](D:/REA-Android/targets/piupiu-source/frontend-readable/scripts/preset-manager.js:1)、[根模块](D:/REA-Android/targets/piupiu-source/frontend-readable/script.js:1)。

## 3. 建议的宿主结构

1. **Compose 保留 MD3 界面，Kotlin 保持唯一持久数据源。** API 密钥、连接、消息、资料库和模型由现有宿主管理。
2. **建立会话级兼容运行时，消息 WebView 负责展示。** 脚本不因列表滚动、重组或某条消息离屏而重复初始化；切换/关闭会话时释放事件和任务。可优先利用已有 Android WebView 的 JS 环境，第一阶段不引入另一套大型 JS 引擎。
3. **纯算法入口和卡片脚本分开。** 内置宏/正则算法的 JS 执行不等于开启任意角色脚本；保留用户对卡片脚本的现有开关。
4. **最小 JSON 宿主契约。** 覆盖消息及候选、变量作用域、资料库 CRUD、提示词钩子、生成/取消、事件回传。脚本回调函数在 JS 内执行，结果变为数据操作提交给 Kotlin，不尝试跨桥传递函数对象。
5. **保存成功后同步和发事件。** 使用稳定消息 ID、候选索引、状态版本号避免旧 frame 覆盖新状态；提供索引到稳定 ID 的映射。脚本要求同步读取的接口从已确认快照读取，写入失败回滚并明确报错。
6. **逐功能确定唯一语义实现。** 引入原版 JS 后，对应 Kotlin 路径退出主链或明确限定用途，避免同一轮宏、MVU、模板和正则被处理两遍。

## 4. 分阶段交付与验收

| 阶段 | 交付内容 | 验收标准 |
| --- | --- | --- |
| A：抽取与最小接入 | 建立源文件/函数/字面量/哈希/许可头清单；提取模块包装、宏解析、运行时脚本文本；定义宿主契约；先修 addvar/trim/substituteRegex 等确定缺口 | 抽出模块能在受控宿主中加载；不依赖原账号、原数据库或官方服务；有真实读写能力的接口与仅保留接口形状的项逐项标记 |
| B：宏与正则对齐 | 块宏、嵌套、条件、变量文本拼接、原版替换规则和各处理阶段 | 同一输入、相同时间/随机源，原版提取函数与残叙·残念得到相同文本、变量和规则命中；未知语法有诊断，不静默吞掉 |
| C：复杂卡片可执行 | 脚本树、按需本地依赖、事件、作用域、MVU 初始化与更新、Zod、EJS、动态世界书 | 状态栏按钮可修改资料/变量；消息和候选各有正确快照；流式中途停止、切候选、编辑、回溯、重开应用均不重复执行副作用或串会话 |
| D：生成链与可操作预设 | 原版提示注入/生成钩子、特殊 marker、世界书边界、指定/普通群聊分支；MD3 条目开关与排序 | 本地 mock 捕获的最终 messages 与受控参考输出对齐；世界书命中、顺序、位置和状态可解释；开关改变会实际影响下一轮请求 |
| E：表现与记忆 | Markdown/HTML、媒体 URI、模板显示刷新；可配置 RAG 查询与时间排序 | 多张代表性复杂卡完整操作通过；旧资料导入后脚本实际运行且可再次导出；检索模型身份不混用。真实模型质量单独评估 |

建议下一次实现先交付 A，并同时完成 B 中最小的宏/正则缺口；随后集中完成 C，再做 D。自动摘要、流式停止及基础资料备份上一轮已完成，后续以回归保护为主。

## 5. 验证方法与完成口径

- 用不含私有聊天的合成样本固定：时间、随机源、角色、预设、世界书、输入和候选。先建立原版提取函数的参考输出，再比较残叙·残念输出；暂不以模型自由回复是否相似作为兼容验收。
- 每张卡记录四个状态：数据可导入、文本/HTML 可呈现、交互/脚本可执行、状态可保存并回导。不能用其中一个状态代表全部兼容。
- 代表性样本至少包含：纯文本卡、嵌套宏预设、JS 正则卡、普通 MVU 卡、Zod MVU 卡、EJS 动态世界书卡、带脚本按钮的 HTML 卡、指定/普通群聊卡。
- 沿用本地模拟 API 捕获提示词、流事件和取消行为；模拟器保持运行，优先使用 ADB。真实模型效果与客户端确定行为分别记录。
- 对第三方模块保留可见许可头，每个提取模块记录来源、原始 SHA-256、修改和宿主适配点。当前包中的 PiuPiu 兼容层本身也不是完整 SillyTavern，超出其已观察行为的能力另行列为扩展。

计划排除云端内部行为、账号/邀请/VIP/社区/市场、Live2D；语音与生图继续按已讨论范围后置。原版未初始化模型时的随机记忆回退、固定客户端服务材料和过大的预设默认预算不作为必须复制的行为。
