# 构建记录

日期：2026-11-01。版本：0.1.0 开发版。

- `:core:compileKotlin` 首次构建通过，后续修改随 APK 构建重新编译。
- Android SDK 36、Build Tools 35.0.0、Gradle 8.13 已安装至 D 盘；下载的工具归档已核对上游校验值。
- **`assembleDebug -Offline` 构建通过**：Kotlin、Java、资源、DEX、原生库及开发签名 APK 均已完成。
- 此次修复 Android ICU 不接受的宏正则右花括号转义；离线重建通过，`41` 个构建任务，耗时 `3` 分 `36` 秒。
- 首次联网下载发生 TLS 中断；已补齐校验过的文件，增加镜像优先、显式下载断点续传和本地依赖仓库。
- 为适配本机内存，改用单进程 / 单工作线程构建，且只保留 26 个实际使用的扩展图标源码。
- 首次构建阶段通过桌面操控在 Android 13 模拟器检查应用启动、角色会话、聊天、设置和资料库界面；后续新增的核心回归与 ADB 验证见下方记录。

## 安装包

包名 `app.xujing`，版本 `0.1.0`（versionCode 1），最低 Android 8.0（API 26）。开发签名，不是正式发行版本。

产物包含 ARM64、armeabi-v7a、x86、x86_64 分包和通用包；当前 SHA-256 值位于 `dist/SHA256SUMS.txt`。安装包不包含模型权重。

源码归档为 `dist/xujing-0.1.0-source.zip`，不包含构建缓存、本机 SDK 路径配置、签名密钥、APK 或模型权重。`README.md` 记录本机构建方法。

## 尚未验证

模拟器已通过本机模拟 API 验证聊天请求、普通响应和流式响应。真实模型推理、外部 API 连通性、复杂 TavernHelper/MVU 角色卡和 PiuPiu 资料双向回导仍需后续验证；参见 `compatibility.md`。

## 模拟器测试记录

- 测试环境：Android 13 / API 33，x86_64，1 GB 客体内存低内存配置。
- 首次角色会话路径触发 Android ICU `PatternSyntaxException`，根因是 `MacroProcessor` 宏正则没有转义结束花括号；已将右侧 `}}` 改为 `\}\}`，重新构建并以保留应用数据的方式覆盖安装。
- 修复后重新启动、打开角色页、创建会话并进入聊天，开场消息正常显示；浏览了设置、世界书、预设、正则、人设页面。该路径没有再出现崩溃。
- 在未配置聊天 API 时尝试聊天操作，界面提示先添加连接并选择会话连接；没有访问外部 API。
- ADB 确认进程存活和版本为 `0.1.0` / `versionCode 1`。最初异常可在本轮保留的 emulator Logcat 中定位。
- 配置本机模拟 API 后，在同一会话发送测试消息：服务记录了提示词及消息，`stream=true` 时界面先显示部分文本，再显示完整回复；`stream=false` 时普通回复也正常显示。测试结束时连接已切回流式模式。服务及捕获文件的使用方法见 `tools/MOCK_API.md`。

## 本轮增量功能与验证（2026-11-01）

- 修复重复合并群聊备份后成员 / 发言人关联、原版 ZIP 中数字发言人 ID 导出、资料库媒体引用，以及设置中删除资料再次出现、文风未同步的问题。导入源消息 ID 重复时也为每条消息生成独立本地 ID。
- 新增会话级预设采样开关，支持标准温度、top_p、惩罚、输出上限、seed、停止序列及常见别名；上下文预算与生效的输出上限一致。
- 新增可选自动增量摘要，保留近期原文、设置触发数量、独立摘要连接、记录处理进度、按上下文分批。停止 / 失败不覆盖已有摘要；修改历史、回溯和改写已摘要消息时使旧摘要失效。
- `:core:regressionChecks -Offline` 通过，覆盖导入关联、设置往返、预设采样和摘要边界；使用合成资料，详见 `regression-checks.md`。
- 最终 `:app:assembleDebug -Offline` 通过，随后使用 `adb -s emulator-5554 install -r` 覆盖安装 x86_64 APK，保留原数据。
- 使用应用自身的文件导入界面载入独立合成会话“本地回归：摘要与预设”。通过 ADB 操作发送三轮消息：首轮确认先摘要后流式聊天、摘要与预设参数分离及近期原文边界；强制停止并重新打开应用后，第二轮只摘要新增进入范围的事实 6–7；在会话设置中关闭两项开关后，第三轮仅发送聊天请求并恢复连接参数。
- 本地模拟 API 捕获断言全部通过；固定回复不能证明真实模型的摘要或扮演质量。合成角色、会话及 Smoke 连接保留在模拟器中用于复查。
- 测试期间环境发生意外断连，已恢复原 Android 13 实例。冷启动时系统界面及音频服务出现资源紧张引起的停顿，构建结束并停止 Gradle 后恢复；最终 ADB 在线、应用进程存活。模拟器和模拟 API 保持运行。
- `dist` 中的各架构开发 APK、源码归档及 SHA-256 清单已与此次最终代码同步。

## 宏、正则与模块兼容增量（2026-11-01）

- 宏执行改为解析嵌套和配对块，支持块式 `setvar`、`if/else`、注释、`trim`；`addvar` 根据内容执行字符串拼接或数值相加。未选条件分支不执行变量副作用。`maxresponse` 使用实际生效的预设采样参数。
- 正则 `substituteRegex=2` 对宏替换值进行正则转义。JVM Regex 与 JavaScript RegExp 的剩余差异仍见 `compatibility.md`。
- 使用原包恢复的 `scripts/openai.js` 和 `scripts/utils.js`，增加当前会话、参数替换和自定义生成桥。模块来源及 SHA-256 见 `THIRD_PARTY_NOTICES.md`；世界书、预设管理和生成中途取消尚未完整接入。
- `:core:regressionChecks -Offline`、`node --experimental-vm-modules tools/test-card-modules.cjs` 和 `:app:assembleDebug -Offline` 均通过。另在隔离的本地 JavaScript 环境运行原包中的字符串/数值累加函数，核对边界行为。
- 通过 Android 13 模拟器的测试会话连接本机 mock API，验证嵌套条件和字符串累加生成 `TONE=calmsoft`，输出上限为预设值 333，角色名 `A+B` 仅按字面匹配，`AAAB` 不被误替换；流式请求成功。测试数据保留在模拟器中。
- APK 覆盖安装且模拟器保持运行。`dist` 各架构包、源码及哈希清单已同步本次构建；复杂角色卡和真实模型仍需后续验证。

## 世界书与预设脚本桥接增量（2026-11-02）

- 复用恢复的 `scripts/world-info.js`、`scripts/preset-manager.js` 和 `/script.js`，为前两者接入部分世界书及预设宿主接口；来源见 `THIRD_PARTY_NOTICES.md`。脚本创建的世界书限定于会话，并与原版动态世界书备份关联，重复导入时重映射会话 ID。
- 离线 `:core:regressionChecks`、原版模块 Node 检查和 `:app:assembleDebug` 通过。定向检查覆盖作用域、世界书写入和绑定、名称冲突、资料未知字段、原版备份往返和预设选择；不等于全量兼容率。
- Android 13 模拟器通过 ADB 覆盖安装，导入合成互动卡后验证模块创建/读取世界书、角色绑定及预设加载/选择；强制停止并重新启动应用后再次执行成功。重启本机 mock API 后，确认捕获的流式请求包含对应世界书、预设及用户消息。此前由于测试资料重复导入产生同名预设，应用报告歧义；使用独立名称的资料后完成验证。
- 测试结束后已将角色卡脚本开关恢复为关闭。模拟器和本机 mock API 保持运行；用于回归的合成会话、角色、连接、预设和部分重复资料留在模拟器中。
- 尚未以具有原版参考结果的完整样本集测得 90% 兼容性；剩余范围和验收方法见 `compatibility.md` 与 `compatibility-assessment.md`。

## EJS、Zod 与 MVU 接口增量（2026-11-02）

- 使用 `tools/extract-card-vendors.cjs` 从已恢复构建资源抽取 EJS 3.1.10、Zod 4.4.3、MVU 数据操作及 schema 事件钩子；提取工具不执行应用 chunk。来源和 SHA-256 记录在 `card-vendors/provenance.json`，许可随 APK 和源码交付。
- 允许脚本的消息页支持 EJS HTML 模板和模板 API，保留异步、条件、转义和失败回退；显示阶段产生的变量只在返回值中变化，保存需要显式调用宿主接口。消息页脚本按顺序加载，页面关闭时清理待处理请求和事件。
- 会话及消息候选变量写入在持久化成功后返回；按旧变量或旧候选提交会被拒绝。接入原版 `Mvu` 数据接口和 `mag_*` 事件名；schema 注册及验证代码可运行，但完整 MVU 自动更新、InitVar/历史重放、独立脚本树和生成钩子仍待接入。
- `:core:regressionChecks`、两个 Node 模块/运行时检查及离线 `:app:assembleDebug` 通过。Android 13 x86_64 模拟器覆盖安装后，合成卡显示 EJS 转义结果及 `RUNTIME_PASS`；强制停止并重新打开应用后再次通过，验证变量和消息候选快照恢复。此前失败的是测试样本内嵌套 EJS 字面量被外层提前处理，修正样本后完成验证。既有世界书/预设卡也再次显示 `WORLD_BRIDGE_PASS`。
- 测试结束已将脚本开关恢复关闭，测试资料保留，模拟器继续运行。各架构 APK、源码归档和 SHA-256 清单同步至 `dist`。这些定向检查尚不构成 90% 全量兼容率证据。

## MVU 更新块次序（2026-11-02）

- 对照原版 `GroupMemberSelector-legacy-BIJ8h7lR.js` 的 `Os` 解析函数，增加常见更新标签及 `json-patch` 别名；非嵌套的 JSON Patch 和变量更新块按文本中的出现顺序处理。现有每块事务与原版完整命令执行、嵌套块及事件链尚未逐项对齐。
- `:core:regressionChecks -Offline` 通过，新增检查覆盖混合块前后顺序、标签别名和不支持语句的保留；这些是合成输入的定向回归，尚不是原版与残叙·残念的自动差分测试。
- `:app:assembleDebug -Offline` 通过，已对 Android 13 模拟器覆盖安装 x86_64 开发包。模拟器保持运行；本轮未重复执行 UI 测试。

## MVU 命令与独立测试（2026-11-02）

- 对照原版 `Ls`、`Hs`、`Ps`、`Ns` 和 JSON Patch 转换，增加部分 `lodash` / `replace` / `move`、数组路径及按键或索引增删、带说明数值对和 JSON 更新命令。嵌套块、部分 scope、日期运算、原版完整 schema/事件链及逐操作提交语义仍未完整覆盖。
- 测试代理执行 `:core:regressionChecks -Offline`：20 条源代码推导的命令样例、6 条 JSON Patch、非法操作/作用域、未闭合注释与 9 条非法 path/from 检查通过。样例按静态代码构造，不是调用原版运行时的自动差分。
- Android 13 模拟器中使用独立 8766 模拟接口与合成会话，验证模型回复中的 JSON 更新及 JSON Patch、候选重生成与切换、强制停止和重启后状态恢复。最终 APK 覆盖安装后再次检查候选和发出的变量；角色卡脚本保持关闭。`assembleDebug -Offline` 通过。

## 测试机切换与悬浮面板（2026-11-06）

- 测试机默认改为逍遥 Android 12（`MEmu_1`，adb `127.0.0.1:21513`，x86_64，Chrome WebView 104，720×1280）；原 Android 13 AVD 保留为备用。完整流程见 `docs/environment.md` 与 `docs/regression-checks.md`。
- 悬浮面板层端到端通过：`setScriptPanel` 显式面板、`parent.document` 兼容面板、标题栏拖动、宿主关闭按钮、`clearScriptPanel()` 均验证成功。
- 预设检查卡回读 `shape=true|true|true`、`saved=undefined`、`replaceExisting=false`、`set=true|object`、`pvars`、`hasCap=true` 全部符合预期。
- `:core:regressionChecks` 七组、Node 两组测试、APK 资源与 dist 哈希检查均通过。构建期间主机内存不足一次（Gradle native OOM）；停 Gradle/Kotlin 守护进程并关闭模拟器后重试成功。
- 逍遥首次安装需手动打开「允许角色卡脚本」和会话级「运行角色独立脚本」；文件选择器必须点文件名而不是缩略图。测试资料与合成会话保留在模拟器中。

## M1 世界书与消息事件实现（2026-11-06）

基线 `9cf3641`（计划文档提交）。本批按 `docs/development-goals-2026-11-06.md` 实现 M1-01～M1-05，状态均为“已实现未验收”。

- M1-01/02/03：`WorldbookEngine.kt` 增加 `characterFilter`、`triggers`、条目→书级→会话全局的扫描深度继承（显式 0 合法）、`maxDepth` 扩大扫描、`useGroupScoring` 主/副关键词评分；`PromptEngine.build` 显式传入生成模式与回复角色。
- M1-04：`AppController.kt` 增加可等待的 `commitSession`，发送/编辑/切候选只有在落库成功后才派发 `message_sent`、`message_edited`、`message_updated`；补齐 `generation_started(type, {}, false)`、`message_received`、`character_message_rendered`、`generation_stopped` 与 `generate_after_data` 的 `messages/prompt` 兼容取值。`MessageRenders` 负责渲染证据、去重和实时索引；`CardMessage.kt`、`Chat.kt` 在卡片 ready 或 Compose 组合完成后上报。
- M1-05：`SessionSettings.kt` 新增「世界书高级设置」折叠区，写入 `session.extra.worldInfo`，只合并用户实际修改的键，保留未知字段；支持恢复继承/默认和输入校验。
- 验证：`:app:compileDebugKotlin -Offline` 与 `:app:assembleDebug -Offline` 通过，产出各架构 debug APK。**未运行测试、未启动模拟器、未安装 APK**；事件顺序、渲染去重、世界书过滤/评分/深度、设置页保存与重启行为均待测试 Agent 验收。
- 已知待确认：生成提交时 `character_message_rendered` 采用“提交后立即派发、真实渲染只去重”的混合方案；用户停止时 `generation_stopped` 仍主要经 `SessionHooks.cancel` 发给会话脚本；`swipe` 按 `regenerate` 映射；summary/impersonate 使用字面 trigger 值。

## M1 源码复审与重新打包（2026-11-06）

基线 `f7be95a`。复审记录见 `docs/m1-code-review-2026-11-06.md`，共修正 R01～R12，仍未运行测试或安装。

- 世界书：每组保留全部 sticky、多组取并集、已处理候选不重复尝试；角色过滤只使用来源角色文件名/名称与本轮角色名，不再把内部 ID 当名称；`maxRecursionSteps=0` 按不限固定轮处理并设安全边界；`maxDepth` 正值按包含末端索引计算，最少激活扩扫顺序对齐参考。
- 事件与渲染：发送入口增加 `pendingSend` 占位，覆盖 UI 与脚本，保存期间停止/切会话可取消；`message_received` 完成后才 arm 渲染期望，真实渲染后再发 `character_message_rendered` 和 `message_updated`；多段 HTML 需全部 ready；脚本关闭走页面完成回调；`prompt/messages` 在每个接收 WebView 重建别名；停止事件由最终清理统一派发。
- 设置：显示四个兼容容器合并后的有效值；只清理用户编辑项的别名并写入目标容器，未编辑字段和其他容器保持原样；输入不再静默改写为其他数字。
- 验证：`node --check` 两个 JS 通过；`:app:assembleDebug -Offline` 通过（1m40s）。`dist` 已用本次构建刷新，x86_64 APK SHA-256 为 `e6e3088dfdbce585f0f9460d4e6f7c84da54941b038355de7806dc9512d78252`，源码包为 `b9f8f0da4290c44df54749b6d53e2b86cb1f5db0af41cee4ba702bb944d254ee`。
- 状态仍为“已实现未运行验收”。前节“提交后立即派发”的混合方案已被本次复审替换；离屏或未完成 HTML 最多等待 1.5 秒，`generation_ended` 可先结束，渲染事件等消息实际挂载后再发。

## M1 运行测试与面板修复（2026-11-06 晚）

- 面板：逍遥 Android 12 上发现显式面板浅字浅底、兼容面板 `position:fixed;bottom:100px` 被推出小面板可视区。修复 `ScriptPanel.kt` 后复测：显式/兼容正文均可见，宿主关闭与 `clearScriptPanel()` 正常。
- 核心：新增 `WorldbookM1Checks` 并接入 `:core:regressionChecks`，覆盖 characterFilter 名称/标签/排除、triggers 模式、条目 scanDepth 0/1/2、书级 `scan_depth` 覆盖、全局显式 0、组评分主+副关键词。原有七组加新组全部通过，`BUILD SUCCESSFUL in 49s`。
- M1-04：逍遥 Android 12 + mock API 普通发送记录事件顺序 `sent(1) ; gen_start(normal,{},false) ; recv(2,normal) ; rendered(2,normal) ; updated(2) ; gen_end(2)`，无重复；mock capture 命中 `/v1/chat/completions`。
- M1-05：8 项高级设置控件存在；设置 `scanDepth=0`、`maxScanDepth=0`、`minActivations=1`、`maxRecursionSteps=0`、`budgetCap=0`、组评分开；保存、重进和 force-stop 冷启动后均保持。
- 未测：`prompt/messages` 别名（`QA Prompt Alias` 未跑完）、停止/编辑/切换会话/失败事件分支、M1-01/02/03 的模拟 API 实收内容、恢复继承/默认。
- 环境：强制停止 DocumentsUI 后出现一次 ANR，日志原因为 `Input dispatching timed out (Application does not have a focused window)`，不是主线程阻塞；重启应用后恢复。主机内存紧张，收尾时 MEmu VM 退出。
- 构建：`ScriptPanel.kt` 修复后 `:app:assembleDebug -Offline` 通过；当前 x86_64 APK SHA-256 为 `107a95b71d5eff04218d846ef809bce34c9b12fc81e5564214f912c88b2aac91`。`dist` 已刷新，测试新增的源码/文档会在提交后重新打包。

## 消息 iframe 与 live overlay（2026-11-07）

- 消息宿主：脚本开启时 `CardMessage` 改为 shell + 同源 iframe；作者 HTML 内的 `window.parent.document` 现在能访问 `.mes_text`、隐藏的 `#send_textarea`，`triggerSlash('/setinput ...')` 会把文本写入 Compose 输入框；`frameElement.style.height` 通过 shell 高度同步。
- 变量：`card-runtime.js` 与 `CardVariables.kt` 增加 `global` 作用域，读写 `state.compatibility["globalVariables"]`；卡片和会话脚本 snapshot 均带 `globalVariables`。
- App 级 live overlay（实验）：会话设置新增「预设 live 悬浮层（实验）」开关，写入 `session.extra["liveOverlayEnabled"]`。开启后优先把当前会话的预设脚本放到可见覆盖层 WebView，脚本的 `parent.document` 指向 overlay shell，fixed 节点可显示、拖动、点击；宿主按节点并集裁剪 WebView，并保留关闭按钮。当前只让一个脚本 live，其余仍走隐藏运行时。
- 验收（逍遥 Android 12，x86_64）：消息 shell 自动填充和点击按钮两条路径均得到 `SHELL_INPUT_OK`，无 `ERR` 文本；live overlay 显示 `LIVE OVERLAY OK`，可关闭，返回重进会话后自动重建；logcat 无 `FATAL EXCEPTION` / `XujingLive` / `live overlay script failed`。
- 构建：arm64 交付包 `4d0b3203491311cf234764bdb0a34e5a3e3eb0283f5923ba131ebe1b952d90e7`；本轮测试用 x86_64 包 `8b13917392d401524890e6f0686dc92c7c77136dfea0f3460ed91b6d17238a9e`，位于 `app/build/outputs/apk/debug/`。
- 未完成：overlay 只支持一个 live 脚本；`parent.$`、完整 `.mes`/聊天 DOM、`scrollTop` 等仍未 shim；overlay 并集矩形之间的空白会覆盖聊天；`/send`、杀进程重启恢复、多个预设脚本未测。

## 有效预设脚本与 overlay 修正（2026-11-07）

- 新文件 `三人逆行（净化）.json` 有效：约 75,080 个 CJK，脚本 214,663 字符，`node --check` 通过，保留 41 条正则（22 条启用）。
- 首次加载报 `$ is not defined`；overlay iframe 增加最小 `$`/`jQuery`、`toastr`、`errorCatched` shim 后脚本可加载。`session-script-runtime.js` 的加载失败日志改为输出 stack，便于定位。
- 修正 overlay 坐标：`getBoundingClientRect` 返回的是 CSS/设备像素，原先又乘 density 导致 WebView 被移到屏幕外；现在 offset 使用原始像素，尺寸按 dp 转换。关闭按钮改为按 24dp 实际像素定位，避免压住 orb。
- 验收：有效脚本的 orb 图标出现，拖动后 overlay rect 从 `654,1154 60×60` 变为 `316,582 60×60`，说明 fixed 节点和拖动链路生效；菜单/关闭未完整验证，orb 初始位置靠近底部输入栏会被部分遮挡。
- `E.json` 仍因中文被破坏成 `?` 而无法运行，不是 App 管线问题；有效脚本已证明 overlay 可以承载这类 UI。
- 新构建：arm64 `79d1dd8abd797c2e50de7275a2048198dc17a0f20f4a7810ba79a6616f35857f`。
- 真实预设 `E.json` 冒烟：原文带尾逗号，旧 `AppJson` 拒绝导入（`Unexpected JSON token ... Trailing comma`）。已给 `AppJson` 开启 `allowTrailingComma`/`allowComments`，`E-original.json` 现在可导入为预设。orb 仍未出现，根因是文件中文被破坏成 `?`，脚本出现 `/???/` 非法正则和未定义标识符；同一会话切到合成 `QA Live Preset` 时 overlay 正常显示和关闭，证明管线可用。需要未损坏的原始 `E.json` 才能继续验 orb。
- 预设正则：`E.json` 的 41 条规则在 `extensions.regex_scripts` 中（22 条启用），由 `RegexProcessor` 从当前预设读取，不会变成资料库里的独立正则条目；这是设计行为，不是导入丢失。
- 新构建：arm64 `f0bb6c6bf5b1ce1549a9039271be130f7cde5057b59f7b1c35202837b1bbc430`；x86_64 测试包 `5a42bb3451a58fea7971fa6afdd4e11422757e0dd3de9b2cf6cb1bb0d7879ebf`。

## B1：全屏渲染 + touch proxy（2026-11-07）

- 结构：Activity `android.R.id.content` 上新增全屏透明 `FrameLayout`；全屏透明 `TouchThroughWebView` 只渲染，默认 `onTouchEvent` 返回 false；shell 用 `XujingLive.touchRects([{id,x,y,w,h}])` 注册交互区域，宿主为每个区域创建透明 proxy。
- 事件：proxy 命中时把 MotionEvent 转发给 WebView 并消费；未命中时 proxy/FrameLayout/WebView 返回 false，事件继续落到下层 Compose。去掉了 union rect 裁剪和可见关闭按钮，返回键关闭 overlay。
- shell：删除 `documentElement` translate 和 union rect；按 fixed 根节点 + 非 fixed 交互节点发布多个 rect，100ms 节流（拖动 80ms），空数组清空 proxy。
- 验收：orb 显示且无关闭按钮；orb 存在时点击聊天输入框能获得焦点并输入 `PAS`，证明未命中 proxy 的触摸已穿透；返回键关闭 overlay 后仍停留在会话页；logcat 无 `XujingLive`/`FATAL` 错误。
- 未完成：本轮未重新验证 B1 下的 orb 点击/拖动（之前 rect 方案已验证）；返回键关闭后不能在同一会话内重新打开 overlay，需要离开重进或重启；菜单/多弹层/半透明全屏遮罩和 proxy 重叠顺序未测。
- 构建：arm64 `3ac52f99365970d490cfe015d92200489d38ed524d71696af40dca2cbedcd300`。
