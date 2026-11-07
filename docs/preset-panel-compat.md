# 预设正则、脚本与悬浮面板兼容

更新：2026-11-07。版本：`0.2.0`（versionCode 2）。适用于 `D:\Canxu-project\Xujing`。

本文记录预设（PiuPiu / SillyTavern 格式）中的正则、脚本、消息 HTML 和 `parent.document` 悬浮 UI 在当前实现中的处理方式、兼容边界和排查方法。它是实现说明，不是“已完全兼容 PiuPiu”的验收结论。

## 1. 总览

```text
预设 JSON
├── extensions.regex_scripts
│     └── RegexProcessor：提示词 / 输出 / 显示阶段
├── extensions.tavern_helper.scripts
│     └── SessionScripts
│           ├── 默认：隐藏会话 WebView + iframe
│           └── liveOverlayEnabled：可见 overlay WebView
└── 消息 HTML（角色卡 / 正则替换结果）
      └── CardMessage：同源 iframe shell
            ├── .mes_text
            ├── #send_textarea
            └── window.parent / triggerSlash / frameElement
```

关键入口：

| 功能 | 文件 |
| --- | --- |
| 预设正则 | `core/src/main/kotlin/app/xujing/core/engine/RegexProcessor.kt` |
| 预设脚本收集 | `core/src/main/kotlin/app/xujing/core/compat/SessionScripts.kt` |
| 消息 WebView / iframe shell | `app/src/main/java/app/xujing/runtime/CardMessage.kt`、`app/src/main/assets/message-shell.js` |
| 会话脚本运行时 | `app/src/main/java/app/xujing/runtime/SessionScriptRuntime.kt` |
| 卡片 JS 接口 | `app/src/main/assets/card-runtime.js` |
| live overlay 主机 | `app/src/main/java/app/xujing/ui/LiveScriptOverlay.kt` |
| live overlay 触摸代理 | `app/src/main/java/app/xujing/ui/TouchProxyHost.kt` |
| live overlay shell | `app/src/main/assets/overlay-shell.js` |
| 变量作用域 | `core/src/main/kotlin/app/xujing/core/compat/CardVariables.kt`、`card-runtime.js` |

## 2. 预设正则

### 2.1 来源

只读取当前会话选中的、启用的预设：

```kotlin
state.library.filter { it.enabled && it.kind == "preset" && it.id == session.presetId }
```

读取路径：

```text
unwrap(preset.data)["extensions"]["regex_scripts"]
  ／ ["scripts"] / ["regexScripts"] / ["regexes"]
```

注意：

- 只认 `extensions` 下；根级 `regex_scripts` 不会执行。
- `extensions.scripts` 会被当正则规则读取，不是预设脚本。
- 预设内正则不会出现在资料库的“正则”标签里，这是设计行为。

### 2.2 顺序

`RegexProcessor` 的规则顺序固定为：

1. 资料库正则：会话绑定或 `extra.global=true`
2. 当前预设 `extensions.regex_scripts`
3. 角色卡内嵌正则

同一来源按数组顺序执行，最后按来源、id/name、findRegex、replaceString 去重。

### 2.3 阶段与 placement

| 阶段 | 作用 |
| --- | --- |
| `INPUT` | 用户输入 / 历史中的用户消息 |
| `OUTPUT` | 角色输出；`processOutput` 后处理 |
| `WORLDINFO` | 世界书内容、depth 注入 |
| `DISPLAY` | 聊天界面显示 |

`placement` 映射：

- `1` / `user` / `input` → 用户输入
- `2` / `assistant` / `ai_output` / `output` → AI 输出
- `5` / `worldinfo` / `world_info` → 世界书
- `DISPLAY` 阶段：用户消息按 `1`，角色消息按 `2`

### 2.4 规则过滤

- `disabled` / `enabled`
- `markdownOnly`：只在显示阶段
- `promptOnly`：只在提示词阶段
- `runOnEdit`：编辑消息时是否重跑
- 已处理消息：`processed=true` 时跳过普通规则
- `minDepth` / `maxDepth`

### 2.5 替换语法

- `/pattern/flags`，支持 `g/i/m/s`
- 非 `/.../` 形式默认全局替换
- `substituteRegex=1` 先展开宏；`=2` 宏值转义
- 替换串支持 `$1`、`$&`、`$$`、`` $` ``、`$'`、`$<name>`、`{{match}}`
- `trimStrings` 在替换前删除匹配片段
- 无效正则/未知 flag 写诊断，不中止生成

正则是 **Kotlin/JVM 正则**，不是 JS 正则；JS 专有语法、`y/u/d` flag 不保证一致。

### 2.6 不处理的边界

- 预设自己的 prompt 块文本只做宏展开，不做正则后处理。
- 预设正则不会自动变成独立资料库正则。

## 3. 预设脚本

### 3.1 收集

`SessionScripts.collect` 读取当前预设：

- `extensions.tavern_helper.scripts`
- 或旧式 `extensions.TavernHelper_scripts`

支持：

- 文件夹嵌套，父级 `enabled=false` 禁用子树
- `type:"script"` 的 `value` 包装
- 整段 ` ```javascript ... ``` ` 代码围栏
- ID：`preset:<presetId>:<record.id 或下标>`
- `session.extra.disabledSessionScriptIds` 单独禁用
- 收集顺序：角色脚本在前，预设脚本在后

### 3.2 启用条件

同时满足：

1. 全局 `preferences.allowCardScripts=true`
2. 会话 `extra.sessionScriptsEnabled=true`
3. 脚本/父文件夹 `enabled != false`
4. 不在 `disabledSessionScriptIds`

### 3.3 默认运行时

`SessionScriptRuntime.kt` 创建隐藏 WebView，加载 `session-runtime.js`；每个脚本一个隐藏 iframe，加载：

- `card-runtime.js`
- `zod.js` / `ejs.js` / `mvu.js` / `template.js`
- `session-script-runtime.js`

脚本只能通过 `XujingBridge.request` 与宿主交互，不接触 App 的 Compose DOM。

### 3.4 live overlay（实验）

会话设置 → 提示词资料 → 「预设 live 悬浮层（实验）」

- 写入 `session.extra["liveOverlayEnabled"]`
- 只让一个脚本 live：优先 `preset:` 前缀，否则第一个
- 其余脚本仍走隐藏运行时
- live 脚本在可见 overlay 的 iframe 中运行，`parent.document` 指向 overlay shell

详见第 5 节。

## 4. 消息 HTML shell

### 4.1 结构

脚本开启时，`CardMessage` 不再把作者 HTML 直接作为顶层页面，而是：

```text
CardMessage WebView（shell）
├── .mes_text
│   └── iframe（作者 HTML + card-runtime）
├── #send_textarea（隐藏）
└── message-shell.js
```

- iframe 同源；`window.parent` 是 shell
- `window.XujingBridge = parent.XujingBridge` 注入 iframe
- `ready/height/request/eventComplete` 保持原行为
- 脚本关闭时仍使用原来的直接页面，不套 iframe

### 4.2 提供的接口

| 接口 | 行为 |
| --- | --- |
| `window.parent.document.querySelector('#send_textarea')` | 命中 shell 的隐藏 textarea |
| `window.parent.triggerSlash('/setinput ...')` | 写 textarea 并回填 Compose 输入框 |
| `window.parent.triggerSlash('/send ...')` | 走宿主发送 |
| `window.parent.document.querySelector('.mes_text')` | 主题色容器 |
| `window.frameElement.style.height` | shell 同步卡片高度 |
| `getComputedStyle(.mes_text).color` | 返回宿主主题色 |

`setInput` 链路：

```text
script → triggerSlash / #send_textarea input
→ XujingBridge.request('setInput')
→ AppController.cardRequest
→ UiRuntime.inputDraft
→ ChatScreen LaunchedEffect
→ Compose 输入框
```

只填输入框，不自动发送。

### 4.3 主题与高度

- `CardMessage` 把 `MaterialTheme.colorScheme` 的文本/背景/强调色和字号写入 snapshot 的 `theme`
- shell 把主题写到 `.mes_text` / body
- shell 用 `ResizeObserver` + `MutationObserver` 测量 iframe 内容；识别 `frameElement.style.height`
- 与 `card-runtime.resize()` 取最大值，避免互相覆盖

### 4.4 限制

- 只在脚本开启时提供 shell；脚本关闭时没有 `#send_textarea`
- `srcdoc` 的 `location.href` 是 `about:srcdoc`，依赖 location 的卡片可能受影响
- 作者文档自带 `<base>` 会覆盖注入的 base
- 不是完整浏览器 iframe 沙箱：同源 iframe 可访问 shell 的 bridge

## 5. live overlay（B1）

### 5.1 目标

让通过 `parent.document` 创建 fixed 节点的预设脚本运行在可见层，同时尽量不阻塞聊天。

### 5.2 结构

```text
Activity android.R.id.content
├── Compose 聊天界面
└── 全屏透明 FrameLayout（native overlay）
    ├── TouchThroughWebView（全屏、透明、只渲染）
    └── TouchProxy[N]（透明、只覆盖注册区域）
```

- overlay 加在 Activity 根布局，位于 Compose 之上
- 不申请系统悬浮窗权限
- 没有可见关闭按钮；系统返回键关闭 overlay

### 5.3 全屏 WebView 不消费触摸

`TouchThroughWebView`：

```kotlin
var forwarding = false
override fun onTouchEvent(event: MotionEvent): Boolean =
    if (forwarding) super.onTouchEvent(event) else false
```

默认返回 false；只有 proxy 转发时才消费。

### 5.4 touchRects

`overlay-shell.js` 计算需要拦截的区域：

- fixed 根节点 + 其**可见子树并集**（覆盖 orb 内部向外展开的菜单）
- 非 fixed 的交互节点：`button/a/input/select/textarea/[role=button]/[onclick]/[data-xujing-touch]`，或 `cursor:pointer`

通过：

```js
XujingLive.touchRects(JSON.stringify([
  { id, x, y, w, h }
]))
```

发布。坐标是 **CSS 像素**，相对 shell 左上角。

触发：

- `MutationObserver`（childList/attributes）
- `ResizeObserver`
- `pointerdown/pointermove/pointerup/scroll/transitionend/animationend/resize`
- 普通 100ms 节流；拖动 80ms

### 5.5 坐标换算

这是之前菜单点不到的关键：

- shell 使用 CSS 像素（`meta viewport width=device-width`）
- 宿主 proxy 使用设备像素
- 创建 proxy 时按 `density` 换算：

```kotlin
val scale = parent.resources.displayMetrics.density
leftMargin = (rect.x * scale).roundToInt()
width = (rect.width * scale).roundToInt()
```

`LiveScriptOverlay` 传给 shell 的 `screenWidth/Height` 是 CSS/dp 尺寸，不是 `composeView` 的设备像素。

### 5.6 事件转发

- 每个 rect 一个透明 `View`
- proxy `ACTION_DOWN`：`web.forwarding=true`
- 使用 `getLocationOnScreen` 求 proxy/WebView 偏移，复制 MotionEvent 后 `web.dispatchTouchEvent`
- `ACTION_UP/CANCEL` 恢复 `forwarding=false`
- 手势进行中收到的 rect 更新延后到手势结束再重建，避免断手
- 未命中 proxy 时，FrameLayout/WebView 返回 false，事件落到 Compose

### 5.7 关闭

- 系统返回键：`BackHandler` / overlay 关闭
- 脚本可调用 `XujingLive.close()`
- 关闭只隐藏 overlay，不退出会话

## 6. 兼容契约

### 6.1 消息 iframe

| API | 状态 |
| --- | --- |
| `window.parent.document` | ✅ |
| `#send_textarea` | ✅ 隐藏元素 + input 回填 |
| `.mes_text` | ✅ |
| `triggerSlash('/send')` | ✅ |
| `triggerSlash('/setinput')` | ✅ |
| `window.frameElement.style.height` | ✅ |
| `getComputedStyle` 主题 | ✅ 基础颜色/字号 |
| 自包含 `<details>` / React / 内嵌 iframe | ⚠️ 部分可运行 |
| 完整 `#chat` / `.mes` 列表 | ❌ |

### 6.2 live overlay

| API | 状态 |
| --- | --- |
| `parent.document.createElement/appendChild` | ✅ |
| `parent.document.head` 样式注入 | ✅ |
| `parent.document.documentElement.appendChild` | ✅ |
| `position:fixed` 节点 | ✅ |
| pointer 拖动/点击 | ✅ |
| `localStorage` | ✅（overlay 源） |
| `getVariables({type:'global'})` | ✅ |
| `insertOrAssignVariables(...,{type:'global'})` | ✅ |
| `#send_textarea` / `triggerSlash` | ✅ |
| `#completion_prompt_manager_list` | ⚠️ 空容器 |
| `parent.$` / jQuery | ⚠️ 最小 `$` shim，不是完整 jQuery |
| `toastr` | ⚠️ 空操作 |
| `errorCatched` | ✅ 最小实现 |
| `parent.SillyTavern.eventSource` | ❌ 空 stub |
| 完整 `.mes` / `#chat` / `scrollTop` | ❌ |

## 7. 已知限制

- **只支持一个 live 脚本**：其余脚本仍在隐藏运行时。
- **proxy 是矩形并集**：fixed 根节点内部非点击空隙也会被拦截；多个 fixed 节点的并集之间空白可能挡聊天。
- **半透明全屏遮罩**：如果脚本创建覆盖全屏且 `pointer-events:auto` 的容器，聊天会被挡住。
- **菜单可能被裁**：orb 靠近屏幕边缘时，菜单会伸出屏幕，顶部/右侧不可见。
- **返回关闭后不能在同一会话内重开**：需要离开重进或重启；目前没有可见重开入口。
- **不完整 app DOM**：`#completion_prompt_manager_list` 为空，jQuery 只有最小 shim，不能保证所有 orb 菜单功能。
- **`tavern_helper.variables` 未自动合并**：global/preset 变量需要脚本显式读写。
- **预设变量是共享桶**：`type:'preset'` 不按预设名分桶。
- **CSS/设备像素**：修改 overlay 时若再混用 px/dp，会重现菜单点不到。
- **E.json 类文件损坏**：中文被替换成 `?` 会导致 `/???/` 或未定义标识符；这是数据问题，不是 App 管线问题。
- **JSON 尾逗号/注释**：`AppJson` 已允许；其他解析入口不一定。

## 8. 验收记录

### 8.1 合成测试

- 消息 shell：`QA Shell` 的 `parent.triggerSlash('/setinput SHELL_INPUT_OK')` 自动/按钮均回填输入框，无 `ERR`
- live overlay：`QA Live Preset` 的 fixed 节点显示、可关闭
- B1 触摸：orb 存在时点聊天输入框可聚焦并输入 `PASTHROUGH_OK` / `MENUPAS`
- B1 菜单：orb 打开真实菜单；点「情感基调」展开子项；菜单打开时输入框仍可输入
- B1 拖动：proxy 从 `482,881-554,953` 移到 `276,675-348,747`
- 返回关闭：overlay 消失，仍在会话页；离开重进后 orb 重建
- 消息 shell 回归：`SHELL_INPUT_OK` 自动回填，可编辑

### 8.2 真实预设

- `三人逆行（净化）.json`：脚本 214,663 字符，`node --check` 通过；orb 可显示、菜单可打开、可拖动
- `E.json`：尾逗号已可导入；orb 因中文损坏成 `?` 失败
- `E-normalized` / `E-diagnostic`：仍受数据损坏限制

### 8.3 构建

```powershell
powershell -NoProfile -File D:\Canxu-project\Xujing\build.ps1 :app:assembleDebug -Offline
```

版本 `0.2.0` / versionCode 2。当前 `dist`：

- `xujing-0.2.0-arm64-v8a-debug.apk`
- `xujing-0.2.0-armeabi-v7a-debug.apk`
- `xujing-0.2.0-debug.apk`
- `xujing-0.2.0-x86-debug.apk`
- `xujing-0.2.0-x86_64-debug.apk`
- `xujing-0.2.0-source.zip`

哈希见 `dist/SHA256SUMS.txt`。

## 9. 排查

### 菜单点不了

1. 确认安装的是 0.2.0 且包含菜单并集修复。
2. `adb shell dumpsys activity top | findstr TouchThroughWebView` 看 proxy rect 是否覆盖菜单。
3. 如果 proxy 是 48×48，说明 shell 没把菜单子树纳入并集，或 `touchRects` 没更新。
4. 如果 proxy 与可见菜单有固定倍数偏差，检查 CSS/设备像素与 `density` 换算。
5. `adb logcat -s XujingLive` 看脚本 console / 加载错误。

### 聊天被 overlay 挡住

1. 确认 `TouchThroughWebView` 默认 `onTouchEvent` 返回 false。
2. 确认未命中 proxy 时 FrameLayout 不消费。
3. 检查是否有半透明全屏 fixed 容器被注册成 proxy。

### 脚本不加载

1. 全局「允许角色卡脚本」、会话「运行角色独立脚本」是否开启。
2. `adb logcat -s XujingLive` 看 stack。
3. 常见缺失全局：`$`、`toastr`、`errorCatched`；overlay 已提供最小 shim。
4. 文件编码损坏会产生 `/???/` 或未定义标识符。

### 正则不生效

1. 预设是否选中、启用。
2. 规则是否在 `extensions.regex_scripts`。
3. `placement` 是否匹配当前阶段。
4. `markdownOnly` / `promptOnly` / `runOnEdit` 是否把规则过滤掉。
5. 查看提示词详情的 diagnostics。

## 10. 后续

- 返回关闭后的同会话重开入口
- 多 live 脚本 / 多弹层 proxy 合并与 z-order
- 半透明全屏遮罩与触摸区域识别
- 完整 `#completion_prompt_manager_list` / jQuery shim
- `tavern_helper.variables` 合并与 M2 变量作用域
- 真实设备不同 density/WebView 的回归
