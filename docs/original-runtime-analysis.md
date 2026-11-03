# PiuPiu 2.7.9 角色卡运行时：原代码分析

参考材料是从 Android 2.7.9 客户端恢复的构建产物，主要为 `frontend-readable/assets/c/GroupMemberSelector-legacy-BIJ8h7lR.js`、`ChatView-legacy-C8UPOooP.js` 和 `tavernHelperScriptStorage-legacy-BVIVfja_.js`。格式化文件有行号，但不是原始工程源码；本记录是静态分析，不代表所有分支已在原应用实测。对应 APK SHA-256 为 `258e85bf0f18347d13c59e9907c79c1728ef5cc0eaaa272057d835741c9d319a`。

## MVU 与 Zod

原版实现同时包含宿主中的状态更新逻辑和 iframe 中的 `Mvu`、Zod 兼容接口，而不是简单加载一个完整的上游插件。宿主数据包含 `initialized_lorebooks`、`stat_data`、`display_data`、`delta_data` 和 `schema`；它会从当前候选或更早的消息快照查找变量状态。初始化会解析 `<initvar>` 及世界书条目，合并变量并记录初始化来源（GroupMemberSelector:21027、21069、21105、21156）。

iframe 中的 `Mvu.events` 使用 `mag_variable_initialized`、`mag_variable_update_started`、`mag_command_parsed`、`mag_variable_updated`、`mag_variable_update_ended`、`mag_before_message_update`。`waitGlobalInitialized('Mvu')` 除等待全局对象外，还等待宿主完成重放持久化并同步聊天时间线（GroupMemberSelector:30934、30998）。原版会将识别到的 MagVarUpdate 远程依赖映射到内置 MVU，避免重复运行（31712–31718）。

`registerMvuSchema` 注册 schema，普通 ZodObject 被转换为 `looseObject`；初始化时用 `safeParse`。解析更新命令时，代码先将命令作用于候选状态，再验证 schema；成功则合并结果并从待处理命令中移除。后续 Zod 事件还会清理剩余命令与显示/增量数据（30934）。这些行为依赖实际打包的 Zod，不能用“存在一个 schema 字段”替代。

残叙·残念当前在 `card-runtime.js:105–118` 只暴露简化的 Mvu 变量接口和大写事件值，DOMContentLoaded 时即发初始化；Kotlin 仅解析部分更新语法。现有数据持久化、候选及回溯机制需要和原版时间线及事件顺序对接。原版 JavaScript 函数和打包的 Zod 有复用可能，但依赖宿主快照、跨 iframe 消息和存储，尚未证明可独立运行。

## EJS

打包代码含 EJS 3.1.10 和 Apache 2.0 许可头（GroupMemberSelector:27397–27409）。外围包装先解析开头的 `@@` 指令，例如 `@@preprocessing`、`@@private`、`@@render_before`、`@@render_after`、`@@message_formatting`、`@@iframe`、`@@if`；另有若干被识别并从正文移除的指令。识别指令不等于每个指令的全部行为都已得到验证（27980–28041）。

包装层复制变量，提供 `getvar`、`setvar`、`user`、`char` 和常用全局对象，使用异步 EJS 渲染，返回内容、指令和变量副本。`@@if` 被转为条件代码，`@@private` 转为局部代码块。默认渲染失败时返回原正文、原变量和错误；`throwOnError` 路径则抛错（28080–28208）。

优先抽取 EJS 与包装层，保留其许可并标记来源，再接上残叙·残念变量及阶段管理。引擎能加载不代表完成兼容：仍需核对它在世界书、提示词、消息显示各阶段的调用时机、变量结果何时提交及失败回退。

## 脚本树、iframe 与事件

脚本树读取支持 `extensions.tavern_helper.scripts` 和旧 `extensions.TavernHelper_scripts`，根据结构规范化并按 ID 与内容去重；模块还解析脚本的 ES module imports（tavernHelperScriptStorage:361–383、417–449）。原版根据激活条件过滤脚本，处理远程依赖和是否需要宿主访问；可信授权与脚本树哈希绑定，脚本树变化后旧授权失效（GroupMemberSelector:31595–31749）。

普通角色脚本运行在隐藏 iframe 的 sandbox 模式，需要宿主访问的脚本则要求授权。iframe 通过 `postMessage` 与宿主执行 RPC；宿主按 `hostApi` 分派，必要时刷新消息、变量和世界书缓存并回传结果（31916–31923、32604–32660）。事件按监听器顺序异步执行；跨 frame 派发有应答与超时机制（30927、32707–32765）。

消息 HTML 的 iframe 与角色脚本 iframe 是不同路径，前者的 sandbox 配置并不等同于后者。残叙·残念目前主要将脚本放进消息 WebView；这与原版的独立脚本树和宿主桥有生命周期差异。需要单独验证加载顺序、离屏/重组、会话切换、监听器清理、生成取消以及持久化后发事件的语义。

## 扩展与生成钩子

原版暴露 TavernHelper、SillyTavern 风格全局对象、变量及消息 API、世界书、正则、脚本树、预设、提示注入和生成 API，并内嵌 jQuery、Lodash、Vue、Pinia、Vue Router、Zod 和 YAML 等运行时资源（GroupMemberSelector:30986–31035、31143）。具体入口有实现，不代表所有 SillyTavern 扩展均被支持。`scripts/world-info.js` 等适配模块仍依赖这些宿主行为。

主聊天生成消息组装后先触发 `chat_completion_prompt_ready`，并在返回值包含 chat 数组时将其写回；随后触发 `generate_after_data`，再继续后续处理（ChatView:27025–27064）。第二个事件具体能通过哪些引用修改最终请求，仍需单独运行验证。残叙·残念当前没有完整的等价事件链。

## 实施边界与验证

1. 对内嵌 JS 字符串使用语法解析和字面量解码抽取，记录来源、哈希和许可；不要整体复制混有 Vue 页面、数据库和其他功能的大 chunk。
2. 先验证抽取的 EJS、Zod 和独立状态算法能在受控宿主加载；逐步对接变量快照、脚本树、事件总线和宿主 API。Kotlin 仍负责持久存储和连接管理。
3. 以原版执行结果为参考，验证初始化及 Zod 校验、模板输出与变量副作用、脚本加载/注销、提示钩子、重生成/切候选/回溯/重启。静态代码定位不能当作运行通过。
4. 第三方扩展按实际依赖的 API 和代表性卡片逐项核对；未确认的外部依赖和云端行为不计为已兼容。

## 本次分析后的实现进展

2026-11-02 已抽取 EJS/Zod/MVU 数据及 schema 钩子并接入消息页。上文中的“残叙·残念当前”描述保留分析时的起点；最新执行范围以 `compatibility.md` 和 `build-status.md` 为准。完整 InitVar 重放、命令自动更新、独立角色脚本树和生成钩子尚未接通。

## InitVar 与重放补充定位

原版 `jl` 从指定起点前的最近有效快照恢复状态，之后逐条消息、逐候选处理原始内容；首条开场消息走 `Sl` 初始化（GroupMemberSelector:21970–22043）。`Sl` 解析 `<initvar>` 和关联世界书，记录 `initialized_lorebooks` 并有不同来源的合并优先级（21155–21326）。消息存储在处理助手更新时，若基础变量为空且存在世界书，也会尝试恢复初始状态（23590–23623）。因此实现时需同时保存原始候选内容、初始化来源和快照，不能把 InitVar 仅作为显示页面的副作用。上述调用关系来自静态代码，尚未实现或完成动态对照。
