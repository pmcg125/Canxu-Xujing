# PiuPiu 2.7.9 → 残叙·残念 功能缺口总表

日期：2026-11-04。本文是三份只读对照的合并结论，不重复明细：

| 明细 | 范围 | 文件 |
| --- | --- | --- |
| core / 引擎层 | 复核 `docs/piupiu-detail-gap-audit.md` 的 22 条补点 + 清单外发现 | [`audit-2026-11-04/core-gaps.md`](audit-2026-11-04/core-gaps.md) |
| 产品功能面 | 43 项前端能力对照 | [`audit-2026-11-04/surface-gaps.md`](audit-2026-11-04/surface-gaps.md) |
| 原生宿主 | 28 项原生能力 + 安全边界 | [`audit-2026-11-04/native-gaps.md`](audit-2026-11-04/native-gaps.md) |

三份明细都是**静态源码对照**，不代表运行验证；`docs/compatibility.md` 的「源码接入 ≠ 已验收」口径不变。

## 1. 总体结论

- 旧清单（11-03 的 22 条）里已有 **7 条完成、5 条部分完成**，本轮实测通过的项目（HTML 分段、`runOnEdit`、数值递归、预算零值、嵌套合并、事件参数、脚本按钮）与复核结果一致。
- 真正剩下的不是「大面积没做」，而是**三类零散缺口**：世界书高级语义、变量作用域与 MVU 阶段链、宿主侧后台与文件能力。
- 产品功能面上，PiuPiu 有而 Xujing 没有的多数是**账号/服务端绑定**的能力。合并时我把其中 3 项从子代理的「未实现」改判为「不适用」，理由见 §3。
- 安全边界上 Xujing **整体强于** PiuPiu（原生桥有 token 鉴别、逐条加密、关闭备份），唯一明确漏项是**没关 WebView 内容调试**。

## 2. 真实缺口（已去重、按建议顺序）

优先级依据：是否影响「已有卡片的执行正确性」> 数据安全 > 用户可见功能 > 体验项；工作量是 S/M/L 粗估。

### P0：低成本、确定性收益

| # | 缺口 | 现状证据 | 来源 | 工作量 |
| --- | --- | --- | --- | --- |
| 1 | WebView 内容调试未关闭 | 全仓无 `setWebContentsDebuggingEnabled`；PiuPiu 在 `MainActivity.java:251` 启动即关 | native #18 | S（可用 `BuildConfig.DEBUG` 条件保留调试便利） |
| 2 | 世界书默认参数与原版不一致 | `WorldbookEngine.kt:111-114` 默认 25%/2048/递归 8；原版默认 `budgetPercent:0/budgetCap:0/maxRecursionSteps:0/defaultScanDepth:2` | core 附加① | S，但会改变现有行为，需先确认取舍 |
| 3 | 脚本人本收集不兼容旧键 | `SessionScripts.kt:39` 只要 `tavern_helper` 存在就不再读 `TavernHelper_scripts` | core 附加⑤ | S |
| 4 | 回复设置（长度上下限 / 建议条数） | 只有连接的 `max_tokens`；建议条数写死 5 | surface #19 | S |
| 5 | 模型列表拉取与选择器 | 只手填模型 ID，无 `/models` 调用 | surface #6 | S |
| 6 | 事件参数补齐 | 缺 `dryRun:false`、`generation_started` 的 `{}`/`false`、`after_data` 的 `prompt` 别名 | core #13（部分） | S |
| 7 | 生命周期事件点位 | 编辑不发 `message_edited`/`message_updated`；完成链缺 `character_message_rendered`/`message_updated`；`message_sent` 未派发 | core #14（部分） | M |

### P1：兼容性实质缺口

| # | 缺口 | 现状证据 | 来源 | 工作量 |
| --- | --- | --- | --- | --- |
| 8 | 记忆多查询聚合 | `EmbeddingManager.kt:109-118` 把最近 N 条拼成单 query 再截断；原版逐条 embedding 后取 max | core #21 | M |
| 9 | 正常回复内的建议解析 | 仍额外请求 JSON；无 `<reply_suggestions>` 解析与写回消息 | core #20 | M |
| 10 | 世界书 characterFilter / triggers | 收集阶段只做 enabled 过滤，无名称/标签/排除/生成模式 | core #05 | M |
| 11 | 变量作用域 | 只有 chat/message；缺 global/preset/character/extension/script/all 与合并优先级 | core #16 | M |
| 12 | 回忆页 | 记忆只用于检索注入，用户看不到、改不了、删不了 | surface #15 | M |
| 13 | 备份导出流式化 | `AppController.kt:555/561` 整包驻留内存，大资料有 OOM 风险且无进度 | native #11 | M |
| 14 | 生成期间前后台保护 | 生成在 `viewModelScope`，后台可被回收；无完成通知与通知权限 | native #15/#16/#17 | M |

### P2：较大或收益偏窄

| # | 缺口 | 来源 | 备注 |
| --- | --- | --- | --- |
| 15 | MVU/Zod 阶段参与真实输出链 | core #17 | 最大架构项，需定义阶段事件与写回契约 |
| 16 | 世界书装饰器与阶段分流 | core #10 | `@@activate` 等控制行当前会进正文 |
| 17 | 世界书时效内容哈希 | core #11 | 改条目后旧 sticky/cooldown 仍生效 |
| 18 | 世界书互斥组评分 | core #07（部分） | 缺「按命中数先筛」阶段 |
| 19 | 书级 scanDepth 继承 | core #08（部分） | 条目级 0 已支持，书级与全局默认未继承 |
| 20 | injectPrompts / 注册宏 | core #18 | 与「生成前改数组」不是同一机制 |
| 21 | generateRaw 与按任务取消 | core #19 | 需引入 generationId 与流增量回送 |
| 22 | 搜索防抖与高度稳定后二次定位 | core #22 | 体验项 |
| 23 | 外观：正文/引用/括号颜色与预览 | core #02（部分） | 高亮机制已有，缺配置与预览 |
| 24 | 卡片内下载 / 保存文件 | native #8/#9 | 主框架导航被拦且桥无写文件入口；需受 token 保护的接口 |
| 25 | 数据库用量与可用空间 | native #14 | 低风险，可作 #13 的配套 |
| 26 | 诊断事件结构化通道 | native #4（部分） | 排查成本问题，不影响正确性 |
| 27 | 聊天图片上传 / 视觉输入 | surface #5 | 需多模态数据模型（`PromptMessage` 目前纯文本） |
| 28 | 角色标签与分类筛选 | surface #13 | 需 `RoleCard` 增加 tags |
| 29 | 状态栏配置 | surface #20 | 复杂卡依赖；卡片可自绘但不等价 |
| 30 | BGM | surface #37 | 纯氛围项 |

## 3. 重分类：这些不算缺口

子代理把下列项判为「未实现」，但它们的第一依赖是 PiuPiu 的账号体系或官方分发通道，净室重写里不存在对应实体。合并时改判为 **不适用**（不是「刻意不做」——`AGENTS.md` 没有逐项点名，但 `requirements.md:18` 排除了账号与固定凭据）：

| 项 | 原判 | 改判理由 |
| --- | --- | --- |
| 首页（好感度/签到/日运/成就/公告/邮箱） | 未实现 | 数据全部来自 `/site_mailbox.php` 等服务端接口，无账号则无数据源 |
| 反馈（问题/建议） | 未实现 | 原版要求登录并走服务端；可考虑改为「导出诊断信息」本地替代 |
| 官方网页弹窗 / APK 更新 | 未实现 | 依赖原应用官网与分发通道；净室应用应走自己的发布渠道 |

另有 2 项维持「不确定」，不做结论：覆盖式导入的崩溃恢复（Xujing 采用合并导入）、原版 SQLite 迁移（净室不读原库）。

## 4. 需要产品决策的三点

1. **世界书固定随机种子**（core 附加③）：`PromptEngine.kt:38-39` 用 `Random(seed)`，原版用 `Math.random()`。同一条消息重复生成时，Xujing 的世界书命中恒定。若这是为 MVU 回放一致性做的取舍，应在 `docs/compatibility.md` 写明；否则属于行为差异。
2. **世界书默认参数**（P0 #2）：改成原版默认（近乎不限制预算）会让现有长会话更容易顶满上下文。建议改为「未显式配置时按原版默认」，而不是直接替换。
3. **卡片下载能力**（P2 #24）：PiuPiu 靠「不拦主框架导航 + 无鉴权下载接口」实现，Xujing 现在两者都关了。正确补法是新增受 token 保护的写文件接口，而不是放开导航。

## 5. 与「22 条清单」的关系

`docs/piupiu-detail-gap-audit.md` 保留作历史记录，不要直接在其上打勾——它的「当前差异」列对 12 条已经过期。后续应以本文 §2 为排期依据，以三份 `audit-2026-11-04/*.md` 为证据。
