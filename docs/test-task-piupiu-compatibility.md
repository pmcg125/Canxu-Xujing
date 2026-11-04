# 测试任务：PiuPiu 兼容细节与聊天 HTML / 脚本运行时

**交接对象：** 测试 Agent  
**项目：** `D:\Canxu-project\Xujing`（Android / Jetpack Compose）  
**任务日期：** 2026-11-04  
**目标：** 验证本轮源码接入的 HTML 分段显示、会话脚本 iframe 隔离、按钮与事件传递，以及正则编辑和世界书边界行为。当前工作树已有未提交改动；测试前先记录状态，禁止清理、重置或覆盖这些改动。

## 测试边界

- 本任务授权测试 Agent 运行本文列出的自动化检查和构建，并在可用的 Android 模拟器上做手工验证。
- 不修改 `app/`、`core/` 下的生产实现，不提交代码，不执行 `git reset`、`clean`、`checkout`、`stash` 等会覆盖或移走现有改动的操作。
- 如需修正过时的测试夹具，只改测试夹具并单独记录差异；如果怀疑生产代码有问题，保留复现步骤与日志，交回实现方处理。
- 使用临时角色、临时会话和虚构对话。优先用项目本地模拟 API，不要将真实聊天内容写入测试捕获文件。
- 不在实体手机上安装或启动应用。模拟器不可用时，将设备项标记为 `BLOCKED`，不要用“构建成功”替代运行验证。
- 项目说明的已知边界：尚未实现 TypeScript 转译，也没有承诺完整复刻 PiuPiu 的所有 DOM 生命周期、依赖库或第三方扩展行为。请区分“本轮声明行为”和“已知未支持项”。

## 1. 测试前记录

在仓库根目录执行并保留结果：

```powershell
git status --short
git rev-parse HEAD
node --version
python --version
```

确认工作树中既有改动仍在。项目没有 `gradlew.bat`；构建应通过仓库的 `build.ps1`，它会使用项目配置的 JDK、Android SDK、Gradle 和依赖缓存路径。若这些路径在测试环境不存在，记录为环境阻塞，不要改写 `build.ps1` 或项目配置来绕过。

## 2. 自动化检查

依次运行并记录每条命令的退出码和完整输出：

```powershell
node --check app/src/main/assets/card-runtime.js
node --check app/src/main/assets/session-runtime.js
node --check app/src/main/assets/session-script-runtime.js
node tools/test-card-runtime.cjs
node --experimental-vm-modules tools/test-card-modules.cjs
.\build.ps1 -Task ':core:regressionChecks'
.\build.ps1 -Task ':app:assembleDebug'
git diff --check
```

**测试夹具兼容提示：** 当前 `card-runtime.js` 调用 `XujingBridge.request(runtimeToken, requestId, method, payload)`。已有两个 Node 夹具最初按旧的三参数接口编写。如果相关脚本因 mock 参数错位而失败，先确认失败是否来自夹具；不得将夹具不匹配误报为生产功能回归。可提交给测试报告的夹具修正应只改 `tools/test-card-runtime.cjs` / `tools/test-card-modules.cjs`，并单独标注。

`:core:regressionChecks` 会运行现有导入、预设、设置、宏 / 正则、变量更新和 MVU 检查。它不会覆盖 Compose 聊天 UI、HTML WebView 或 Android JavaScript 桥接，所以仍需完成后续手工项。

## 3. Android 模拟器准备

若已有 Android 模拟器且可以安装本地构建产物：

1. 安装本次 `assembleDebug` 生成的 debug APK，在应用内新建一个临时会话和测试角色。
2. 开启全局角色卡脚本权限，并在临时会话设置里开启独立脚本。
3. 若要检查生成事件，在宿主机启动模拟 API：

   ```powershell
   python D:\Canxu-project\Xujing\tools\mock_openai.py
   adb devices
   adb -s emulator-5554 reverse tcp:8765 tcp:8765
   ```

   在应用中配置基础 URL `http://127.0.0.1:8765/v1`、模型 `xujing-mock`、无需 API 密钥，并在会话中选择该连接。若模拟器序列号不是 `emulator-5554`，将命令替换为实际序列号。

4. 测试结束后停止模拟 API，并移除端口转发：

   ```powershell
   adb -s emulator-5554 reverse --remove tcp:8765
   ```

   `tools/mock-api-captures/requests.jsonl` 会记录请求正文和提示词；其中可能包含测试对话。只使用虚构内容，测试后按项目数据处理要求清理或保管。

## 4. 手工验证矩阵

每项按 `PASS / FAIL / BLOCKED / NOT TESTED` 记录。失败时附最短复现路径、期望 / 实际结果、设备与系统版本、截图或日志。

### A. 聊天正文分段与 HTML

| ID | 操作 | 通过条件 |
| --- | --- | --- |
| HTML-01 | 在临时聊天中验证普通 Markdown、列表、引用、行内代码和普通代码围栏 | Markdown 与代码围栏仍按原有样式显示；围栏内的 HTML 只作为代码文本展示 |
| HTML-02 | 在正文中放入 `Use \`<script>…</script>\`` 形式的行内代码 | 标签显示为文本，不执行脚本，也不生成 HTML 卡片 |
| HTML-03 | 放入闭合的 HTML 围栏片段，含简单样式和可见文本 | 作为 HTML 内容呈现，样式和文本可见；相邻 Markdown 内容仍正常显示 |
| HTML-04 | 放入完整 `<!doctype html><html><head>…</head><body>…</body></html>` 文档 | 文档正文和 head 中的样式生效；页面宽度适配聊天区域，不出现横向溢出 |
| HTML-05 | 分别关闭、开启全局脚本权限后，验证含 `document.body.insertAdjacentHTML('beforeend','<span>JS-RAN</span>')` 的测试片段 | 关闭时不执行脚本；开启时脚本按当前策略运行。全程只用无网络、无文件访问的测试代码 |
| HTML-06 | 放入未闭合代码围栏，围栏内部包含 HTML | 未闭合内容保持普通文本 / 代码显示，不被误识别为可运行 HTML |
| HTML-07 | 观察回复生成中的流式文本，再观察生成完成后的最终正文 | 流式阶段不提前创建或执行 HTML；生成完成后按最终完整正文分段渲染 |
| HTML-08 | 放入较长 HTML 内容并滚动聊天页面 | 卡片高度能随内容变化；滚动、离屏和返回后没有明显重叠或重复创建 |

HTML 测试优先通过临时会话的消息编辑入口或测试角色的欢迎消息载入样例，不要直接修改应用数据库。每个样例只用可见的合成内容。

### B. 会话脚本隔离与输入区按钮

在临时角色的常见 TavernHelper 脚本树中配置两个轻量 JavaScript 脚本 A、B。两者都声明同名 `window` 标记，以验证 iframe 的全局隔离；同时各自暴露一个按钮：

```javascript
if (window.__qaGlobalOwner) throw new Error('脚本全局被共享');
window.__qaGlobalOwner = 'A'; // 脚本 B 改为 'B'
eventOnButton('tap', async () => {
  const vars = getVariables();
  await replaceVariables({ ...vars, qaButtonA: (vars.qaButtonA || 0) + 1 });
});
replaceScriptButtons([{ name: 'tap', label: 'QA A', enabled: true }]);
```

脚本 B 使用不同的变量名和按钮标签。若 `getVariables` / `replaceVariables` 在该导入结构下不可用，记录所用脚本数据结构与错误，不要改生产代码。

| ID | 操作 | 通过条件 |
| --- | --- | --- |
| SCRIPT-01 | 同时加载 A、B；两脚本使用相同的 `window.__qaGlobalOwner` 属性名 | 两脚本均加载成功，互不因全局冲突报错；各自 `getScriptId()` / `getScriptName()` 对应正确脚本 |
| SCRIPT-02 | 查看输入区按钮，分别点击 QA A 和 QA B | 标签归属正确；每次点击只执行对应脚本一次；变量结果可在临时会话中核对 |
| SCRIPT-03 | 测试按钮 `visible=false`、`enabled=false`，再替换按钮列表 | 隐藏按钮不显示；禁用按钮不可点击；替换后输入区及时更新且不残留旧按钮 |
| SCRIPT-04 | 生成期间观察按钮状态 | 生成忙碌时按钮不可触发；生成结束后恢复可用 |
| SCRIPT-05 | 关闭单个脚本、关闭会话独立脚本、关闭全局脚本权限后重新进入 | 按开关预期不加载对应脚本、不残留按钮；重新开启后脚本能正常初始化 |
| SCRIPT-06 | 切换到另一会话，再返回原会话 | 旧 iframe 与按钮状态没有串到新会话；返回后当前会话脚本可继续工作 |

### C. 事件路由与参数契约

| ID | 操作 | 通过条件 |
| --- | --- | --- |
| EVENT-01 | 让 A 调用 `eventEmit('qa_probe', { source: 'A' })`；A、B 都监听并给各自测试变量计数 | A 的本地监听只计数一次；B 收到一次；事件不会回灌造成循环或重复触发 |
| EVENT-02 | 让会话脚本发出测试事件，并在已挂载 HTML 卡片中监听 | 会话事件按脚本加载顺序传递到其他脚本，再按消息索引 / HTML 片段顺序传给已挂载卡片；源脚本被排除 |
| EVENT-03 | 使用模拟 API 正常生成一条回复并记录事件名、参数数量和值 | 参数符合下列契约；`generate_after_data` 修改后的 `messages` 真正进入 API 请求 |
| EVENT-04 | 在会话脚本与 HTML 卡片中对同一事件依序修改 payload | 后续监听者看到前序修改后的 payload；单个监听失败或超时不应让其后的接收者永久失效 |
| EVENT-05 | 生成期间停止请求 | 后续收到 `generation_stopped`；宿主等待被取消；不会卡住下一轮生成 |
| EVENT-06 | 在 `message_received` 监听中读取消息索引；另测生成失败 / 中断 | 成功保存回复后才收到 `message_received`；索引指向收到的回复；失败或中断不伪报成功接收 |

检查生成事件的参数数组：

| 事件 | 参数 |
| --- | --- |
| `generation_started` | `mode` |
| `chat_completion_prompt_ready` | 对象 `{ chat: [...] }` |
| `generate_after_data` | 对象 `{ messages: [...] }` |
| `js_generation_started` | `[mode]` |
| `js_stream_token_received_incrementally` | `[delta, mode]` |
| `js_stream_token_received_fully` | `[cumulativeText, mode]` |
| `js_generation_ended` | `[finalText, mode]` |
| `message_received` | `[messageIndex, mode]` |
| `generation_ended` | `[messageIndex]` |
| `generation_stopped` | 无参数 |

正常生成的预期顺序为：`generation_started` → `chat_completion_prompt_ready` → `generate_after_data` → `js_generation_started` → 成对按流顺序到达的 incremental / fully 事件 → `js_generation_ended` → `message_received` → `generation_ended`。如果现有测试脚本不能观察参数，可由测试夹具把参数序列化到临时会话变量或本地日志；不要依赖真实模型。

### D. 编辑消息正则与候选原文

准备带输入 / 输出阶段、深度限制和 `runOnEdit` 开关的临时正则规则，并使用可编辑的临时用户消息和角色回复：

| ID | 操作 | 通过条件 |
| --- | --- | --- |
| REGEX-01 | 编辑消息，分别比较 `runOnEdit=true` 和 `false` 的规则 | 仅显式允许编辑运行的规则执行；角色与输入 / 输出阶段筛选正确 |
| REGEX-02 | 对比不同消息深度的编辑结果 | `minDepth` / `maxDepth` 边界一致；不匹配的规则不修改内容 |
| REGEX-03 | 编辑包含变量更新块的回复 | 编辑时不重复提交生成阶段的变量副作用；已保存变量保持不变 |
| REGEX-04 | 为含多个 swipe 的助手回复编辑当前候选，再切换候选 / 导出临时聊天 | 当前候选显示编辑后文本；原始来源数组与当前 swipe 对应，没有继续读到旧文本，也没有改写其他候选 |

### E. 世界书预算与递归边界

用小型临时世界书和合成对话验证提示词详情及命中结果：

| ID | 操作 | 通过条件 |
| --- | --- | --- |
| WORLD-01 | 条目级 `scanDepth=0`，关键词只出现在历史消息中 | 该条目不因历史文本命中；设置为正值后按相应历史窗口命中 |
| WORLD-02 | 设置 `delayUntilRecursion` 为 `false`、`true`、`0`、`1`、`2` | 分别按 0、1、0、1、2 个递归步骤生效；字符串 / 无效值按实现中的安全默认处理 |
| WORLD-03 | 设置正的预算比例与正的 cap，并构造略超 cap 的条目集合 | 实际注入受 cap 限制，诊断或提示词详情与最终注入相符 |
| WORLD-04 | 设置预算比例为 0 且 cap 为 0；再设置正比例与 cap 为 0 | 前者表示无上限；后者只受比例预算约束，不应被 0 cap 错误压成零预算 |
| WORLD-05 | 省略预算字段或给出 `null` / 无效类型 | 使用配置默认值，不因字段存在但无有效数值而产生错误的零值 |

如果界面不能直接显示世界书命中细节，通过应用的提示词详情与诊断记录判断；记录测试用条目、命中词和最终注入文本。

## 5. 交付报告格式

测试 Agent 结束时请回填：

```text
测试时间 / Agent：
仓库 HEAD / 测试前 git status：
设备或模拟器型号、Android API、应用版本：
自动化命令及退出码：
用例结果：ID -> PASS / FAIL / BLOCKED / NOT TESTED
失败项：复现步骤、期望、实际、相关日志 / 截图路径
测试夹具改动（如有）：
是否改动生产代码：否（如是，请立即单独说明）
未覆盖项与环境阻塞：
```

完成标准：核心回归检查和 debug 构建结果可复现；HTML、脚本隔离 / 按钮、事件契约至少完成一轮模拟器手工验证，或逐项明确标为阻塞并提供原因；所有失败都附证据，不用“看起来正常”替代观察结果。

## 相关项目说明

- [功能进度](feature-progress.md)
- [PiuPiu 细节差异审计](piupiu-detail-gap-audit.md)
- [兼容性说明](compatibility.md)
- [本地模拟 API 说明](../tools/MOCK_API.md)
