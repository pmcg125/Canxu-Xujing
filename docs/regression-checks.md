# 针对性回归检查

运行 `powershell -File D:\Canxu-project\Xujing\build.ps1 :core:regressionChecks -Offline`。

检查使用纯 Kotlin `check()` 和已有运行时依赖，不依赖 Android 或真实模型服务。入口位于 `core/src/test/kotlin/app/xujing/core/RegressionChecks.kt`。

- `ImportIntegrityChecks`：同一群聊备份合并两次、移除第一批资料后导出原版 ZIP，成员和发言人仍有关联；资料库媒体精确引用再导出；摘要进度随消息 ID 重连。
- `SettingsRoundtripChecks`：删除全部资料后原版设置列表保持为空；文风编辑、未知字段、人设活动 ID 和残叙·残念附加资料去重。
- `PresetSamplingChecks`：默认关闭、有效参数覆盖、别名、非法值、连接扩展保留、输出上限冲突处理及提示词上下文预算。
- `SummaryChecks`：旧剧情与近期原文边界、触发间隔、增量进度、上下文分批、源记录改变时拒绝保存、空结果拒绝保存。
- `MacroCompatibilityChecks`：块式赋值、嵌套条件、未选分支无副作用、注释和 trim、文本/数字累加、有效预设参数，以及 `substituteRegex=2` 的元字符转义。
- `CardLibraryChecks`：会话世界书隔离、读写及条目格式、角色绑定后的提示词激活、备份重复导入后的会话关联、删除清理、带额外字段的包装格式与预设选择。
- `CardVariableChecks`：会话与消息作用域、候选快照写入、过期变量和候选拒绝、未实现作用域显式失败。
- `VariableUpdateOrderChecks`：混合更新块按文本顺序执行、更新标签别名以及未知语句保留。
- `MvuReferenceChecks`：根据原版解析和执行代码构造的定向用例，覆盖 lodash 命令、数组路径、带描述数值对、JSON 命令、JSON Patch、未知操作、未闭合注释、非法 path/from 与整块回滚；这些用例并未运行原版作为自动差分基准。
- `WorldbookM1Checks`：M1-01/02/03 的定向检查，覆盖 characterFilter 名称/标签/排除、triggers 的 normal/regenerate/summary、条目显式 scanDepth 0/1/2、书级 `scan_depth` 覆盖全局、全局显式 0 与条目显式值、互斥组主+副关键词评分。固定 `Random(0)`，不替代模拟 API 的实际注入核对。

使用 `node --experimental-vm-modules tools/test-card-modules.cjs` 检查恢复的 `openai.js`、`utils.js`、`world-info.js`、`preset-manager.js` 与宿主桥：生成调用、世界书读写/绑定、名称刷新、失败响应和预设加载/选择。Node 的 VM 模块仍为实验性接口。该检查不代表 Android WebView 中全部第三方角色卡均可运行。

Android 端合成样本为 `tools/fixtures/card-worldbook-preset-qa.zip`。在隔离的测试资料库中导入该备份，允许角色卡脚本并打开 `Bridge QA Unique V5`；预期页面显示 `WORLD_BRIDGE_PASS`。重启后再次打开应仍显示通过。启动 `tools/MOCK_API.md` 描述的本地模拟服务及 ADB reverse，发送 `qa-trigger` 后检查捕获请求包含 `QA_PRESET_SELECTED` 和 `QA_WORLD_PERSISTED`。同名预设会造成选择歧义；重复执行前应使用隔离环境，或修改样本中的名称和 ID。测试后恢复原有脚本开关设置。该样本只覆盖单一合成互动卡。

以上为合成样本回归，不能替代用户真实 PiuPiu 备份的双向回导或真实模型效果评估。

`node tools/test-card-runtime.cjs` 检查抽取的 EJS/Zod、原版 MVU schema 钩子、异步模板、失败变量回退、作用域和保存失败不触发成功事件。预期失败模板会输出一条警告。

`python tools/make-runtime-fixture.py` 生成 `tools/fixtures/card-runtime-ejs-zod-qa.zip`。在隔离的测试资料库导入一次，打开 `Runtime QA EJS Zod B`，预期显示 `EJS:RuntimeUser:A&B` 及 `RUNTIME_PASS`。强制停止应用、重新打开会话后仍应通过。测试包括真实 WebView 模板渲染、Zod 注册事件、显式保存与消息作用域；不包括完整 MVU 自动更新链。模板内的嵌套 EJS 字面量需要避免被外层模板提前解析；此样本使用字符编码构造内层分隔符。测试会启用脚本，完成后恢复原开关。

## 模拟器：逍遥 Android 12（默认）

默认测试机改为逍遥的 Android 12 实例（`MEmu_1`，`memuc` 索引 1）。

```powershell
$memu = 'D:\Program Files\Microvirt\MEmu\memuc.exe'
& $memu start -i 1

$adb = 'D:\REA-Android\platform-tools\adb.exe'
& $adb kill-server
& $adb connect 127.0.0.1:21513
& $adb -s 127.0.0.1:21513 shell getprop ro.build.version.release   # 12
& $adb -s 127.0.0.1:21513 shell getprop ro.product.cpu.abi         # x86_64
& $adb -s 127.0.0.1:21513 shell dumpsys webviewupdate | Select-String 'Current WebView'
```

安装 `app-x86_64-debug.apk`。当前实例：Android 12 / x86_64 / 720×1280 / density 240 / Chrome WebView 104。

注意：

- 只用平台工具的 adb，先 `adb kill-server`，避免和逍遥自带 adb 的 server 版本冲突。
- UI 辅助脚本要显式传设备：`ui.ps1 -Serial 127.0.0.1:21513`。
- 逍遥的文件选择器里点缩略图只会预览；要点文件名文本，才会把 URI 返回给残叙。
- 首次安装后需要打开「设置 → 角色卡执行 → 允许角色卡脚本」，再在会话里开「运行角色独立脚本」。
- 关闭实例：`& $memu stop -i 1`。
