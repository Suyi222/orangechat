# 隙光 OrangeChat · 实现文档（IMPLEMENTATION）

> 本文档记录隙光在 fork 仓库 `Suyi222/orangechat`（分支 `feature/workflow-widget-v2`）上的**代码级实现细节**。
> 计划/进度/画像见 tree-plan `projects/04-orangechat-frontend/`（只放状态与计划，不放代码细节）。
> 版本更新说明见 `docs/RELEASE-2026-08-04-xiguang-2.3.0.md`。

---

## 1. 系统工具层

### 1.1 工作流主动唤醒 `trigger_proactive_message`

**用途**：工作流检测到触发条件（如用户打开游戏、到随机时间点）→ 唤醒 AI 主动查岗。

**实现**：
- 文件：`data/ai/tools/system/TriggerProactiveMessageTool.kt`
- 注册在 `SystemTools`（设置 → 系统工具 → 🌲 工作流主动唤醒）
- 内部调用 `ProactiveMessageTriggerService`
- 参数：`message`（必填）、`include_usage`（默认 true）
- 醒因格式：`【醒因】【要做什么】`，经 `EXTRA_WORKFLOW_WAKEUP` 标志与设备事件唤醒区分

### 1.2 桌面便签 Widget `post_desk_note`

**CRUD 操作**：

| 操作 | 调用方式 |
|:---|:---|
| 新建/更新 | `post_desk_note(content="...")` |
| 删除 | `post_desk_note(content="")` |
| 定时过期 | `post_desk_note(content="...", duration_hours=2)` |

**UI 风格**：半透明圆角卡片，居中文字，点击打开隙光。

### 1.3 历史聊天记录搜索 `search_history`

- 包装已有 `MessageFtsManager`（SQLite FTS5 + jieba 中文分词）
- 返回带高亮 snippet 的排序结果（默认 top 10，最大 50）
- 支持按日期范围过滤（`date_from` / `date_to`）
- 参数：`keyword`（必填）、`date_from`、`date_to`、`limit`
- 注意：FTS 索引消息写入时自动建立；安装前的旧消息可能需要手动触发全量重建

### 1.4 树影下状态工具（state_read / state_write / state_archive / state_echo_read）

- 注册在 `data/ai/tools/treeshadow/`（树影下状态系统）
- `state_read`：AI 生成回复前读取当前状态卡（状态指纹）
- `state_echo_read`：读取"今天回声"（留言）
- `state_write`：AI 自行决定是否写备注（写了打包进上下文）
- `state_archive`：归档今天的状态卡 → 往日页

## 2. 工作流触发

### 2.1 随机时间触发器 `random_time_between`

- 文件：`workflow/trigger/RandomTimeTriggerFamily.kt` + `WorkflowRandomTimeWorker.kt`
- 注册在 `TriggerRegistry`（第 13 家族，位于 time_cron 后）
- 参数：`start`/`end`（HH:mm，支持跨午夜窗口如 22:00~08:00）、`min_interval_minutes`（默认 120）、`max_triggers_per_day`（默认 3）
- 每次触发后自动重排下一次随机点；达每日上限后 `scheduleWorkAfterCurrentWindow` 跳到次日窗口，不再空跑
- 凌晨归属逻辑：先查「昨晚窗口」再查今天，避免凌晨错排到当晚

## 3. 主动消息链路

### 3.1 后台工具调用总开关

- `data/datastore/ProactiveMessageSetting.kt`：`allowToolsInProactive`（默认 false）
- `data/service/ProactiveMessageService.kt` L1222：拦截点 `needsApproval && !allowToolsInProactive` 才拒绝，开启后**真·全开**
- 设置页：设置 → 主动消息 → 「允许 AI 在后台调用工具」（带高权限警示）
- 旧白名单机制（`whitelistTools` 候选勾选 + 自定义输入框）已移除

### 3.2 工作流主动唤醒卡

- `ProactiveMessageTriggerService.onStartCommand`：工作流唤醒时
  - userMessage = 完整「唤醒卡」：醒因（被工作流主动唤醒）+ message + 当前时间（星期 + yyyy-MM-dd HH:mm）
  - 过滤 `TimeReminderTransformer`（抑制 time_reminder 抢戏）
  - `buildSystemPrompt` 工作流分支只留规则声明（"以唤醒卡为准"）作兜底

## 4. 数据层

- `data/db/AppDatabase.kt`：Room version 30，AutoMigration 29→30 建 `tree_shadow` 表
- 实体 `TreeShadowEntry` + DAO `TreeShadowDao` + 服务 `TreeShadowService`（`today()` companion）
- 注入顺序：树影下状态注入在工具之后（提高缓存命中率）

## 5. 改动文件清单（截至 2.3.0）

| 文件 | 操作 | 说明 |
|:---|:---:|:---|
| `data/ai/tools/system/TriggerProactiveMessageTool.kt` | 修改 | 醒因格式 + EXTRA_WORKFLOW_WAKEUP |
| `data/ai/tools/system/DeskNoteTool.kt` | 新建 | 桌面便签工具 |
| `data/ai/tools/system/SearchHistoryTool.kt` | 新建 | 历史聊天记录搜索（FTS5+jieba） |
| `data/ai/tools/treeshadow/` | 新建 | 树影下状态工具集（state_read/write/archive/echo_read） |
| `data/ai/GenerationPrompts.kt` | 修改 | 状态卡注入上下文 |
| `data/db/AppDatabase.kt` | 修改 | version 30 + AutoMigration 29→30 |
| `data/db/entity/TreeShadowEntry.kt` | 新建 | 树影下表实体 |
| `data/db/dao/TreeShadowDao.kt` | 新建 | 树影下 DAO |
| `data/service/TreeShadowService.kt` | 新建 | 树影下业务服务 |
| `data/service/ProactiveMessageService.kt` | 修改 | 总开关拦截 + 唤醒卡 + 抑制 time_reminder |
| `data/datastore/ProactiveMessageSetting.kt` | 修改 | allowToolsInProactive，删除 whitelistTools |
| `workflow/trigger/RandomTimeTriggerFamily.kt` | 新建 | 随机时间触发器 |
| `workflow/trigger/WorkflowRandomTimeWorker.kt` | 新建 | 随机时间 Worker |
| `workflow/trigger/TriggerRegistry.kt` | 修改 | 注册随机时间家族 |
| `workflow/model/TriggerSpec.kt` | 修改 | RandomTimeBetween 变体 |
| `workflow/model/WorkflowJson.kt` | 修改 | sanityCheckTrigger 校验 |
| `workflow/tools/WorkflowTools.kt` | 修改 | workflow_create 文档支持随机时间 |
| `ui/pages/treeshadow/` | 新建 | 树影下页面（今天/往日） |
| `ui/pages/setting/SettingProactiveMessagePage.kt` | 修改 | 总开关 UI（删除白名单） |
| `widget/DeskNoteWidgetProvider.kt` 等 | 新建 | 桌面便签 Widget |
| `ui/pages/...` 品牌相关 | 修改 | 橘瓣 → 隙光（图标/加载动画/文案） |
